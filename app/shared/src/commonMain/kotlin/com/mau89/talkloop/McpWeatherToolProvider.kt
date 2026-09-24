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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** MCP-инструмент обычного TalkLoopAgent, а не отдельный экран или отдельный агент. */
class McpWeatherToolProvider(
    private val serverUrl: String = defaultMcpServerUrl(),
) : AgentToolProvider {
    override suspend fun callFor(request: String): AgentToolCall? {
        if (!isWeatherRequest(request)) return null

        val city = extractWeatherCity(request)
        require(city.length >= 2) {
            "Укажите город, например: /weather Екатеринбург"
        }
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
            val tool = client.listTools().tools.firstOrNull { it.name == TOOL_NAME }
                ?: error("MCP-сервер не зарегистрировал инструмент $TOOL_NAME")
            val arguments = mapOf("city" to city)
            val result = client.callTool(tool.name, arguments)
            val resultText = result.structuredContent?.let(JSON::encodeToString)
                ?: result.content.filterIsInstance<TextContent>()
                    .joinToString("\n", transform = TextContent::text)
                    .ifBlank { "MCP-инструмент вернул пустой результат" }

            if (result.isError == true) error(resultText)

            return AgentToolCall(
                toolName = tool.name,
                toolDescription = tool.description.orEmpty(),
                inputSchema = JSON.encodeToString(tool.inputSchema),
                arguments = JSON.encodeToString(arguments),
                result = resultText,
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
        const val TOOL_NAME = "get_current_weather"
        val JSON = Json { prettyPrint = true }
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
        "weather" in normalized
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
