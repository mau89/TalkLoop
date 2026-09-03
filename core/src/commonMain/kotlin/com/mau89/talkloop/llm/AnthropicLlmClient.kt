package com.mau89.talkloop.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
        val response: MessagesResponse = try {
            http.post(ENDPOINT) {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    MessagesRequest(
                        model = model,
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
        )
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
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
)
