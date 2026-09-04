package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelVersionsTest {

    @Test
    fun `линейка — три точки от слабой к сильной`() {
        assertEquals(listOf("слабая", "средняя", "сильная"), MODEL_PRESETS.map { it.title })
        assertEquals("claude-haiku-4-5", MODEL_PRESETS[0].id)
        assertEquals("claude-sonnet-5", MODEL_PRESETS[1].id)
        assertEquals("claude-opus-5", MODEL_PRESETS[2].id)
    }

    @Test
    fun `стоимость считается по тарифу за миллион токенов`() {
        val haiku = MODEL_PRESETS.first { it.id == "claude-haiku-4-5" }

        // 1000 in * $1/M + 2000 out * $5/M = 0.001 + 0.01 = 0.011
        assertEquals(0.011, estimateCostUsd(haiku, inputTokens = 1000, outputTokens = 2000))
        assertEquals("$0.011", formatUsd(0.011))
        assertEquals("$0.000012", formatUsd(0.000012))
        assertEquals("$0", formatUsd(0.0))
    }

    @Test
    fun `эталон улитки ловит типичную ошибку слабой модели`() {
        val correct = "День 1: 3, ночь: 1. ... В последний день ночи нет.\nОТВЕТ: 8"
        val trap = "10 / (3 - 2) = 10\nОТВЕТ: 10"
        assertEquals(true, scoreModelAnswer(MODEL_LAB_EXPECTED_HINT, correct))
        assertEquals(false, scoreModelAnswer(MODEL_LAB_EXPECTED_HINT, trap))
        assertEquals(true, scoreModelAnswer(MODEL_LAB_EXPECTED_HINT, "8"))
        assertEquals(null, scoreModelAnswer("", correct))
        assertEquals(null, scoreModelAnswer("  ", trap))
    }

    @Test
    fun `скорость и длительность форматируются без лишних нулей`() {
        assertEquals(50.0, tokensPerSecond(outputTokens = 100, durationMs = 2000))
        assertEquals(0.0, tokensPerSecond(outputTokens = 10, durationMs = 0))
        assertEquals("850 мс", formatDuration(850))
        assertEquals("1.5 с", formatDuration(1500))
        assertEquals("2 с", formatDuration(2000))
        assertEquals("50 ток/с", formatThroughput(50.0))
        assertEquals("12.3 ток/с", formatThroughput(12.34))
        assertEquals("—", formatThroughput(0.0))
    }
}
