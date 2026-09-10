package com.mau89.talkloop.llm

/**
 * Метрики одного хода диалога.
 *
 * [requestTokens] — текущая реплика пользователя, посчитанная отдельно.
 * [inputTokens] — весь вход модели: system prompt, прежняя история и новая реплика.
 * [outputTokens] — ответ модели. Только input/output тарифицируются; предварительный
 * вызов Token Count API бесплатный и нужен, чтобы поймать переполнение заранее.
 */
data class AgentTurnUsage(
    val turn: Int,
    val requestTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val contextWindowTokens: Int,
    val inputCostUsd: Double,
    val outputCostUsd: Double,
    val stopReason: String?,
    val outcome: TokenTurnOutcome,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val costUsd: Double get() = inputCostUsd + outputCostUsd
    val contextUsage: Double
        get() = if (contextWindowTokens <= 0) 0.0
        else inputTokens.toDouble() / contextWindowTokens
}

enum class TokenTurnOutcome {
    COMPLETED,
    REJECTED_BEFORE_SEND,
    RESPONSE_REACHED_CONTEXT_LIMIT,
}

data class TokenPricing(
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
)

const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000

/** Публичные обычные тарифы Anthropic, без prompt caching и batch-скидок. */
fun tokenPricingForModel(model: String): TokenPricing? = when {
    model.startsWith("claude-haiku-4-5") -> TokenPricing(1.0, 5.0)
    model.startsWith("claude-sonnet-5") -> TokenPricing(2.0, 10.0)
    model.startsWith("claude-opus-5") -> TokenPricing(5.0, 25.0)
    else -> null
}

/** Размеры контекста используемых в лаборатории моделей. */
fun contextWindowForModel(model: String): Int = when {
    model.startsWith("claude-sonnet-5") || model.startsWith("claude-opus-5") -> 1_000_000
    else -> DEFAULT_CONTEXT_WINDOW_TOKENS
}

fun estimateTokenCostUsd(
    pricing: TokenPricing?,
    inputTokens: Int,
    outputTokens: Int,
    cacheCreationInputTokens: Int = 0,
    cacheReadInputTokens: Int = 0,
    cacheCreation5mInputTokens: Int = 0,
    cacheCreation1hInputTokens: Int = 0,
): Pair<Double, Double> {
    if (pricing == null) return 0.0 to 0.0
    val unclassifiedCacheWrites = (
        cacheCreationInputTokens - cacheCreation5mInputTokens - cacheCreation1hInputTokens
        ).coerceAtLeast(0)
    val inputCost = (
        inputTokens * pricing.inputUsdPerMillion +
            (cacheCreation5mInputTokens + unclassifiedCacheWrites) *
                pricing.inputUsdPerMillion * 1.25 +
            cacheCreation1hInputTokens * pricing.inputUsdPerMillion * 2.0 +
            cacheReadInputTokens * pricing.inputUsdPerMillion * 0.1
        ) / 1_000_000.0
    return inputCost to
        outputTokens * pricing.outputUsdPerMillion / 1_000_000.0
}

class ContextWindowExceededException(
    val inputTokens: Int,
    val contextWindowTokens: Int,
    message: String =
        "Контекст переполнен: $inputTokens токенов при лимите $contextWindowTokens. " +
            "Запрос не отправлен, история не изменилась.",
) : IllegalStateException(message)
