package com.mau89.talkloop

import com.mau89.talkloop.llm.AgentToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
    fun `команды книг и погодной рекомендации распознаются отдельно`() {
        assertEquals("детектив", extractBookSearchQuery("/books детектив"))
        assertEquals("Тюмень", extractWeatherRecommendationCity("/weather-recommend Тюмень"))
        assertEquals(
            "Тюмени",
            extractWeatherRecommendationCity("Порекомендуй книгу по погоде в Тюмени"),
        )
    }

    @Test
    fun `агент маршрутизирует длинный флоу между двумя MCP серверами`() = runTest {
        data class Invocation(val server: String, val tool: String, val arguments: JsonObject)
        val invocations = mutableListOf<Invocation>()
        val weatherResult = weatherJson()
        val moodResult = buildJsonObject {
            put("mood_key", "detective")
            put("genre", "детектив")
            put("query", "detective fiction cozy mystery")
            put("reason", "Дождливая погода подходит для детектива.")
        }
        val booksResult = booksJson()
        val detailsResult = buildJsonObject {
            put("work_key", "/works/OL1W")
            put("title", "Тестовый детектив")
            put("description", "Атмосферное расследование.")
            put("subjects", buildJsonArray {})
            put("open_library_url", "https://openlibrary.org/works/OL1W")
        }
        val executor = McpToolExecutor { endpoint, toolName, arguments ->
            invocations += Invocation(endpoint.name, toolName, arguments)
            val result = when (toolName) {
                "get_current_weather" -> weatherResult
                "choose_book_mood" -> moodResult
                "search_books" -> booksResult
                "get_book_details" -> detailsResult
                else -> error("Unexpected tool $toolName")
            }
            AgentToolCall(
                toolName = toolName,
                toolDescription = toolName,
                inputSchema = "{}",
                arguments = arguments.toString(),
                result = result.toString(),
                serverName = endpoint.name,
            )
        }
        val provider = McpWeatherToolProvider(
            serverUrl = "weather-url",
            bookServerUrl = "books-url",
            toolExecutor = executor,
        )

        val call = assertNotNull(provider.callFor("/weather-recommend Тюмень"))

        assertEquals(
            listOf(
                "talkloop-weather" to "get_current_weather",
                "talkloop-books" to "choose_book_mood",
                "talkloop-books" to "search_books",
                "talkloop-books" to "get_book_details",
            ),
            invocations.map { it.server to it.tool },
        )
        assertEquals(weatherResult, invocations[1].arguments["weather_data"])
        assertEquals(
            moodResult["query"],
            invocations[2].arguments["query"],
        )
        assertEquals(
            "/works/OL1W",
            invocations[3].arguments["work_key"]?.jsonPrimitive?.content,
        )
        assertEquals(4, call.steps.size)
        assertTrue("Тестовый детектив" in call.directResponse.orEmpty())
        assertTrue("дождь" in call.directResponse.orEmpty())
    }

    @Test
    fun `команда books обращается только к книжному MCP серверу`() = runTest {
        val invocations = mutableListOf<Pair<String, String>>()
        val executor = McpToolExecutor { endpoint, toolName, arguments ->
            invocations += endpoint.name to toolName
            AgentToolCall(
                toolName = toolName,
                toolDescription = toolName,
                inputSchema = "{}",
                arguments = arguments.toString(),
                result = booksJson().toString(),
                serverName = endpoint.name,
            )
        }
        val provider = McpWeatherToolProvider(
            serverUrl = "weather-url",
            bookServerUrl = "books-url",
            toolExecutor = executor,
        )

        val call = assertNotNull(provider.callFor("/books детектив"))

        assertEquals(listOf("talkloop-books" to "search_books"), invocations)
        assertTrue("Тестовый детектив" in call.directResponse.orEmpty())
    }

    @Test
    fun `команда weather возвращает готовый ответ без обращения к модели`() = runTest {
        val executor = McpToolExecutor { endpoint, toolName, arguments ->
            AgentToolCall(
                toolName = toolName,
                toolDescription = toolName,
                inputSchema = "{}",
                arguments = arguments.toString(),
                result = weatherJson().toString(),
                serverName = endpoint.name,
            )
        }
        val provider = McpWeatherToolProvider(
            serverUrl = "weather-url",
            bookServerUrl = "books-url",
            toolExecutor = executor,
        )

        val call = assertNotNull(provider.callFor("/weather Тюмень"))

        assertEquals("get_current_weather", call.toolName)
        assertTrue("Погода в Тюмень, Россия" in call.directResponse.orEmpty())
        assertTrue("Сейчас: 8.0 °C" in call.directResponse.orEmpty())
        assertTrue("ветер 10.0 км/ч" in call.directResponse.orEmpty())
    }

    @Test
    fun `команда запуска сбора выбирает планировщик и интервал`() {
        val intent = weatherToolIntent("Собирай погоду в Тюмени каждые 5 минут")

        assertEquals("schedule_weather_collection", intent?.toolName)
        assertEquals("Тюмени", intent?.arguments?.get("city"))
        assertEquals(5, intent?.arguments?.get("interval_minutes"))
    }

    @Test
    fun `команда отчёта выбирает автоматический пайплайн`() {
        val intent = weatherToolIntent("/weather-report Екатеринбург")

        assertEquals("run_weather_report_pipeline", intent?.toolName)
        assertEquals("Екатеринбург", intent?.arguments?.get("city"))
    }

    @Test
    fun `обычная просьба создать отчёт выбирает автоматический пайплайн`() {
        val intent = weatherToolIntent("Создай отчёт о погоде для Тюмени")

        assertEquals("run_weather_report_pipeline", intent?.toolName)
        assertEquals("Тюмени", intent?.arguments?.get("city"))
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

    @Test
    fun `ответ пайплайна показывает все этапы и сохранённый файл`() {
        val result = buildJsonObject {
            put("city", "Тюмень")
            put("file_path", "server-data/reports/weather-тюмень.md")
            put(
                "report_markdown",
                "# Отчёт о погоде: Тюмень\n\n- Температура: 9.0 °C\n- Условия: солнечно",
            )
        }

        val text = formatPipelineResponse(result)

        assertTrue("search_weather_data" in text)
        assertTrue("summarize_weather_data" in text)
        assertTrue("save_weather_report" in text)
        assertTrue("server-data/reports/weather-тюмень.md" in text)
        assertTrue("Температура: 9.0 °C" in text)
        assertTrue("Условия: солнечно" in text)
        assertFalse("# Отчёт" in text)
        assertTrue("• Температура" in text)
    }
}

private fun weatherJson(): JsonObject = buildJsonObject {
    put("city", "Тюмень")
    put("country", "Россия")
    put("observed_at", "2026-09-25T12:00")
    put("temperature_c", 8.0)
    put("feels_like_c", 5.0)
    put("humidity_percent", 80)
    put("precipitation_mm", 1.0)
    put("wind_speed_kmh", 10.0)
    put("condition", "дождь")
}

private fun booksJson(): JsonObject = buildJsonObject {
    put("query", "detective fiction")
    put("language", "ru")
    put("count", 1)
    put("books", buildJsonArray {
        add(buildJsonObject {
            put("work_key", "/works/OL1W")
            put("title", "Тестовый детектив")
            put("authors", buildJsonArray { add(JsonPrimitive("Тестовый автор")) })
            put("first_publish_year", 1934)
            put("open_library_url", "https://openlibrary.org/works/OL1W")
        })
    })
}
