package com.mau89.talkloop.llm

/**
 * Лаборатория температуры: один запрос — три значения temperature.
 *
 * Запрос любой — сравнение точности, креативности и разнообразия остаётся
 * на глаз. Здесь только гоняем одну и ту же фразу при разных температурах
 * и считаем, насколько ответы расходятся между прогонами.
 */

/** Три точки из задания. Anthropic API принимает только 0..1 — 1.2 уходит как 1.0. */
val TEMPERATURE_PRESETS = listOf(0.0, 0.7, 1.2)

/** Верхняя граница temperature у Anthropic Messages API. */
const val ANTHROPIC_MAX_TEMPERATURE = 1.0

/** Значение, которое реально уходит в запрос. */
fun effectiveTemperature(requested: Double): Double =
    requested.coerceIn(0.0, ANTHROPIC_MAX_TEMPERATURE)

fun temperatureWasClamped(requested: Double): Boolean =
    requested > ANTHROPIC_MAX_TEMPERATURE

data class TemperaturePreset(
    val value: Double,
    val title: String,
    val hint: String,
)

val TEMPERATURE_LEVELS = listOf(
    TemperaturePreset(
        value = 0.0,
        title = "0",
        hint = "Минимум случайности: ответы почти одинаковые, факты стабильнее.",
    ),
    TemperaturePreset(
        value = 0.7,
        title = "0.7",
        hint = "Баланс: немного вариативности без явной «рваности».",
    ),
    TemperaturePreset(
        value = 1.2,
        title = "1.2",
        hint = "Максимум случайности. У Anthropic потолок 1.0 — в API уходит 1.0.",
    ),
)

const val TEMPERATURE_LAB_MAX_TOKENS = 1024

/** Пример запроса — поле на экране можно переписать на любой свой. */
const val TEMPERATURE_LAB_PROMPT = "Расскажи мне теорему Пифагора"

fun temperatureSpec(temperature: Double): ResponseSpec =
    ResponseSpec(
        maxTokens = TEMPERATURE_LAB_MAX_TOKENS,
        temperature = effectiveTemperature(temperature),
    )

fun temperatureLabRequest(prompt: String = TEMPERATURE_LAB_PROMPT): List<ChatMessage> =
    listOf(ChatMessage(fromUser = true, text = prompt.trim()))

/** Сколько разных непустых ответов среди списка — для оценки разнообразия. */
fun distinctCount(texts: List<String?>): Int =
    texts.mapNotNull { text ->
        text?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }.toSet().size

/** Короткая подпись к доле уникальных ответов на нескольких прогонах. */
fun diversityLabel(unique: Int, total: Int): String = when {
    total <= 1 -> "—"
    unique == 1 -> "все одинаковые"
    unique == total -> "все разные"
    else -> "$unique из $total разных"
}
