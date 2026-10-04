package com.mau89.talkloop

import com.mau89.talkloop.llm.DocumentIndexCatalog
import com.mau89.talkloop.llm.DocumentSearchRequest
import com.mau89.talkloop.llm.DocumentSearchResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface DocumentIndexGateway {
    suspend fun catalog(): DocumentIndexCatalog
    suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse
}

class DocumentIndexUnavailable(message: String) : RuntimeException(message)

/** One persistent, offline CPU worker, owned and stopped by the Ktor application. */
class LocalDocumentIndexGateway(
    private val projectRoot: Path = Paths.get(System.getProperty("talkloop.project.root", "."))
        .toAbsolutePath().normalize(),
    private val dataDirectory: Path = Paths.get(
        System.getProperty("talkloop.index.directory", projectRoot.resolve("day21-data").toString())
    ).toAbsolutePath().normalize(),
) : DocumentIndexGateway, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val readerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "document-index-reader").apply { isDaemon = true }
    }
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun catalog(): DocumentIndexCatalog =
        json.decodeFromJsonElement(DocumentIndexCatalog.serializer(), exchange(
            JsonObject(mapOf("operation" to JsonPrimitive("status")))
        ))

    override suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse {
        request.validate()
        val payload = json.encodeToJsonElement(DocumentSearchRequest.serializer(), request).jsonObject
        return json.decodeFromJsonElement(DocumentSearchResponse.serializer(), exchange(
            JsonObject(payload + ("operation" to JsonPrimitive("search")))
        ))
    }

    private suspend fun exchange(payload: JsonObject): JsonElement = lock.withLock {
        withContext(Dispatchers.IO) {
            if (closed) throw DocumentIndexUnavailable("Сервер поиска остановлен.")
            try {
                if (process?.isAlive != true) startWorker()
                writer!!.apply {
                    write(payload.toString())
                    newLine()
                    flush()
                }
                val currentReader = reader!!
                val line = CompletableFuture.supplyAsync({ currentReader.readLine() }, readerExecutor)
                    .get(60, TimeUnit.SECONDS)
                    ?: throw DocumentIndexUnavailable("Индекс не загрузился. Проверьте day21-data/worker.log.")
                val reply = json.parseToJsonElement(line).jsonObject
                if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    throw IllegalArgumentException(reply["error"]?.jsonPrimitive?.content ?: "Неверный запрос поиска.")
                }
                reply.getValue("data")
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                stopWorker()
                throw DocumentIndexUnavailable(
                    "Локальный индекс недоступен. В корне TalkLoop выполните " +
                        "tools/day21/run.sh prepare и tools/day21/run.sh build. " +
                        "После подготовки перезапустите сервер. Подробности: day21-data/worker.log."
                )
            }
        }
    }

    private fun startWorker() {
        val python = projectRoot.resolve(".day21-venv/bin/python")
        val script = projectRoot.resolve("tools/day21/index_documents.py")
        if (!Files.isExecutable(python) || !Files.isRegularFile(script)) {
            throw DocumentIndexUnavailable("Не найдено Python-окружение Дня 21.")
        }
        Files.createDirectories(dataDirectory)
        val worker = ProcessBuilder(
            python.toString(), "-u", script.toString(), "--data-dir", dataDirectory.toString(), "worker",
        ).directory(projectRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(dataDirectory.resolve("worker.log").toFile()))
            .start()
        process = worker
        reader = worker.inputStream.bufferedReader(Charsets.UTF_8)
        writer = worker.outputStream.bufferedWriter(Charsets.UTF_8)
    }

    private fun stopWorker() {
        process?.destroyForcibly()
        process = null
        reader = null
        writer = null
    }

    override fun close() {
        closed = true
        stopWorker()
        readerExecutor.shutdownNow()
    }
}

fun Route.documentIndexRoutes(gateway: DocumentIndexGateway) {
    val json = Json { ignoreUnknownKeys = true }
    get("/api/documents/status") {
        try {
            call.respondText(json.encodeToString(DocumentIndexCatalog.serializer(), gateway.catalog()), ContentType.Application.Json)
        } catch (error: DocumentIndexUnavailable) {
            call.respondText(
                JsonObject(mapOf("error" to JsonPrimitive(error.message))).toString(),
                ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
            )
        }
    }
    post("/api/documents/search") {
        try {
            val body = call.receiveText()
            require(body.length <= 16_000) { "Запрос слишком большой." }
            val request = json.decodeFromString(DocumentSearchRequest.serializer(), body)
            request.validate()
            val result = gateway.search(request)
            call.respondText(json.encodeToString(DocumentSearchResponse.serializer(), result), ContentType.Application.Json)
        } catch (error: SerializationException) {
            call.respondText("{\"error\":\"Неверный формат запроса.\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
        } catch (error: IllegalArgumentException) {
            call.respondText(
                JsonObject(mapOf("error" to JsonPrimitive(error.message))).toString(),
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
        } catch (error: DocumentIndexUnavailable) {
            call.respondText(
                JsonObject(mapOf("error" to JsonPrimitive(error.message))).toString(),
                ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
