package com.mau89.talkloop

import com.mau89.talkloop.llm.OllamaTuning

internal data class OllamaTuningForm(
    val temperature: String,
    val presencePenalty: String,
    val context: String,
    val answer: String,
    val memory: String,
    val verdict: String,
    val recipePrompts: Boolean,
    val thinking: Boolean,
) {
    fun parse(): OllamaTuning? = runCatching {
        val profile = OllamaTuning(
            contextWindowTokens = context.trim().toInt(),
            temperature = temperature.trim().replace(',', '.').toDouble(),
            presencePenalty = presencePenalty.trim().replace(',', '.').toDouble(),
            answerMaxTokens = answer.trim().toInt(),
            memoryMaxTokens = memory.trim().toInt(),
            verdictMaxTokens = verdict.trim().toInt(),
            recipePrompts = recipePrompts,
            thinking = thinking,
        )
        require(listOf(profile.answerMaxTokens, profile.memoryMaxTokens, profile.verdictMaxTokens)
            .all { it < profile.contextWindowTokens })
        profile
    }.getOrNull()

    companion object {
        fun from(profile: OllamaTuning) = OllamaTuningForm(
            profile.temperature.toString(), profile.presencePenalty.toString(),
            profile.contextWindowTokens.toString(), profile.answerMaxTokens.toString(),
            profile.memoryMaxTokens.toString(), profile.verdictMaxTokens.toString(),
            profile.recipePrompts, profile.thinking,
        )
        val baseline = OllamaTuning(contextWindowTokens = 32768, temperature = 1.0,
            presencePenalty = 1.5, answerMaxTokens = 2048, memoryMaxTokens = 2600,
            verdictMaxTokens = 1200, recipePrompts = false)
    }
}
