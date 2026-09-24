package com.mau89.talkloop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** API погоды, которое MCP-сервер адаптирует к стандартному tools/call. */
fun interface WeatherApi {
    suspend fun getCurrentWeather(city: String): WeatherSnapshot?
}

data class WeatherSnapshot(
    val city: String,
    val country: String,
    val observedAt: String,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val condition: String,
)

/** Реальный API без ключа: принимает название города и возвращает текущую погоду. */
class WttrWeatherApi : WeatherApi, AutoCloseable {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun getCurrentWeather(city: String): WeatherSnapshot? {
        val responseText = http.get(WTTR_URL) {
            url { appendPathSegments(city) }
            parameter("format", "j1")
            parameter("lang", "ru")
        }.bodyAsText()
        // wttr.in возвращает JSON с Content-Type text/plain, поэтому декодируем явно.
        val response = WEATHER_JSON.decodeFromString<WttrResponse>(responseText)
        val current = response.currentConditions.firstOrNull() ?: return null
        val area = response.nearestAreas.firstOrNull()

        return WeatherSnapshot(
            city = area?.areaNames?.firstOrNull()?.value ?: city,
            country = area?.countries?.firstOrNull()?.value.orEmpty(),
            observedAt = current.localObservationTime ?: current.observationTime,
            temperatureC = current.temperatureC.toDouble(),
            feelsLikeC = current.feelsLikeC.toDouble(),
            humidityPercent = current.humidity.toInt(),
            precipitationMm = current.precipitationMm.toDouble(),
            windSpeedKmh = current.windSpeedKmh.toDouble(),
            condition = wttrCondition(current),
        )
    }

    override fun close() = http.close()

    private companion object {
        const val WTTR_URL = "https://wttr.in"
        val WEATHER_JSON = Json { ignoreUnknownKeys = true }
    }
}

fun WeatherSnapshot.toJson(): JsonObject = buildJsonObject {
    put("city", city)
    put("country", country)
    put("observed_at", observedAt)
    put("temperature_c", temperatureC)
    put("feels_like_c", feelsLikeC)
    put("humidity_percent", humidityPercent)
    put("precipitation_mm", precipitationMm)
    put("wind_speed_kmh", windSpeedKmh)
    put("condition", condition)
}

/** Регистрирует понятный read-only инструмент получения текущей погоды по городу. */
fun createWeatherMcpServer(api: WeatherApi): Server = Server(
    serverInfo = Implementation(
        name = "talkloop-weather",
        version = "1.0.0",
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        ),
    ),
).apply {
    addTool(
        name = "get_current_weather",
        description = "Получить текущую погоду в указанном городе. " +
            "Используй, когда пользователь спрашивает о погоде или одежде по погоде.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("city", buildJsonObject {
                    put("type", "string")
                    put("description", "Название города, например Екатеринбург")
                    put("minLength", 2)
                })
            },
            required = listOf("city"),
        ),
        outputSchema = weatherOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Узнать текущую погоду",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        val city = request.arguments
            ?.get("city")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            .orEmpty()

        when {
            city.length < 2 -> toolError("Укажите название города минимум из двух символов")
            else -> try {
                val weather = api.getCurrentWeather(city)
                    ?: return@addTool toolError("Город «$city» не найден")
                val result = weather.toJson()
                CallToolResult(
                    content = listOf(
                        TextContent(
                            "Сейчас в ${weather.city}: ${weather.temperatureC} °C, " +
                                "ощущается как ${weather.feelsLikeC} °C, ${weather.condition}, " +
                                "ветер ${weather.windSpeedKmh} км/ч."
                        )
                    ),
                    structuredContent = result,
                    isError = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toolError("Не удалось получить погоду из внешнего API. Повторите позже.")
            }
        }
    }
}

private fun weatherOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        listOf("city", "country", "observed_at", "condition").forEach { field ->
            put(field, buildJsonObject { put("type", "string") })
        }
        listOf(
            "temperature_c",
            "feels_like_c",
            "humidity_percent",
            "precipitation_mm",
            "wind_speed_kmh",
        ).forEach { field ->
            put(field, buildJsonObject { put("type", "number") })
        }
    },
    required = listOf(
        "city",
        "country",
        "observed_at",
        "temperature_c",
        "feels_like_c",
        "humidity_percent",
        "precipitation_mm",
        "wind_speed_kmh",
        "condition",
    ),
)

private fun toolError(message: String): CallToolResult = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)

@Serializable
internal data class WttrResponse(
    @SerialName("current_condition") val currentConditions: List<WttrCurrent> = emptyList(),
    @SerialName("nearest_area") val nearestAreas: List<WttrArea> = emptyList(),
)

@Serializable
internal data class WttrCurrent(
    @SerialName("temp_C") val temperatureC: String,
    @SerialName("FeelsLikeC") val feelsLikeC: String,
    val humidity: String,
    @SerialName("precipMM") val precipitationMm: String,
    @SerialName("windspeedKmph") val windSpeedKmh: String,
    @SerialName("weatherCode") val weatherCode: String,
    @SerialName("observation_time") val observationTime: String,
    @SerialName("localObsDateTime") val localObservationTime: String? = null,
    @SerialName("weatherDesc") val weatherDescriptions: List<WttrText> = emptyList(),
    @SerialName("lang_ru") val russianDescriptions: List<WttrText> = emptyList(),
)

@Serializable
internal data class WttrArea(
    @SerialName("areaName") val areaNames: List<WttrText> = emptyList(),
    val country: List<WttrText> = emptyList(),
) {
    val countries: List<WttrText> get() = country
}

@Serializable
internal data class WttrText(val value: String)

private fun wttrCondition(current: WttrCurrent): String {
    val localized = current.russianDescriptions.firstOrNull()?.value
        ?.takeIf { value -> value.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } }
    if (localized != null) return localized.lowercase()

    return when (current.weatherCode.toIntOrNull()) {
        113 -> "ясно"
        116 -> "переменная облачность"
        119 -> "облачно"
        122 -> "пасмурно"
        143, 248, 260 -> "туман"
        176, 263, 266, 281, 293, 296, 299, 302, 305, 308, 353, 356, 359 -> "дождь"
        179, 182, 185, 227, 230, 317, 320, 323, 326, 329, 332, 335, 338,
        368, 371 -> "снег"
        200, 386, 389, 392, 395 -> "гроза"
        else -> current.weatherDescriptions.firstOrNull()?.value
            ?.lowercase()
            ?: "неизвестные погодные условия"
    }
}
