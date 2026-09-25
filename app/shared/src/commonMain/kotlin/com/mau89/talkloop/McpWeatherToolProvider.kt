package com.mau89.talkloop

import com.mau89.talkloop.llm.AgentToolCall
import com.mau89.talkloop.llm.AgentToolProvider
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** MCP-инструмент обычного TalkLoopAgent, а не отдельный экран или отдельный агент. */
class McpWeatherToolProvider(
    private val serverUrl: String = defaultMcpServerUrl(),
) : AgentToolProvider {
    private val mutableMonitoring = MutableStateFlow<WeatherMonitoring?>(null)
    val monitoring: StateFlow<WeatherMonitoring?> = mutableMonitoring.asStateFlow()

    override suspend fun callFor(request: String): AgentToolCall? {
        val intent = weatherToolIntent(request) ?: return null
        return execute(intent, automaticSummary = false)
    }

    suspend fun requestAutomaticSummary(city: String): AgentToolCall = execute(
        intent = WeatherToolIntent(
            toolName = "get_weather_summary",
            arguments = mapOf("city" to city),
        ),
        automaticSummary = true,
    )

    suspend fun restoreMonitoring(): WeatherMonitoring? {
        execute(
            intent = WeatherToolIntent(
                toolName = "get_active_weather_collection",
                arguments = emptyMap(),
            ),
            automaticSummary = false,
        )
        return mutableMonitoring.value
    }

    private suspend fun execute(
        intent: WeatherToolIntent,
        automaticSummary: Boolean,
    ): AgentToolCall {
        require(serverUrl.isNotBlank()) { "Не задан URL MCP-сервера" }

        val httpClient = createMcpHttpClient()
        val client = Client(
            clientInfo = Implementation(
                name = "talkloop-agent",
                version = "1.0.0",
            ),
        )

        try {
            client.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = serverUrl.trim(),
                    requestBuilder = {
                        // Сервер обязан видеть поддержку обоих форматов, но для POST
                        // предпочитаем конечный JSON-ответ. Иначе Android OkHttp ждёт
                        // закрытия inline SSE-потока и падает по socket timeout.
                        if (method == HttpMethod.Post) {
                            headers.remove(HttpHeaders.Accept)
                            headers.append(
                                HttpHeaders.Accept,
                                "application/json, text/event-stream;q=0.1",
                            )
                        }
                    },
                )
            )

            // Агент получает каталог инструментов и выбирает погодный инструмент.
            val tool = client.listTools().tools.firstOrNull { it.name == intent.toolName }
                ?: error("MCP-сервер не зарегистрировал инструмент ${intent.toolName}")
            val arguments = intent.arguments
            val result = client.callTool(tool.name, arguments)
            val resultText = result.structuredContent?.let(JSON::encodeToString)
                ?: result.content.filterIsInstance<TextContent>()
                    .joinToString("\n", transform = TextContent::text)
                    .ifBlank { "MCP-инструмент вернул пустой результат" }

            if (result.isError == true) error(resultText)

            val structured = result.structuredContent
            val directResponse = structured?.let { content ->
                when (intent.toolName) {
                    "run_weather_report_pipeline" -> formatPipelineResponse(content)
                    "schedule_weather_collection" -> formatScheduleResponse(content)
                    "get_weather_summary" -> formatSummaryResponse(content, automaticSummary)
                    "cancel_weather_collection" -> formatCancelResponse(content)
                    else -> null
                }
            }

            when (intent.toolName) {
                "schedule_weather_collection" -> structured?.let { content ->
                    val city = content.string("city")
                    val interval = content.integer("interval_minutes")
                    if (city.isNotBlank() && interval != null) {
                        mutableMonitoring.value = WeatherMonitoring(city, interval)
                    }
                }
                "cancel_weather_collection" -> {
                    val cancelledCity = structured?.string("city").orEmpty()
                    if (mutableMonitoring.value?.city.equals(cancelledCity, ignoreCase = true)) {
                        mutableMonitoring.value = null
                    }
                }
                "get_active_weather_collection" -> {
                    val active = structured?.get("active")?.jsonPrimitive?.booleanOrNull == true
                    val city = structured?.string("city").orEmpty()
                    val interval = structured?.integer("interval_minutes")
                    mutableMonitoring.value = if (active && city.isNotBlank() && interval != null) {
                        WeatherMonitoring(city, interval)
                    } else {
                        null
                    }
                }
            }

            return AgentToolCall(
                toolName = tool.name,
                toolDescription = tool.description.orEmpty(),
                inputSchema = JSON.encodeToString(tool.inputSchema),
                arguments = JSON.encodeToString(arguments.toJsonObject()),
                result = resultText,
                directResponse = directResponse,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isMcpConnectionFailure()) {
                throw IllegalStateException(
                    "Не удалось подключиться к MCP-серверу $serverUrl. " +
                        "В корне проекта запустите ./gradlew :server:run и оставьте " +
                        "этот терминал открытым. Адрес 10.0.2.2 работает только в Android Emulator.",
                    e,
                )
            }
            throw e
        } finally {
            client.close()
            httpClient.close()
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}

data class WeatherMonitoring(
    val city: String,
    val intervalMinutes: Int,
)

internal fun formatScheduleResponse(result: JsonObject): String = buildString {
    appendLine("Сбор погоды для города ${result.string("city")} запущен.")
    appendLine("Частота: каждые ${result.integer("interval_minutes")} мин.")
    val samples = result.integer("samples")
    appendLine("Счётчик нового периода: ${samples ?: 0} замер.")
    val latest = result["latest_weather"]?.jsonObject
    if (latest != null) {
        appendLine(
            "Сейчас: ${latest.string("temperature_c")} °C, " +
                "ощущается как ${latest.string("feels_like_c")} °C, " +
                "${latest.string("condition")}."
        )
        appendLine(
            "Влажность ${latest.string("humidity_percent")}% · " +
                "ветер ${latest.string("wind_speed_kmh")} км/ч."
        )
    } else {
        appendLine("Первый замер пока не получен; сервер повторит попытку.")
    }
    append("Следующий сбор: ${result.string("next_run_at")}")
}

internal fun formatSummaryResponse(result: JsonObject, automatic: Boolean): String = buildString {
    appendLine(
        if (automatic) "Автоматическая сводка погоды — ${result.string("city")}."
        else "Сводка погоды — ${result.string("city")}."
    )
    appendLine("Частота сбора: каждые ${result.integer("interval_minutes")} мин.")
    appendLine("Замеров в текущем периоде: ${result.integer("samples")}.")
    appendLine(
        "Период данных: ${result.string("period_started_at")} — " +
            result.string("period_ended_at") + "."
    )
    appendLine(
        "Температура: минимум ${result.string("minimum_temperature_c")} °C, " +
            "средняя ${result.string("average_temperature_c")} °C, " +
            "максимум ${result.string("maximum_temperature_c")} °C."
    )
    val latest = result["latest"]?.jsonObject
    if (latest != null) {
        append(
            "Последний замер: ${latest.string("temperature_c")} °C, " +
                "${latest.string("condition")}"
        )
    }
}

internal fun formatCancelResponse(result: JsonObject): String =
    "Сбор погоды для города ${result.string("city")} остановлен. " +
        "Новый запуск создаст новый период и начнёт счётчик заново."

internal fun formatPipelineResponse(result: JsonObject): String = buildString {
    appendLine("Погодный отчёт для города ${result.string("city")} готов.")
    val report = result.string("report_markdown")
        .lineSequence()
        .map { line ->
            when {
                line.startsWith("# ") -> line.removePrefix("# ")
                line.startsWith("- ") -> "• ${line.removePrefix("- ")}"
                else -> line
            }
        }
        .joinToString("\n")
        .trim()
    if (report.isNotEmpty()) {
        appendLine()
        appendLine("Результат:")
        appendLine(report)
        appendLine()
    }
    appendLine("Выполнена цепочка:")
    appendLine("1. search_weather_data — данные получены")
    appendLine("2. summarize_weather_data — Markdown сформирован")
    appendLine("3. save_weather_report — файл сохранён")
    append("Файл: ${result.string("file_path")}")
}

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.integer(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

/** Не просим kotlinx.serialization искать сериализатор для Any на Android/iOS. */
internal fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(
    mapValues { (_, value) ->
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
)

internal data class WeatherToolIntent(
    val toolName: String,
    val arguments: Map<String, Any?>,
)

internal fun weatherToolIntent(request: String): WeatherToolIntent? {
    val trimmed = request.trim()
    val normalized = trimmed.lowercase()
    if (!isWeatherRequest(trimmed)) return null

    if (
        normalized.startsWith("/weather-report") ||
        (("отчёт" in normalized || "отчет" in normalized) &&
            listOf("создай", "сделай", "сформируй", "сохрани").any(normalized::contains))
    ) {
        val city = extractWeatherReportCity(trimmed)
        require(city.length >= 2) {
            "Укажите город, например: /weather-report Екатеринбург"
        }
        return WeatherToolIntent(
            toolName = "run_weather_report_pipeline",
            arguments = mapOf("city" to city),
        )
    }

    if (
        normalized.startsWith("/weather-stop") ||
        (("останов" in normalized || "отмен" in normalized) &&
            listOf("сбор", "монитор", "отслеж").any(normalized::contains))
    ) {
        val city = extractToolCity(trimmed, "/weather-stop")
        return WeatherToolIntent(
            toolName = "cancel_weather_collection",
            arguments = city.takeIf { it.length >= 2 }?.let { mapOf("city" to it) }.orEmpty(),
        )
    }

    if (
        normalized.startsWith("/weather-summary") ||
        ("сводк" in normalized && ("погод" in normalized || "weather" in normalized))
    ) {
        val city = extractToolCity(trimmed, "/weather-summary")
        return WeatherToolIntent(
            toolName = "get_weather_summary",
            arguments = city.takeIf { it.length >= 2 }?.let { mapOf("city" to it) }.orEmpty(),
        )
    }

    if (
        normalized.startsWith("/weather-watch") ||
        listOf("собирай", "собирать", "отслеживай", "регуляр", "периодичес", "кажд", "every")
            .any(normalized::contains)
    ) {
        val city = extractScheduledCity(trimmed)
        require(city.length >= 2) {
            "Укажите город, например: /weather-watch Екатеринбург 5"
        }
        return WeatherToolIntent(
            toolName = "schedule_weather_collection",
            arguments = mapOf(
                "city" to city,
                "interval_minutes" to extractIntervalMinutes(trimmed),
            ),
        )
    }

    val city = extractWeatherCity(trimmed)
    require(city.length >= 2) {
        "Укажите город, например: /weather Екатеринбург"
    }
    return WeatherToolIntent(
        toolName = "get_current_weather",
        arguments = mapOf("city" to city),
    )
}

private fun extractWeatherReportCity(request: String): String {
    val trimmed = request.trim()
    if (trimmed.lowercase().startsWith("/weather-report")) {
        return cleanCity(trimmed.drop("/weather-report".length))
    }
    val fromWeatherRequest = extractWeatherCity(trimmed)
    if (fromWeatherRequest.length >= 2) return fromWeatherRequest
    val normalized = trimmed.lowercase()
    val marker = " для "
    val markerIndex = normalized.lastIndexOf(marker)
    return if (markerIndex >= 0) {
        cleanCity(trimmed.substring(markerIndex + marker.length))
    } else {
        ""
    }
}

private fun Exception.isMcpConnectionFailure(): Boolean {
    val details = generateSequence<Throwable>(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
        .lowercase()
    return listOf(
        "timeout",
        "timed out",
        "failed to connect",
        "connection refused",
        "connectexception",
        "network is unreachable",
    ).any(details::contains)
}

internal fun isWeatherRequest(request: String): Boolean {
    val normalized = request.trim().lowercase()
    return normalized.startsWith("/weather") ||
        "погод" in normalized ||
        "температур" in normalized ||
        "weather" in normalized ||
        ("сводк" in normalized && listOf("сбор", "замер", "монитор").any(normalized::contains))
}

internal fun extractWeatherCity(request: String): String {
    val trimmed = request.trim()
    if (trimmed.lowercase().startsWith("/weather")) {
        return cleanCity(trimmed.drop("/weather".length))
    }

    val normalized = trimmed.lowercase()
    val locationMarkers = listOf(" в городе ", " для города ", " in ", " for ", " в ")
    locationMarkers.forEach { marker ->
        val markerIndex = normalized.lastIndexOf(marker)
        if (markerIndex >= 0) {
            return cleanCity(trimmed.substring(markerIndex + marker.length))
        }
    }

    val intentWords = listOf("погода", "погоду", "температура", "температуру", "weather")
    intentWords.forEach { word ->
        val wordIndex = normalized.indexOf(word)
        if (wordIndex >= 0) {
            return cleanCity(trimmed.substring(wordIndex + word.length))
        }
    }
    return ""
}

private fun extractScheduledCity(request: String): String {
    val fromCommand = request.trim().takeIf {
        it.lowercase().startsWith("/weather-watch")
    }?.drop("/weather-watch".length)
        ?.replace(Regex("""\s+\d+\s*(мин\p{L}*|minute\p{L}*|час\p{L}*|hour\p{L}*)?.*$""", RegexOption.IGNORE_CASE), "")
    val extracted = fromCommand ?: extractWeatherCity(request)
    return cleanCity(
        extracted.replace(
            Regex("""\s+(кажд\p{L}*|every|раз\s+в)\s+.*$""", RegexOption.IGNORE_CASE),
            "",
        )
    )
}

private fun extractToolCity(request: String, command: String): String {
    val trimmed = request.trim()
    if (trimmed.lowercase().startsWith(command)) {
        return cleanCity(trimmed.drop(command.length))
    }
    val normalized = trimmed.lowercase()
    val markers = listOf(" для города ", " в городе ", " по городу ", " для ", " по ", " в ")
    markers.forEach { marker ->
        val index = normalized.lastIndexOf(marker)
        if (index >= 0) return cleanCity(trimmed.substring(index + marker.length))
    }
    return ""
}

internal fun extractIntervalMinutes(request: String): Int {
    val normalized = request.lowercase()
    val amountAndUnit = Regex(
        """(\d+)\s*(мин\p{L}*|minute\p{L}*|час\p{L}*|hour\p{L}*)""",
        RegexOption.IGNORE_CASE,
    ).find(normalized)
    if (amountAndUnit != null) {
        val amount = amountAndUnit.groupValues[1].toInt()
        val unit = amountAndUnit.groupValues[2]
        return if (unit.startsWith("час") || unit.startsWith("hour")) amount * 60 else amount
    }
    if ("каждый час" in normalized || "каждые час" in normalized || "hourly" in normalized) {
        return 60
    }
    if (normalized.startsWith("/weather-watch")) {
        Regex("""\b(\d+)\b""").find(normalized.drop("/weather-watch".length))
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return 60
}

private fun cleanCity(value: String): String = value
    .trim()
    .trim('?', '!', '.', ',', ':', ';', '«', '»', '\'', '"')
    .removePrefix("сейчас ")
    .removePrefix("сегодня ")
    .removePrefix("now ")
    .removePrefix("today ")
    .trim()
    .trim('?', '!', '.', ',', ':', ';', '«', '»', '\'', '"')

internal fun defaultMcpServerUrl(): String =
    if (getPlatform().name.startsWith("Android")) {
        "http://10.0.2.2:8080/mcp"
    } else {
        "http://127.0.0.1:8080/mcp"
    }
