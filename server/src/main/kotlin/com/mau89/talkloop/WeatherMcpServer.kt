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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** API погоды, которое MCP-сервер адаптирует к стандартному tools/call. */
fun interface WeatherApi {
    suspend fun getCurrentWeather(city: String): WeatherSnapshot?
}

@Serializable
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

/** Регистрирует текущую погоду и фоновые инструменты планировщика. */
fun createWeatherMcpServer(
    api: WeatherApi,
    scheduler: WeatherSummaryScheduler,
): Server = Server(
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

    addTool(
        name = "schedule_weather_collection",
        description = "Запустить или обновить периодический сбор погоды для города. " +
            "Первый замер выполняется сразу, остальные — в фоне, пока MCP-сервер работает.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("city", buildJsonObject {
                    put("type", "string")
                    put("description", "Город, для которого нужно собирать погоду")
                    put("minLength", 2)
                })
                put("interval_minutes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Период сбора в минутах, от 1 до 1440")
                    put("minimum", 1)
                    put("maximum", 1440)
                })
            },
            required = listOf("city", "interval_minutes"),
        ),
        outputSchema = scheduledJobOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Запланировать сбор погоды",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.trim().orEmpty()
        val intervalMinutes = request.arguments
            ?.get("interval_minutes")
            ?.jsonPrimitive
            ?.intOrNull
        try {
            require(intervalMinutes != null) { "Укажите интервал в минутах" }
            val job = scheduler.schedule(city, intervalMinutes)
            val firstSummary = runCatching { scheduler.summary(job.city) }.getOrNull()
            val result = job.toJson(
                samples = firstSummary?.samples,
                latestWeather = firstSummary?.latest,
            )
            val firstMeasurementText = firstSummary?.latest?.let { weather ->
                "Первый замер: ${weather.temperatureC} °C, ${weather.condition}, " +
                    "ощущается как ${weather.feelsLikeC} °C."
            } ?: "Первый замер пока не получен; сервер повторит попытку по расписанию."
            CallToolResult(
                content = listOf(
                    TextContent(
                        "Сбор погоды для города ${job.city} запущен каждые " +
                            "${job.intervalMinutes} мин. $firstMeasurementText"
                    )
                ),
                structuredContent = result,
                isError = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolError(e.message ?: "Не удалось создать расписание")
        }
    }

    addTool(
        name = "get_weather_summary",
        description = "Получить агрегированную сводку по сохранённым фоновым замерам: " +
            "минимальную, среднюю и максимальную температуру, влажность и ветер.",
        inputSchema = optionalCityInputSchema(),
        outputSchema = weatherSummaryOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Получить сводку погоды",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
        try {
            val summary = scheduler.summary(city)
            val result = summary.toJson()
            CallToolResult(
                content = listOf(
                    TextContent(
                        "Сводка для ${summary.job.city}: ${summary.samples} замеров, " +
                            "температура от ${summary.minimumTemperatureC} до " +
                            "${summary.maximumTemperatureC} °C, средняя " +
                            "${summary.averageTemperatureC.rounded()} °C."
                    )
                ),
                structuredContent = result,
                isError = false,
            )
        } catch (e: Exception) {
            toolError(e.message ?: "Не удалось получить сводку")
        }
    }

    addTool(
        name = "get_active_weather_collection",
        description = "Получить последнее активное расписание сбора погоды. " +
            "Используется агентом для восстановления автоматических сводок после перезапуска.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        outputSchema = activeCollectionOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Восстановить активный сбор погоды",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        val job = scheduler.latestActiveJob()
        val result = job?.toJson() ?: buildJsonObject { put("active", false) }
        CallToolResult(
            content = listOf(
                TextContent(
                    job?.let {
                        "Активен сбор для города ${it.city} каждые ${it.intervalMinutes} мин."
                    } ?: "Активных сборов погоды нет."
                )
            ),
            structuredContent = result,
            isError = false,
        )
    }

    addTool(
        name = "cancel_weather_collection",
        description = "Остановить периодический сбор погоды. Уже накопленные замеры сохраняются.",
        inputSchema = optionalCityInputSchema(),
        outputSchema = scheduledJobOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Остановить сбор погоды",
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
        try {
            val job = scheduler.cancel(city)
            CallToolResult(
                content = listOf(TextContent("Сбор погоды для города ${job.city} остановлен.")),
                structuredContent = job.toJson(),
                isError = false,
            )
        } catch (e: Exception) {
            toolError(e.message ?: "Не удалось остановить расписание")
        }
    }
}

private fun ScheduledWeatherJob.toJson(
    samples: Int? = null,
    latestWeather: WeatherSnapshot? = null,
): JsonObject = buildJsonObject {
    put("job_id", id)
    put("city", city)
    put("interval_minutes", intervalMinutes)
    put("active", active)
    put("created_at", epochToIso(createdAtEpochMs))
    put("next_run_at", epochToIso(nextRunAtEpochMs))
    lastRunAtEpochMs?.let { put("last_run_at", epochToIso(it)) }
    lastError?.let { put("last_error", it) }
    samples?.let { put("samples", it) }
    latestWeather?.let { put("latest_weather", it.toJson()) }
}

private fun WeatherSummary.toJson(): JsonObject = buildJsonObject {
    put("job_id", job.id)
    put("city", job.city)
    put("active", job.active)
    put("interval_minutes", job.intervalMinutes)
    put("samples", samples)
    put("period_started_at", epochToIso(firstCollectedAtEpochMs))
    put("period_ended_at", epochToIso(lastCollectedAtEpochMs))
    put("minimum_temperature_c", minimumTemperatureC)
    put("average_temperature_c", averageTemperatureC.rounded())
    put("maximum_temperature_c", maximumTemperatureC)
    put("average_humidity_percent", averageHumidityPercent.rounded())
    put("average_wind_speed_kmh", averageWindSpeedKmh.rounded())
    put("latest", latest.toJson())
}

private fun Double.rounded(): Double = kotlin.math.round(this * 10) / 10

private fun optionalCityInputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("city", buildJsonObject {
            put("type", "string")
            put("description", "Город. Можно не указывать, если сохранено одно задание")
        })
    },
)

private fun scheduledJobOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        listOf("job_id", "city", "created_at", "next_run_at", "last_run_at", "last_error")
            .forEach { field -> put(field, buildJsonObject { put("type", "string") }) }
        put("interval_minutes", buildJsonObject { put("type", "integer") })
        put("samples", buildJsonObject { put("type", "integer") })
        put("active", buildJsonObject { put("type", "boolean") })
        put("latest_weather", buildJsonObject { put("type", "object") })
    },
    required = listOf("job_id", "city", "interval_minutes", "active", "created_at", "next_run_at"),
)

private fun weatherSummaryOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        listOf("job_id", "city", "period_started_at", "period_ended_at")
            .forEach { field -> put(field, buildJsonObject { put("type", "string") }) }
        put("active", buildJsonObject { put("type", "boolean") })
        listOf("interval_minutes", "samples").forEach { field ->
            put(field, buildJsonObject { put("type", "integer") })
        }
        listOf(
            "minimum_temperature_c",
            "average_temperature_c",
            "maximum_temperature_c",
            "average_humidity_percent",
            "average_wind_speed_kmh",
        ).forEach { field -> put(field, buildJsonObject { put("type", "number") }) }
        put("latest", buildJsonObject { put("type", "object") })
    },
    required = listOf(
        "job_id",
        "city",
        "active",
        "interval_minutes",
        "samples",
        "period_started_at",
        "period_ended_at",
        "minimum_temperature_c",
        "average_temperature_c",
        "maximum_temperature_c",
        "average_humidity_percent",
        "average_wind_speed_kmh",
        "latest",
    ),
)

private fun activeCollectionOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("active", buildJsonObject { put("type", "boolean") })
        put("job_id", buildJsonObject { put("type", "string") })
        put("city", buildJsonObject { put("type", "string") })
        put("interval_minutes", buildJsonObject { put("type", "integer") })
        put("created_at", buildJsonObject { put("type", "string") })
        put("next_run_at", buildJsonObject { put("type", "string") })
        put("last_run_at", buildJsonObject { put("type", "string") })
    },
    required = listOf("active"),
)

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
