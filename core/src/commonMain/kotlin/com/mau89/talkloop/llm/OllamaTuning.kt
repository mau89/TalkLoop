package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject

const val COOKBOOK_OLLAMA_MODEL = "qwen3.5:9b-q8_0"

const val VPS_OLLAMA_MODEL = "qwen2.5:0.5b"
// Includes the agent's memory and invariant instructions, not just the user's question.
val VPS_OLLAMA_TUNING = OllamaTuning(contextWindowTokens = 8192,
    answerMaxTokens = 256, memoryMaxTokens = 256, verdictMaxTokens = 128,
    recipePrompts = false, thinking = false)

/** Explicit, opt-in recipe profile; the untuned transport remains available for comparisons. */
@Serializable
data class OllamaTuning(
    val contextWindowTokens: Int = 24_576,
    val temperature: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val answerMaxTokens: Int = 1600,
    val memoryMaxTokens: Int = 1400,
    val verdictMaxTokens: Int = 400,
    val recipePrompts: Boolean = true,
    val thinking: Boolean = false,
) {
    init {
        require(contextWindowTokens > 0 && temperature.isFinite() && temperature in 0.0..2.0)
        require(presencePenalty.isFinite() && presencePenalty in -2.0..2.0)
        require(listOf(answerMaxTokens, memoryMaxTokens, verdictMaxTokens).all { it > 0 })
    }

    internal fun prepare(spec: ResponseSpec): ResponseSpec {
        val properties = spec.jsonSchema?.get("properties")?.jsonObject?.keys.orEmpty()
        val memory = "resolvedQuestion" in properties && "searchQuery" in properties
        val verdict = "supported" in properties && "unsupportedClaimNumbers" in properties
        val evidence = "claims" in properties && "clarification" in properties
        val instructions = when {
            // A tested extra memory prompt made Q4 copy inflected facts incorrectly.
            // Keep the original memory instructions, changing only sampling and limits.
            memory -> ""
            evidence -> """
                Режим ответа по кулинарной книге: только запрошенные факты, обычно 1–3 claims.
                Для списка ингредиентов предпочитай одно краткое утверждение с одной непрерывной цитатой
                всего нужного участка, включая промежуточные строки и оговорки об исходных порциях.
                Копируй quote из fragments символ в символ, сохраняя знаки, числа и порядок строк.
                Не повторяй один факт разными словами. Не предлагай замены и пересчёт без запроса.
                Перечисляя лишь запрошенные ингредиенты, не называй их полным составом рецепта.
                Если в текущих fragments нет подтверждения, верни unknown с пустым claims.
            """
            verdict -> """
                Дай краткий вердикт по каждому факту, без пересказа рецепта; reason — максимум два предложения.
                Не смягчай правила проверки: все числа, условия и оговорки должны следовать из цитат.
                Отсутствие непрошенных ингредиентов в ответе само по себе не ошибка:
                проверь запрошенные факты, если автор не заявляет, что перечислил весь состав рецепта.
            """
            else -> ""
        }.trimIndent()
        return spec.copy(
            temperature = spec.temperature ?: temperature,
            maxTokens = minOf(spec.maxTokens, when { memory -> memoryMaxTokens; verdict -> verdictMaxTokens; else -> answerMaxTokens }),
            system = if (recipePrompts && instructions.isNotEmpty()) listOfNotNull(spec.system, instructions).joinToString("\n\n") else spec.system,
        )
    }
}
