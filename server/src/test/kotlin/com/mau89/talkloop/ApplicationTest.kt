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
            module(weatherApi = weatherApi)
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun mcpPublishesAndRunsWeatherTool() = testApplication {
        application {
            module(enableDnsRebindingProtection = false, weatherApi = weatherApi)
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

            val tool = mcpClient.listTools().tools.single {
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
            module(enableDnsRebindingProtection = false, weatherApi = weatherApi)
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
}
