package com.mau89.talkloop.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic Messages API поверх ktor-client: официального KMP-SDK нет,
 * поэтому здесь честный HTTP. JVM-CLI живёт на официальном Java-SDK.
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = TUTOR_SYSTEM_PROMPT,
) : LlmClient {

    private val http = HttpClient {
        expectSuccess = true
        // Opus с adaptive thinking легко сидит дольше дефолтных ~10 с OkHttp.
        // Без этого лаборатория моделей обрывается на сильной модели.
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    // Иначе в запрос уедут "stop_sequences": null и "output_config": null,
                    // а API на такое отвечает 400.
                    explicitNulls = false
                }
            )
        }
    }

    override suspend fun reply(history: List<ChatMessage>): String =
        answer(history, ResponseSpec(system = systemPrompt)).text

    override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
        val requestModel = spec.model ?: model
        val response: MessagesResponse = try {
            http.post(ENDPOINT) {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    MessagesRequest(
                        model = requestModel,
                        maxTokens = spec.maxTokens,
                        system = spec.system,
                        messages = history.map { message ->
                            ApiMessage(
                                role = if (message.fromUser) "user" else "assistant",
                                content = message.text,
                            )
                        },
                        stopSequences = spec.stopSequences.ifEmpty { null },
                        temperature = spec.temperature,
                        outputConfig = spec.jsonSchema?.let { schema ->
                            OutputConfig(FormatSpec(type = "json_schema", schema = schema))
                        },
                    )
                )
            }.body()
        } catch (e: ResponseException) {
            throw LlmException("${e.response.status.value}: ${e.response.bodyAsText()}", e)
        } catch (e: HttpRequestTimeoutException) {
            throw LlmException(
                "Модель $requestModel не успела ответить за ${REQUEST_TIMEOUT_MS / 1000} с — " +
                    "сильные модели с thinking часто дольше. Повторите прогон.",
                e,
            )
        } catch (e: SocketTimeoutException) {
            throw LlmException(
                "Соединение с $requestModel оборвалось по таймауту. " +
                    "Сильная модель думает дольше — повторите прогон.",
                e,
            )
        }

        return LlmAnswer(
            text = response.content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")
                .trim(),
            stopReason = response.stopReason,
            stopSequence = response.stopSequence,
            inputTokens = response.usage?.inputTokens ?: 0,
            outputTokens = response.usage?.outputTokens ?: 0,
            cacheCreationInputTokens = response.usage?.cacheCreationInputTokens ?: 0,
            cacheReadInputTokens = response.usage?.cacheReadInputTokens ?: 0,
            cacheCreation5mInputTokens =
                response.usage?.cacheCreation?.ephemeral5mInputTokens ?: 0,
            cacheCreation1hInputTokens =
                response.usage?.cacheCreation?.ephemeral1hInputTokens ?: 0,
        )
    }

    override suspend fun countInputTokens(
        history: List<ChatMessage>,
        spec: ResponseSpec,
    ): Int {
        val requestModel = spec.model ?: model
        return try {
            http.post("$ENDPOINT/count_tokens") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    TokenCountRequest(
                        model = requestModel,
                        system = spec.system,
                        messages = history.map { message ->
                            ApiMessage(
                                role = if (message.fromUser) "user" else "assistant",
                                content = message.text,
                            )
                        },
                    )
                )
            }.body<TokenCountResponse>().inputTokens
        } catch (e: ResponseException) {
            throw LlmException(
                "Не удалось посчитать токены (${e.response.status.value}): " +
                    e.response.bodyAsText(),
                e,
            )
        } catch (e: HttpRequestTimeoutException) {
            throw LlmException("Подсчёт токенов не успел завершиться", e)
        } catch (e: SocketTimeoutException) {
            throw LlmException("Соединение оборвалось во время подсчёта токенов", e)
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** Opus с adaptive thinking легко занимает минуты — дефолт OkHttp ~10 с мало. */
        const val REQUEST_TIMEOUT_MS = 300_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<ApiMessage>,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val temperature: Double? = null,
    @SerialName("output_config") val outputConfig: OutputConfig? = null,
)

@Serializable
private data class TokenCountRequest(
    val model: String,
    val system: String? = null,
    val messages: List<ApiMessage>,
)

@Serializable
private data class TokenCountResponse(
    @SerialName("input_tokens") val inputTokens: Int,
)

@Serializable
private data class ApiMessage(val role: String, val content: String)

/** Structured outputs: схему ответа проверяет уже сам API, а не промпт. */
@Serializable
private data class OutputConfig(val format: FormatSpec)

@Serializable
private data class FormatSpec(val type: String, val schema: JsonObject)

@Serializable
private data class MessagesResponse(
    val content: List<ContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: ApiUsage? = null,
)

@Serializable
private data class ContentBlock(val type: String, val text: String? = null)

@Serializable
private data class ApiUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation") val cacheCreation: CacheCreationUsage? = null,
)

@Serializable
private data class CacheCreationUsage(
    @SerialName("ephemeral_5m_input_tokens") val ephemeral5mInputTokens: Int = 0,
    @SerialName("ephemeral_1h_input_tokens") val ephemeral1hInputTokens: Int = 0,
)
