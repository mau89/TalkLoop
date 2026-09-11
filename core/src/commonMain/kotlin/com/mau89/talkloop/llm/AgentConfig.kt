package com.mau89.talkloop.llm

/**
 * Полная конфигурация одного логического агента.
 *
 * Сетевой клиент сюда намеренно не входит: его переиспользует [AgentRuntime],
 * поэтому создание большого числа агентов не создаёт столько же HttpClient.
 */
data class AgentConfig(
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** Можно уменьшить в лаборатории, чтобы безопасно воспроизвести переполнение. */
    val contextWindowTokens: Int = contextWindowForModel(model),
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val contextCompression: ContextCompressionConfig = ContextCompressionConfig(),
    val inputPolicies: List<InputPolicy> = listOf(NonBlankInputPolicy),
    val outputPolicies: List<OutputPolicy> = listOf(NonBlankOutputPolicy),
    val judge: AgentJudge? = null,
) {
    init {
        require(model.isNotBlank()) { "Модель агента не должна быть пустой" }
        require(maxTokens > 0) { "maxTokens должен быть больше нуля" }
        require(contextWindowTokens > 0) { "contextWindowTokens должен быть больше нуля" }
    }
}

data class InputPolicyContext(
    val history: List<ChatMessage>,
)

data class OutputPolicyContext(
    val request: String,
    val history: List<ChatMessage>,
)

data class JudgeContext(
    val request: String,
    val response: String,
    val history: List<ChatMessage>,
)

/** Политика может проверить, нормализовать или отклонить пользовательский ввод. */
fun interface InputPolicy {
    suspend fun apply(input: String, context: InputPolicyContext): String
}

/** Политика может проверить, нормализовать или отклонить ответ модели. */
fun interface OutputPolicy {
    suspend fun apply(output: String, context: OutputPolicyContext): String
}

fun interface AgentJudge {
    suspend fun evaluate(context: JudgeContext): JudgeVerdict
}

data class JudgeVerdict(
    val accepted: Boolean,
    val reason: String? = null,
)

data object NonBlankInputPolicy : InputPolicy {
    override suspend fun apply(input: String, context: InputPolicyContext): String =
        input.trim().takeIf(String::isNotEmpty)
            ?: throw AgentPolicyException("Запрос пользователя не должен быть пустым")
}

data object NonBlankOutputPolicy : OutputPolicy {
    override suspend fun apply(output: String, context: OutputPolicyContext): String =
        output.trim().takeIf(String::isNotEmpty)
            ?: throw AgentPolicyException("Модель вернула пустой ответ")
}

class AgentPolicyException(message: String) : IllegalArgumentException(message)

class AgentRejectedException(message: String) : IllegalStateException(message)
