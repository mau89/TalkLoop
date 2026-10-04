package com.mau89.talkloop

import com.mau89.talkloop.llm.DocumentIndexCatalog
import com.mau89.talkloop.llm.DocumentSearchRequest
import com.mau89.talkloop.llm.DocumentSearchResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class DocumentIndexClient {
    private val http = createMcpHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun catalog(address: String): DocumentIndexCatalog = network {
        json.decodeFromString(DocumentIndexCatalog.serializer(), checked(http.get(endpoint(address, "status"))))
    }

    suspend fun search(address: String, request: DocumentSearchRequest): DocumentSearchResponse = network {
        request.validate()
        val response = http.post(endpoint(address, "search")) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
        }
        json.decodeFromString(DocumentSearchResponse.serializer(), checked(response))
    }

    private fun endpoint(address: String, operation: String): String {
        val base = address.trim().trimEnd('/')
        val url = Url(base)
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank()) {
            "Укажите адрес сервера, например http://10.0.2.2:8080."
        }
        return "$base/api/documents/$operation"
    }

    private suspend fun checked(response: HttpResponse): String {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            throw IllegalArgumentException(message ?: "Сервер поиска вернул ошибку ${response.status.value}.")
        }
        return body
    }

    private suspend fun <T> network(block: suspend () -> T): T = try {
        withTimeout(65_000L) { block() }
    } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("Сервер поиска не ответил вовремя. Проверьте его состояние и повторите запрос.", error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalStateException(
            "Не удалось связаться с локальным сервером. Запустите сервер TalkLoop и проверьте адрес. " +
                "На телефоне укажите IP компьютера в той же сети.", error,
        )
    }

    fun close() = http.close()
}

internal fun defaultDocumentServerAddress(): String =
    if (getPlatform().name.startsWith("Android")) "http://10.0.2.2:8080" else "http://127.0.0.1:8080"
