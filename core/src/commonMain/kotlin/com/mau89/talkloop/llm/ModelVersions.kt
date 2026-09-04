package com.mau89.talkloop.llm

import kotlin.math.round

/**
 * Лаборатория версий моделей: один запрос — три точки линейки провайдера.
 *
 * Задание предлагает взять модели из начала, середины и конца каталога
 * Hugging Face. В TalkLoop провайдер — Anthropic, поэтому те же три точки
 * на их линейке: Haiku (слабая/быстрая), Sonnet (средняя), Opus (сильная).
 * Сравниваются скорость и стоимость. Качество — на глаз; эталон
 * можно вписать, тогда появится сверка.
 */

/** Три точки линейки. Порядок — от слабой к сильной, как в задании. */
val MODEL_PRESETS = listOf(
    ModelPreset(
        id = "claude-haiku-4-5",
        title = "слабая",
        hint = "Haiku 4.5: классификация, короткие ответы, где важны цена и задержка.",
        inputUsdPerMTok = 1.0,
        outputUsdPerMTok = 5.0,
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models",
        pricingUrl = "https://platform.claude.com/docs/en/about-claude/pricing",
    ),
    ModelPreset(
        id = "claude-sonnet-5",
        title = "средняя",
        hint = "Sonnet 5: повседневный баланс. Temperature не принимает — adaptive thinking.",
        inputUsdPerMTok = 2.0,
        outputUsdPerMTok = 10.0,
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models",
        pricingUrl = "https://platform.claude.com/docs/en/about-claude/pricing",
    ),
    ModelPreset(
        id = "claude-opus-5",
        title = "сильная",
        hint = "Opus 5: сложные рассуждения. Temperature не принимает — adaptive thinking.",
        inputUsdPerMTok = 5.0,
        outputUsdPerMTok = 25.0,
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models",
        pricingUrl = "https://platform.claude.com/docs/en/about-claude/pricing",
    ),
)

data class ModelPreset(
    val id: String,
    val title: String,
    val hint: String,
    val inputUsdPerMTok: Double,
    val outputUsdPerMTok: Double,
    val docsUrl: String,
    val pricingUrl: String,
)

/**
 * Потолок один на все модели: иначе Opus выиграет уже тем, что ему дали
 * больше токенов, а не тем, что он сильнее. Temperature в запрос не кладём:
 * у Sonnet 5 и Opus 5 параметр снят (adaptive thinking), а 400 на одной
 * модели сломал бы сравнение. Случайность — вкладка «Температура».
 *
 * Лимит с запасом на thinking-блоки новых моделей: это потолок, Haiku
 * его не выбирает целиком.
 */
const val MODEL_LAB_MAX_TOKENS = 4096

/**
 * Пример запроса — поле на экране можно переписать на любой свой.
 * Эталон не обязателен: без него качество сравнивают на глаз.
 *
 * Если всё же сверять: слабая модель часто считает «10 / (3 − 2) = 10»
 * и не замечает, что в последний день улитка уже наверху.
 */
const val MODEL_LAB_PROMPT =
    "Улитка в колодце глубиной 10 метров. За день поднимается на 3 метра, " +
        "за ночь сползает на 2. На какой день она выберется?"

/** Подсказка к примеру, не значение по умолчанию. Пустое поле — без сверки. */
const val MODEL_LAB_EXPECTED_HINT = "8"

fun modelLabSpec(): ResponseSpec =
    ResponseSpec(maxTokens = MODEL_LAB_MAX_TOKENS)

fun modelLabRequest(prompt: String = MODEL_LAB_PROMPT): List<ChatMessage> =
    listOf(ChatMessage(fromUser = true, text = prompt.trim()))

/**
 * Сверка с эталоном. null — эталона нет, сравнивать нечего.
 * Маркер ОТВЕТ: не обязателен: если его нет, смотрим весь текст.
 */
fun scoreModelAnswer(expected: String, text: String): Boolean? {
    if (expected.isBlank()) return null
    return matchesExpected(expected, extractAnswer(text) ?: text)
}

/** Оценка запроса по опубликованным тарифам. Кэш и batch не учитываем. */
fun estimateCostUsd(preset: ModelPreset, inputTokens: Int, outputTokens: Int): Double =
    inputTokens * preset.inputUsdPerMTok / 1_000_000.0 +
        outputTokens * preset.outputUsdPerMTok / 1_000_000.0

fun formatUsd(amount: Double): String {
    if (amount == 0.0) return "$0"
    val micros = round(amount * 1_000_000.0).toLong().coerceAtLeast(0)
    val whole = micros / 1_000_000
    val frac = (micros % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) "$$whole" else "$$whole.$frac"
}

fun tokensPerSecond(outputTokens: Int, durationMs: Long): Double =
    if (durationMs <= 0L || outputTokens <= 0) 0.0 else outputTokens * 1000.0 / durationMs

fun formatDuration(ms: Long): String {
    if (ms < 1000) return "$ms мс"
    val tenths = (ms + 50) / 100
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole с" else "$whole.$frac с"
}

fun formatThroughput(tokensPerSec: Double): String {
    if (tokensPerSec <= 0.0) return "—"
    val tenths = round(tokensPerSec * 10.0).toLong()
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole ток/с" else "$whole.$frac ток/с"
}
