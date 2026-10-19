package com.mau89.talkloop

import com.mau89.talkloop.llm.PrReviewJob
import com.mau89.talkloop.llm.PrReviewRequest
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

internal class PrReviewClient {
    private val http = createMcpHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(address: String, number: Int): PrReviewJob = network {
        val request = PrReviewRequest(number)
        request.validate()
        decode(http.post(endpoint(address)) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PrReviewRequest.serializer(), request))
        })
    }

    suspend fun latest(address: String): PrReviewJob? = network {
        val response = http.get(endpoint(address) + "/latest")
        if (response.status.value == 404) null else decode(response)
    }

    suspend fun get(address: String, id: String): PrReviewJob = network {
        decode(http.get(endpoint(address) + "/$id"))
    }

    private fun endpoint(address: String): String {
        val url = Url(address.trim())
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
            url.encodedPath in listOf("", "/") && url.parameters.isEmpty() && url.fragment.isEmpty()) {
            "Укажите адрес сервера, например http://10.0.2.2:8080."
        }
        return address.trim().trimEnd('/') + "/api/reviews"
    }

    private suspend fun decode(response: HttpResponse): PrReviewJob {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalArgumentException(message ?: "Сервер ревью вернул ошибку ${response.status.value}.")
        }
        return json.decodeFromString(PrReviewJob.serializer(), body)
    }

    private suspend fun <T> network(block: suspend () -> T): T = try {
        withTimeout(15_000) { block() }
    } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("Сервер не ответил вовремя. Обновите состояние проверки.", error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalStateException("Нет связи с Mac. Запустите сервер TalkLoop и проверьте адрес. На телефоне укажите IP Mac в той же сети.", error)
    }

    fun close() = http.close()
}
