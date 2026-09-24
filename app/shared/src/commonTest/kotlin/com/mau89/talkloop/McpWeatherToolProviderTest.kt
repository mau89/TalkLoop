package com.mau89.talkloop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpWeatherToolProviderTest {
    @Test
    fun `явная команда weather распознаёт город`() {
        assertTrue(isWeatherRequest("/weather Екатеринбург"))
        assertEquals("Екатеринбург", extractWeatherCity("/weather Екатеринбург"))
    }

    @Test
    fun `обычный вопрос о погоде распознаёт город`() {
        val request = "Какая погода в городе Екатеринбург?"

        assertTrue(isWeatherRequest(request))
        assertEquals("Екатеринбург", extractWeatherCity(request))
    }

    @Test
    fun `непогодный вопрос не запускает MCP`() {
        assertFalse(isWeatherRequest("Помоги составить список покупок"))
    }
}
