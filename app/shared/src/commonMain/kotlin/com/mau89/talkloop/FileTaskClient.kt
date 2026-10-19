package com.mau89.talkloop

import com.mau89.talkloop.llm.FileTask
import com.mau89.talkloop.llm.FileTaskRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class FileTaskClient {
    private val baseHttp = createMcpHttpClient()
    private val http = baseHttp.config { install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 25_000; socketTimeoutMillis = 25_000 } }
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun latest(address: String): FileTask? = network {
        val response = http.get(endpoint(address) + "/latest")
        if (response.status.value == 404) null else decode(response)
    }
    suspend fun get(address: String, id: String): FileTask = network { decode(http.get(endpoint(address) + "/$id")) }
    suspend fun start(address: String, goal: String): FileTask = network {
        val request = FileTaskRequest(goal); request.validate()
        decode(http.post(endpoint(address)) { contentType(ContentType.Application.Json); setBody(json.encodeToString(FileTaskRequest.serializer(), request)) })
    }
    suspend fun apply(address: String, id: String): FileTask = network {
        decode(http.post(endpoint(address) + "/$id/apply") { contentType(ContentType.Application.Json); setBody("{}") })
    }
    private fun endpoint(address: String): String {
        val url = Url(address.trim())
        require(url.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && url.host.isNotBlank() &&
            url.encodedPath in listOf("", "/") && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Укажите адрес локального сервера TalkLoop." }
        return address.trim().trimEnd('/') + "/api/file-tasks"
    }
    private suspend fun decode(response: HttpResponse): FileTask {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalArgumentException(message ?: "Сервис файлов вернул ошибку ${response.status.value}.")
        }
        return json.decodeFromString(FileTask.serializer(), body)
    }
    private suspend fun <T> network(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: SerializationException) { throw IllegalStateException("Обновите приложение и сервер: формат файловой задачи не совпадает.", error) }
    catch (error: IllegalArgumentException) { throw error }
    catch (error: Exception) { throw IllegalStateException("Нет ответа сервера TalkLoop. Проверьте адрес и запустите сервер на Mac.", error) }
    fun close() { http.close(); baseHttp.close() }
}
