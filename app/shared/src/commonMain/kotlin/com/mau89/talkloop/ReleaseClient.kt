package com.mau89.talkloop

import com.mau89.talkloop.llm.ReleaseJob
import com.mau89.talkloop.llm.ReleaseRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal class ReleaseClient {
    private val http = createMcpHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun start(address: String, request: ReleaseRequest): ReleaseJob = network {
        request.validate()
        decode(http.post(endpoint(address)) { contentType(ContentType.Application.Json); setBody(json.encodeToString(ReleaseRequest.serializer(), request)) })
    }
    suspend fun latest(address: String): ReleaseJob? = network {
        val response = http.get(endpoint(address) + "/latest")
        if (response.status.value == 404) null else decode(response)
    }
    suspend fun get(address: String, id: String): ReleaseJob = network { decode(http.get(endpoint(address) + "/$id")) }
    fun downloadUrl(address: String, id: String, name: String): String {
        require(id.matches(Regex("[0-9a-f-]{36}")) && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,100}"))) { "Некорректная ссылка на файл релиза." }
        return endpoint(address) + "/$id/artifacts/$name"
    }
    private fun endpoint(address: String): String {
        val url = Url(address.trim())
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
            url.encodedPath in listOf("", "/") && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Укажите адрес сервера TalkLoop на Mac." }
        return address.trim().trimEnd('/') + "/api/releases"
    }
    private suspend fun decode(response: HttpResponse): ReleaseJob {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalArgumentException(message ?: "Сервис релизов вернул ошибку ${response.status.value}.")
        }
        return json.decodeFromString(ReleaseJob.serializer(), body)
    }
    private suspend fun <T> network(block: suspend () -> T): T = try { withTimeout(20_000) { block() } }
    catch (error: TimeoutCancellationException) { throw IllegalStateException("Сервер не ответил вовремя. Нажмите «Обновить результат».", error) }
    catch (error: CancellationException) { throw error }
    catch (error: IllegalArgumentException) { throw error }
    catch (error: Exception) { throw IllegalStateException("Нет связи с Mac. Запустите сервер TalkLoop и проверьте адрес.", error) }
    fun close() = http.close()
}
