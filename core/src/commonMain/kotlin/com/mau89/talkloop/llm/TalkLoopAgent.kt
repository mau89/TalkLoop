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
) {
    val totalTokens: Int get() = inputTokens + outputTokens
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
            mutableStatistics.value = mutableStatistics.value.copy(
                requestCount = mutableStatistics.value.requestCount + 1,
            )
            val answer = llmClient.answer(
                history = historySnapshot + userMessage,
                spec = ResponseSpec(
                    system = config.systemPrompt,
                    maxTokens = config.maxTokens,
                    stopSequences = config.stopSequences,
                    temperature = config.temperature,
                    model = config.model,
                ),
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    inputTokens = current.inputTokens + answer.inputTokens,
                    outputTokens = current.outputTokens + answer.outputTokens,
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
