package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
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
    val compression: ContextCompressionStatistics = ContextCompressionStatistics(),
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val totalCostUsd: Double get() = turns.sumOf(AgentTurnUsage::costUsd)
    val lastTurn: AgentTurnUsage? get() = turns.lastOrNull()
    val allInputTokens: Int get() = inputTokens + compression.summaryInputTokens
    val allOutputTokens: Int get() = outputTokens + compression.summaryOutputTokens
    val allTokens: Int get() = allInputTokens + allOutputTokens
    val allCostUsd: Double get() = totalCostUsd + compression.summaryCostUsd
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
    private val mutableSummary = MutableStateFlow(
        historyStore.loadSummary().takeIf { config.contextCompression.enabled }
    )
    private val mutableStatistics = MutableStateFlow(AgentStatistics())

    val history: StateFlow<List<ChatMessage>> = mutableHistory.asStateFlow()
    val summary: StateFlow<String?> = mutableSummary.asStateFlow()
    val statistics: StateFlow<AgentStatistics> = mutableStatistics.asStateFlow()

    suspend fun respond(userRequest: String): String {
        return mutex.withLock {
            val historySnapshot = mutableHistory.value
            val request = config.inputPolicies.foldSuspend(userRequest) { input, policy ->
                policy.apply(input, InputPolicyContext(historySnapshot))
            }
            val userMessage = ChatMessage(fromUser = true, text = request)
            val turn = mutableStatistics.value.requestCount + 1
            val summarySnapshot = mutableSummary.value
            val spec = ResponseSpec(
                system = systemPromptWithSummary(config.systemPrompt, summarySnapshot),
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
            val compacted = compactIfNeeded(
                messages = updatedHistory,
                previousSummary = summarySnapshot,
                conversationSpec = spec,
            )
            // История и summary записываются одним JSON-документом: после сбоя не
            // получится состояния, где новая память относится к старым репликам.
            historyStore.save(compacted.messages, compacted.summary)
            mutableHistory.value = compacted.messages
            mutableSummary.value = compacted.summary
            response
        }
    }

    private suspend fun compactIfNeeded(
        messages: List<ChatMessage>,
        previousSummary: String?,
        conversationSpec: ResponseSpec,
    ): CompactedContext {
        val compressionConfig = config.contextCompression
        if (!compressionConfig.enabled) {
            return CompactedContext(messages = messages, summary = null)
        }

        val messagesToCompressCount = messages.size - compressionConfig.keepLastMessages
        if (messagesToCompressCount <= 0) {
            return CompactedContext(messages = messages, summary = previousSummary)
        }

        val messagesToCompress = messages.take(messagesToCompressCount)
        val recentMessages = messages.takeLast(compressionConfig.keepLastMessages)
        val summarySpec = ResponseSpec(
            system = summarySystemPrompt(previousSummary),
            maxTokens = compressionConfig.summaryMaxTokens,
            model = config.model,
        )

        return try {
            val beforeTokens = llmClient.countInputTokens(
                history = messages,
                spec = conversationSpec,
            )
            val summaryAnswer = llmClient.answer(
                history = listOf(summaryInput(messagesToCompress)),
                spec = summarySpec,
            )
            val newSummary = summaryAnswer.text.trim().takeIf(String::isNotEmpty)
                ?: throw AgentPolicyException("Модель вернула пустое summary")
            val afterTokens = llmClient.countInputTokens(
                history = recentMessages,
                spec = conversationSpec.copy(
                    system = systemPromptWithSummary(config.systemPrompt, newSummary),
                ),
            )
            val (summaryInputCost, summaryOutputCost) = estimateTokenCostUsd(
                pricing = tokenPricingForModel(config.model),
                inputTokens = summaryAnswer.inputTokens,
                outputTokens = summaryAnswer.outputTokens,
                cacheCreationInputTokens = summaryAnswer.cacheCreationInputTokens,
                cacheReadInputTokens = summaryAnswer.cacheReadInputTokens,
                cacheCreation5mInputTokens = summaryAnswer.cacheCreation5mInputTokens,
                cacheCreation1hInputTokens = summaryAnswer.cacheCreation1hInputTokens,
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                val compression = current.compression
                current.copy(
                    compression = compression.copy(
                        compressionCount = compression.compressionCount + 1,
                        compressedMessages = compression.compressedMessages +
                            messagesToCompress.size,
                        summaryInputTokens = compression.summaryInputTokens +
                            summaryAnswer.totalInputTokens,
                        summaryOutputTokens = compression.summaryOutputTokens +
                            summaryAnswer.outputTokens,
                        summaryCostUsd = compression.summaryCostUsd +
                            summaryInputCost + summaryOutputCost,
                        latestBeforeTokens = beforeTokens,
                        latestAfterTokens = afterTokens,
                        lastError = null,
                    )
                )
            }
            CompactedContext(messages = recentMessages, summary = newSummary)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Ответ пользователю уже получен и остаётся полезным. При временном сбое
            // summarizer сохраняем полную историю и повторим сжатие на следующем ходе.
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    compression = current.compression.copy(
                        lastError = error.message ?: "Не удалось обновить summary",
                    )
                )
            }
            CompactedContext(messages = messages, summary = previousSummary)
        }
    }
}

private data class CompactedContext(
    val messages: List<ChatMessage>,
    val summary: String?,
)

private suspend inline fun <T, R> Iterable<T>.foldSuspend(
    initial: R,
    operation: suspend (accumulator: R, T) -> R,
): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}
