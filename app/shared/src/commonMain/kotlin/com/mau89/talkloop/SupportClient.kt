package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class SupportClient {
    private val baseHttp = createMcpHttpClient()
    private val http = baseHttp.config {
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 190_000; socketTimeoutMillis = 190_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun catalog(address: String): SupportCatalog = network {
        json.decodeFromString(SupportCatalog.serializer(), checked(http.get(endpoint(address, "catalog"))))
    }
    suspend fun ask(address: String, request: SupportRequest): SupportReply = network {
        request.validate()
        json.decodeFromString(SupportReply.serializer(), checked(http.post(endpoint(address, "ask")) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SupportRequest.serializer(), request))
        }))
    }
    private fun endpoint(address: String, operation: String): String {
        val url = Url(address.trim())
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
            url.encodedPath in listOf("", "/") && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Укажите адрес сервера TalkLoop, например http://10.0.2.2:8080." }
        return address.trim().trimEnd('/') + "/api/support/$operation"
    }
    private suspend fun checked(response: HttpResponse): String {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalArgumentException(message ?: "Сервис поддержки вернул ошибку ${response.status.value}.")
        }
        return body
    }
    private suspend fun <T> network(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: SerializationException) { throw IllegalStateException("Обновите приложение и сервер: формат поддержки не совпадает.", error) }
    catch (error: IllegalArgumentException) { throw error }
    catch (error: Exception) { throw IllegalStateException("Нет ответа сервиса поддержки. Проверьте адрес, сервер TalkLoop и Ollama на Mac.", error) }

    fun close() { http.close(); baseHttp.close() }
}
