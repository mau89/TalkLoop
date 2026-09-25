package com.mau89.talkloop

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.*
import io.ktor.server.testing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ApplicationTest {

    @Test
    fun weatherApiResponseCanBeDeserialized() {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<WttrResponse>(
            """{"current_condition":[{"temp_C":"8","FeelsLikeC":"5","humidity":"72","precipMM":"0.0","windspeedKmph":"13","weatherCode":"116","observation_time":"12:00 PM","weatherDesc":[{"value":"Partly cloudy"}]}],"nearest_area":[{"areaName":[{"value":"Тюмень"}],"country":[{"value":"Россия"}]}]}"""
        )

        assertEquals("Тюмень", response.nearestAreas.single().areaNames.single().value)
        assertEquals("8", response.currentConditions.single().temperatureC)
        assertEquals("116", response.currentConditions.single().weatherCode)
    }

    private val weatherApi = WeatherApi { city ->
        if (city.equals("Екатеринбург", ignoreCase = true)) {
            WeatherSnapshot(
                city = "Екатеринбург",
                country = "Россия",
                observedAt = "2026-09-24T12:00",
                temperatureC = 8.5,
                feelsLikeC = 5.2,
                humidityPercent = 74,
                precipitationMm = 0.0,
                windSpeedKmh = 14.0,
                condition = "переменная облачность",
            )
        } else {
            null
        }
    }

    @Test
    fun testRoot() = testApplication {
        application {
            module(
                weatherApi = weatherApi,
                weatherScheduler = testScheduler(weatherApi),
            )
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun mcpPublishesAndRunsWeatherTool() = testApplication {
        application {
            module(
                enableDnsRebindingProtection = false,
                weatherApi = weatherApi,
                weatherScheduler = testScheduler(weatherApi),
            )
        }
        val httpClient = createClient { install(SSE) }
        val mcpClient = Client(
            clientInfo = Implementation("talkloop-test", "1.0.0"),
        )

        try {
            mcpClient.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "http://localhost/mcp",
                )
            )

            val tools = mcpClient.listTools().tools
            assertEquals(
                setOf(
                    "get_current_weather",
                    "schedule_weather_collection",
                    "get_weather_summary",
                    "get_active_weather_collection",
                    "cancel_weather_collection",
                    "search_weather_data",
                    "summarize_weather_data",
                    "save_weather_report",
                    "run_weather_report_pipeline",
                ),
                tools.map { it.name }.toSet(),
            )
            val tool = tools.single {
                it.name == "get_current_weather"
            }
            assertEquals(listOf("city"), tool.inputSchema.required)

            val result = mcpClient.callTool(
                "get_current_weather",
                mapOf("city" to "Екатеринбург"),
            )
            assertFalse(result.isError == true)
            assertEquals(
                "Екатеринбург",
                result.structuredContent?.get("city")?.jsonPrimitive?.content,
            )
            assertEquals(
                8.5,
                result.structuredContent?.get("temperature_c")?.jsonPrimitive?.content?.toDouble(),
            )
        } finally {
            mcpClient.close()
            httpClient.close()
        }
    }

    @Test
    fun mcpReturnsToolErrorForUnknownCity() = testApplication {
        application {
            module(
                enableDnsRebindingProtection = false,
                weatherApi = weatherApi,
                weatherScheduler = testScheduler(weatherApi),
            )
        }
        val httpClient = createClient { install(SSE) }
        val mcpClient = Client(
            clientInfo = Implementation("talkloop-test", "1.0.0"),
        )

        try {
            mcpClient.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "http://localhost/mcp",
                )
            )

            val result = mcpClient.callTool(
                "get_current_weather",
                mapOf("city" to "Несуществующий город"),
            )
            assertTrue(result.isError == true)
        } finally {
            mcpClient.close()
            httpClient.close()
        }
    }

    @Test
    fun mcpRunsReportPipelineAndPassesResultsBetweenTools() = testApplication {
        val savedReports = mutableListOf<Pair<String, String>>()
        val reportStore = WeatherReportStore { city, markdown ->
            savedReports += city to markdown
            SavedWeatherReport(
                filePath = "server-data/reports/weather-test.md",
                bytesWritten = markdown.encodeToByteArray().size,
            )
        }
        application {
            module(
                enableDnsRebindingProtection = false,
                weatherApi = weatherApi,
                weatherScheduler = testScheduler(weatherApi),
                weatherReportStore = reportStore,
            )
        }
        val httpClient = createClient { install(SSE) }
        val mcpClient = Client(
            clientInfo = Implementation("talkloop-test", "1.0.0"),
        )

        try {
            mcpClient.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "http://localhost/mcp",
                )
            )

            val result = mcpClient.callTool(
                "run_weather_report_pipeline",
                mapOf("city" to "Екатеринбург"),
            )

            assertFalse(result.isError == true)
            val content = requireNotNull(result.structuredContent)
            assertEquals("Екатеринбург", content["city"]?.jsonPrimitive?.content)
            assertEquals(
                "search_weather_data -> summarize_weather_data -> save_weather_report",
                content["pipeline"]?.jsonPrimitive?.content,
            )
            assertEquals("server-data/reports/weather-test.md", content["file_path"]?.jsonPrimitive?.content)

            val steps = requireNotNull(content["steps"]).jsonArray
            assertEquals(3, steps.size)
            assertEquals("search_weather_data", steps[0].jsonObject["tool"]?.jsonPrimitive?.content)
            assertEquals("summarize_weather_data", steps[1].jsonObject["tool"]?.jsonPrimitive?.content)
            assertEquals("save_weather_report", steps[2].jsonObject["tool"]?.jsonPrimitive?.content)

            val searchOutput = steps[0].jsonObject.getValue("output").jsonObject
            val summarizeInput = steps[1].jsonObject.getValue("input").jsonObject
            assertEquals(searchOutput, summarizeInput)

            val summarizeOutput = steps[1].jsonObject.getValue("output").jsonObject
            val saveInput = steps[2].jsonObject.getValue("input").jsonObject
            assertEquals(
                summarizeOutput.getValue("report_markdown"),
                saveInput.getValue("report_markdown"),
            )
            assertEquals(1, savedReports.size)
            assertEquals("Екатеринбург", savedReports.single().first)
            assertEquals(
                content["report_markdown"]?.jsonPrimitive?.content,
                savedReports.single().second,
            )
            assertTrue("8.5 °C" in savedReports.single().second)
        } finally {
            mcpClient.close()
            httpClient.close()
        }
    }

    @Test
    fun schedulerRunsPeriodicallyPersistsAndAggregates() = kotlinx.coroutines.runBlocking {
        var now = 1_000L
        var temperature = 10.0
        val store = InMemoryWeatherSchedulerStore()
        val api = WeatherApi { city ->
            weather(city = city, temperatureC = temperature)
        }
        val scheduler = WeatherSummaryScheduler(
            weatherApi = api,
            store = store,
            clock = SchedulerClock { now },
        )

        val job = scheduler.schedule("Тюмень", intervalMinutes = 1)
        assertTrue(job.active)
        assertEquals(1, store.data.measurements.size)

        now += 60_000L
        temperature = 20.0
        scheduler.runDueJobs()

        val summary = scheduler.summary("Тюмень")
        assertEquals(2, summary.samples)
        assertEquals(10.0, summary.minimumTemperatureC)
        assertEquals(15.0, summary.averageTemperatureC)
        assertEquals(20.0, summary.maximumTemperatureC)
        assertEquals(2, store.data.measurements.size)

        val cancelled = scheduler.cancel("Тюмень")
        assertFalse(cancelled.active)

        now += 1_000L
        temperature = 30.0
        val restarted = scheduler.schedule("Тюмень", intervalMinutes = 2)
        val restartedSummary = scheduler.summary("Тюмень")
        assertNotEquals(job.id, restarted.id)
        assertEquals(1, restartedSummary.samples)
        assertEquals(30.0, restartedSummary.averageTemperatureC)
        assertEquals(2, restartedSummary.job.intervalMinutes)
        scheduler.close()
    }

    @Test
    fun mcpSchedulesReturnsSummaryAndCancelsCollection() = testApplication {
        val scheduler = testScheduler(weatherApi)
        application {
            module(
                enableDnsRebindingProtection = false,
                weatherApi = weatherApi,
                weatherScheduler = scheduler,
            )
        }
        val httpClient = createClient { install(SSE) }
        val mcpClient = Client(
            clientInfo = Implementation("talkloop-test", "1.0.0"),
        )

        try {
            mcpClient.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = "http://localhost/mcp",
                )
            )

            val scheduled = mcpClient.callTool(
                "schedule_weather_collection",
                mapOf("city" to "Екатеринбург", "interval_minutes" to 1),
            )
            assertFalse(scheduled.isError == true)
            assertEquals(
                true,
                scheduled.structuredContent?.get("active")?.jsonPrimitive?.content?.toBoolean(),
            )
            assertEquals(
                1,
                scheduled.structuredContent?.get("samples")?.jsonPrimitive?.content?.toInt(),
            )
            assertEquals(
                8.5,
                scheduled.structuredContent
                    ?.get("latest_weather")
                    ?.jsonObject
                    ?.get("temperature_c")
                    ?.jsonPrimitive
                    ?.content
                    ?.toDouble(),
            )

            val active = mcpClient.callTool(
                "get_active_weather_collection",
                emptyMap(),
            )
            assertFalse(active.isError == true)
            assertEquals(
                "Екатеринбург",
                active.structuredContent?.get("city")?.jsonPrimitive?.content,
            )
            assertEquals(
                1,
                active.structuredContent?.get("interval_minutes")?.jsonPrimitive?.content?.toInt(),
            )

            val summary = mcpClient.callTool(
                "get_weather_summary",
                mapOf("city" to "Екатеринбург"),
            )
            assertFalse(summary.isError == true)
            assertEquals(
                1,
                summary.structuredContent?.get("samples")?.jsonPrimitive?.content?.toInt(),
            )

            val cancelled = mcpClient.callTool(
                "cancel_weather_collection",
                mapOf("city" to "Екатеринбург"),
            )
            assertFalse(cancelled.isError == true)
            assertEquals(
                false,
                cancelled.structuredContent?.get("active")?.jsonPrimitive?.content?.toBoolean(),
            )

            val inactive = mcpClient.callTool(
                "get_active_weather_collection",
                emptyMap(),
            )
            assertFalse(inactive.isError == true)
            assertEquals(
                false,
                inactive.structuredContent?.get("active")?.jsonPrimitive?.content?.toBoolean(),
            )
        } finally {
            mcpClient.close()
            httpClient.close()
        }
    }

    private fun testScheduler(api: WeatherApi): WeatherSummaryScheduler =
        WeatherSummaryScheduler(api, InMemoryWeatherSchedulerStore())

    private fun weather(city: String, temperatureC: Double) = WeatherSnapshot(
        city = city,
        country = "Россия",
        observedAt = "2026-09-25T12:00",
        temperatureC = temperatureC,
        feelsLikeC = temperatureC - 2,
        humidityPercent = 70,
        precipitationMm = 0.0,
        windSpeedKmh = 10.0,
        condition = "облачно",
    )
}
