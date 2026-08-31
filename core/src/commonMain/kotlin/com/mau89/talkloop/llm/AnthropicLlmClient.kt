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
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun reply(history: List<ChatMessage>): String {
        val response: MessagesResponse = try {
            http.post(ENDPOINT) {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    MessagesRequest(
                        model = model,
                        maxTokens = DEFAULT_MAX_TOKENS,
                        system = systemPrompt,
                        messages = history.map { message ->
                            ApiMessage(
                                role = if (message.fromLearner) "user" else "assistant",
                                content = message.text,
                            )
                        },
                    )
                )
            }.body()
        } catch (e: ResponseException) {
            throw LlmException("${e.response.status.value}: ${e.response.bodyAsText()}", e)
        }

        return response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .trim()
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
    val system: String,
    val messages: List<ApiMessage>,
)

@Serializable
private data class ApiMessage(val role: String, val content: String)

@Serializable
private data class MessagesResponse(val content: List<ContentBlock>)

@Serializable
private data class ContentBlock(val type: String, val text: String? = null)
