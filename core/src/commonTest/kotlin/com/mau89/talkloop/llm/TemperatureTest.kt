package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemperatureTest {

    @Test
    fun `разнообразие считается по уникальным ответам`() {
        val answers = listOf("один", "один", "два")

        assertEquals(2, distinctCount(answers))
        assertEquals("2 из 3 разных", diversityLabel(2, 3))
        assertEquals("все одинаковые", diversityLabel(1, 3))
        assertEquals("все разные", diversityLabel(3, 3))
    }

    @Test
    fun `температура ограничивается потолком anthropic`() {
        assertEquals(1.0, effectiveTemperature(1.2))
        assertTrue(temperatureWasClamped(1.2))
        assertFalse(temperatureWasClamped(0.7))
    }
}
