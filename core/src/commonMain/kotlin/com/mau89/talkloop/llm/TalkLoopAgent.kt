package com.mau89.talkloop.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val GENERAL_AGENT_SYSTEM_PROMPT = """
    Ты универсальный помощник TalkLoop.
    По умолчанию отвечай на русском языке.
    Пользователь может писать на любом языке: понимай его запрос без ограничений.
    Если пользователь явно просит ответить на другом языке или сам ведёт разговор
    на другом языке, отвечай на выбранном им языке.
    Давай прямые, полезные и понятные ответы.
""".trimIndent()

data class AgentStatistics(
    val requestCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val turns: List<AgentTurnUsage> = emptyList(),
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val totalCostUsd: Double get() = turns.sumOf(AgentTurnUsage::costUsd)
    val lastTurn: AgentTurnUsage? get() = turns.lastOrNull()
}

/**
 * Диалоговый агент.
 *
 * UI знает только о пользовательском запросе и готовом тексте ответа. Агент
 * самостоятельно хранит контекст диалога, формирует историю для LLM и фиксирует
 * новую пару реплик только после успешного ответа API.
 */
class TalkLoopAgent(
    private val llmClient: LlmClient,
    private val config: AgentConfig = AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT),
    initialHistory: List<ChatMessage> = emptyList(),
    private val historyStore: ChatHistoryStore = InMemoryChatHistoryStore(initialHistory),
) {
    private val mutex = Mutex()
    private val mutableHistory = MutableStateFlow(
        historyStore.load().ifEmpty { initialHistory.toList() }
    )
    private val mutableStatistics = MutableStateFlow(AgentStatistics())

    val history: StateFlow<List<ChatMessage>> = mutableHistory.asStateFlow()
    val statistics: StateFlow<AgentStatistics> = mutableStatistics.asStateFlow()

    suspend fun respond(userRequest: String): String {
        return mutex.withLock {
            val historySnapshot = mutableHistory.value
            val request = config.inputPolicies.foldSuspend(userRequest) { input, policy ->
                policy.apply(input, InputPolicyContext(historySnapshot))
            }
            val userMessage = ChatMessage(fromUser = true, text = request)
            val turn = mutableStatistics.value.requestCount + 1
            val spec = ResponseSpec(
                system = config.systemPrompt,
                maxTokens = config.maxTokens,
                stopSequences = config.stopSequences,
                temperature = config.temperature,
                model = config.model,
            )
            mutableStatistics.value = mutableStatistics.value.copy(requestCount = turn)

            // Текущую реплику считаем отдельно, а полный контекст — вместе с system prompt
            // и всей историей. Это две разные метрики из задания, их нельзя подменять
            // длиной строки или делением количества символов на четыре.
            val requestTokens = llmClient.countInputTokens(
                history = listOf(userMessage),
                spec = spec.copy(system = null),
            )
            val inputTokensBeforeSend = llmClient.countInputTokens(
                history = historySnapshot + userMessage,
                spec = spec,
            )
            val pricing = tokenPricingForModel(config.model)

            if (inputTokensBeforeSend > config.contextWindowTokens) {
                val rejected = AgentTurnUsage(
                    turn = turn,
                    requestTokens = requestTokens,
                    inputTokens = inputTokensBeforeSend,
                    outputTokens = 0,
                    contextWindowTokens = config.contextWindowTokens,
                    inputCostUsd = 0.0,
                    outputCostUsd = 0.0,
                    stopReason = null,
                    outcome = TokenTurnOutcome.REJECTED_BEFORE_SEND,
                )
                mutableStatistics.value = mutableStatistics.value.let { current ->
                    current.copy(turns = current.turns + rejected)
                }
                throw ContextWindowExceededException(
                    inputTokens = inputTokensBeforeSend,
                    contextWindowTokens = config.contextWindowTokens,
                )
            }

            val answer = llmClient.answer(
                history = historySnapshot + userMessage,
                spec = spec,
            )
            val (inputCost, outputCost) = estimateTokenCostUsd(
                pricing = pricing,
                inputTokens = answer.inputTokens,
                outputTokens = answer.outputTokens,
                cacheCreationInputTokens = answer.cacheCreationInputTokens,
                cacheReadInputTokens = answer.cacheReadInputTokens,
                cacheCreation5mInputTokens = answer.cacheCreation5mInputTokens,
                cacheCreation1hInputTokens = answer.cacheCreation1hInputTokens,
            )
            val reachedContextLimit = answer.stopReason == "model_context_window_exceeded"
            val turnUsage = AgentTurnUsage(
                turn = turn,
                requestTokens = requestTokens,
                inputTokens = answer.totalInputTokens,
                outputTokens = answer.outputTokens,
                contextWindowTokens = config.contextWindowTokens,
                inputCostUsd = inputCost,
                outputCostUsd = outputCost,
                stopReason = answer.stopReason,
                outcome = if (reachedContextLimit) {
                    TokenTurnOutcome.RESPONSE_REACHED_CONTEXT_LIMIT
                } else {
                    TokenTurnOutcome.COMPLETED
                },
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    inputTokens = current.inputTokens + answer.totalInputTokens,
                    outputTokens = current.outputTokens + answer.outputTokens,
                    turns = current.turns + turnUsage,
                )
            }
            if (reachedContextLimit) {
                throw ContextWindowExceededException(
                    inputTokens = answer.totalInputTokens + answer.outputTokens,
                    contextWindowTokens = config.contextWindowTokens,
                    message = "Модель упёрлась в окно контекста и оборвала ответ после " +
                        "${answer.outputTokens} токенов. Неполный ответ не сохранён в истории.",
                )
            }
            val response = config.outputPolicies.foldSuspend(answer.text) { output, policy ->
                policy.apply(
                    output,
                    OutputPolicyContext(request = request, history = historySnapshot),
                )
            }
            val verdict = config.judge?.evaluate(
                JudgeContext(
                    request = request,
                    response = response,
                    history = historySnapshot,
                )
            )
            if (verdict != null && !verdict.accepted) {
                throw AgentRejectedException(verdict.reason ?: "Judge отклонил ответ модели")
            }

            val updatedHistory = historySnapshot + userMessage +
                ChatMessage(fromUser = false, text = response)
            // Сначала подтверждаем постоянную запись. Если она не удалась,
            // оперативная история тоже остаётся на прежнем согласованном состоянии.
            historyStore.save(updatedHistory)
            mutableHistory.value = updatedHistory
            response
        }
    }
}

private suspend inline fun <T, R> Iterable<T>.foldSuspend(
    initial: R,
    operation: suspend (accumulator: R, T) -> R,
): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}
