package com.mau89.talkloop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    @Test
    fun `команда запуска сбора выбирает планировщик и интервал`() {
        val intent = weatherToolIntent("Собирай погоду в Тюмени каждые 5 минут")

        assertEquals("schedule_weather_collection", intent?.toolName)
        assertEquals("Тюмени", intent?.arguments?.get("city"))
        assertEquals(5, intent?.arguments?.get("interval_minutes"))
    }

    @Test
    fun `команда сводки выбирает агрегирующий инструмент`() {
        val intent = weatherToolIntent("Покажи сводку погоды по Тюмени")

        assertEquals("get_weather_summary", intent?.toolName)
        assertEquals("Тюмени", intent?.arguments?.get("city"))
    }

    @Test
    fun `команда остановки выбирает отмену расписания`() {
        val intent = weatherToolIntent("Останови сбор погоды в Тюмени")

        assertEquals("cancel_weather_collection", intent?.toolName)
        assertEquals("Тюмени", intent?.arguments?.get("city"))
    }

    @Test
    fun `команда watch без единицы измерения принимает минуты`() {
        val intent = weatherToolIntent("/weather-watch Екатеринбург 3")

        assertEquals("Екатеринбург", intent?.arguments?.get("city"))
        assertEquals(3, intent?.arguments?.get("interval_minutes"))
    }

    @Test
    fun `аргументы разных типов преобразуются в JSON без сериализации Any`() {
        val json = mapOf<String, Any?>(
            "city" to "Тюмень",
            "interval_minutes" to 1,
        ).toJsonObject()

        assertEquals("Тюмень", json.getValue("city").jsonPrimitive.content)
        assertEquals(1, json.getValue("interval_minutes").jsonPrimitive.content.toInt())
    }

    @Test
    fun `сводка различает частоту и период наблюдений`() {
        val result = buildJsonObject {
            put("city", "Тюмень")
            put("interval_minutes", 2)
            put("samples", 3)
            put("period_started_at", "13:00")
            put("period_ended_at", "13:04")
            put("minimum_temperature_c", 10)
            put("average_temperature_c", 11)
            put("maximum_temperature_c", 12)
            put("latest", buildJsonObject {
                put("temperature_c", 12)
                put("condition", "ясно")
            })
        }

        val text = formatSummaryResponse(result, automatic = true)

        assertTrue("каждые 2 мин" in text)
        assertTrue("3" in text)
        assertTrue("13:00 — 13:04" in text)
        assertFalse("последние 2" in text)
    }

    @Test
    fun `ответ запуска сразу показывает первый замер погоды`() {
        val result = buildJsonObject {
            put("city", "Тюмень")
            put("interval_minutes", 2)
            put("samples", 1)
            put("next_run_at", "13:02")
            put("latest_weather", buildJsonObject {
                put("temperature_c", 15)
                put("feels_like_c", 10)
                put("humidity_percent", 48)
                put("wind_speed_kmh", 18)
                put("condition", "дождь")
            })
        }

        val text = formatScheduleResponse(result)

        assertTrue("Сейчас: 15 °C" in text)
        assertTrue("ощущается как 10 °C" in text)
        assertTrue("дождь" in text)
        assertTrue("каждые 2 мин" in text)
    }
}
