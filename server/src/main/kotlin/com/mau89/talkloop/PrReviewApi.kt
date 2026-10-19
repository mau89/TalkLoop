package com.mau89.talkloop

import com.mau89.talkloop.llm.PrReviewJob
import com.mau89.talkloop.llm.PrReviewRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface PrReviewGateway {
    suspend fun start(request: PrReviewRequest): PrReviewJob
    suspend fun latest(): PrReviewJob?
    suspend fun get(id: String): PrReviewJob?
}

class PrReviewBusy : RuntimeException("На Mac уже выполняется другая проверка. Дождитесь её завершения.")
class PrReviewUnavailable : RuntimeException("Не удалось запустить ревью. Проверьте Python 3.9+ и tools/day32/app_review.py на Mac.")

/** The application owns the process; leaving a mobile screen never cancels a review. */
class LocalPrReviewGateway(
    private val root: Path = Paths.get(System.getProperty("talkloop.project.root", ".")).toAbsolutePath().normalize(),
    private val directory: Path = root.resolve("day32-data/app"),
    private val python: String = System.getProperty("talkloop.review.python", "/usr/bin/python3"),
    private val timeoutSeconds: Long = 20 * 60,
) : PrReviewGateway, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pr-review-timeout").apply { isDaemon = true }
    }
    private var process: Process? = null
    private var activeId: String? = null
    private var closed = false

    override suspend fun start(request: PrReviewRequest): PrReviewJob = withContext(Dispatchers.IO) {
        request.validate()
        synchronized(lock) {
            if (closed) throw PrReviewUnavailable()
            if (process?.isAlive == true) {
                val existing = read(activeId!!)
                if (existing?.prNumber == request.prNumber) return@synchronized existing
                throw PrReviewBusy()
            }
            try {
                val id = UUID.randomUUID().toString()
                val output = directory.resolve(id)
                Files.createDirectories(output)
                val job = PrReviewJob(id, request.prNumber)
                save(job)
                Files.writeString(directory.resolve("latest.txt"), id)
                val child = ProcessBuilder(python, root.resolve("tools/day32/app_review.py").toString(),
                    "--pr", request.prNumber.toString(), "--job-id", id,
                    "--output", output.toString(), "--cache", directory.resolve("git-cache.git").toString())
                    .directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.resolve("worker.log").toFile()).start()
                process = child
                activeId = id
                val timeout = timer.schedule({
                    synchronized(lock) {
                        if (child.isAlive) {
                            terminate(child)
                            fail(id, "Проверка превысила 20 минут. Убедитесь, что Ollama доступна, и повторите запуск.")
                        }
                    }
                }, timeoutSeconds, TimeUnit.SECONDS)
                child.onExit().thenRun {
                    synchronized(lock) {
                        timeout.cancel(false)
                        if (read(id)?.isRunning == true) {
                            fail(id, "Процесс ревью завершился без результата. Проверьте day32-data/app/$id/worker.log на Mac.")
                        }
                    }
                }
                job
            } catch (error: Exception) {
                latestId()?.let { fail(it, PrReviewUnavailable().message!!) }
                throw PrReviewUnavailable()
            }
        }
    }

    override suspend fun latest(): PrReviewJob? = withContext(Dispatchers.IO) {
        synchronized(lock) { latestId()?.let { load(it) } }
    }

    override suspend fun get(id: String): PrReviewJob? = withContext(Dispatchers.IO) {
        synchronized(lock) { load(id) }
    }

    private fun latestId(): String? = directory.resolve("latest.txt").let {
        if (Files.isRegularFile(it)) Files.readString(it).trim() else null
    }

    private fun read(id: String): PrReviewJob? {
        if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return null
        val file = directory.resolve(id).resolve("job.json")
        return if (Files.isRegularFile(file)) json.decodeFromString(PrReviewJob.serializer(), Files.readString(file)) else null
    }

    private fun load(id: String): PrReviewJob? {
        val job = read(id) ?: return null
        if (job.isRunning && (id != activeId || process?.isAlive != true)) {
            return fail(id, "Сервер был перезапущен: проверка прервана. Нажмите «Проверить PR» ещё раз.")
        }
        return job
    }

    private fun save(job: PrReviewJob) {
        val output = directory.resolve(job.id)
        val temporary = output.resolve("job.server.tmp")
        Files.writeString(temporary, json.encodeToString(PrReviewJob.serializer(), job))
        Files.move(temporary, output.resolve("job.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fail(id: String, message: String): PrReviewJob? = read(id)?.copy(state = "error", message = message)?.also(::save)

    private fun terminate(child: Process) {
        child.descendants().forEach { it.destroyForcibly() }
        child.destroyForcibly()
    }

    override fun close() = synchronized(lock) {
        closed = true
        process?.takeIf { it.isAlive }?.let { child ->
            terminate(child)
            activeId?.let { fail(it, "Локальный сервер остановлен. Повторите проверку после запуска сервера.") }
        }
        timer.shutdownNow()
        Unit
    }
}

fun Route.prReviewRoutes(gateway: PrReviewGateway) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun io.ktor.server.application.ApplicationCall.respondJob(job: PrReviewJob?, status: HttpStatusCode = HttpStatusCode.OK) {
        if (job == null) respondText("{\"error\":\"Проверка не найдена.\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
        else respondText(json.encodeToString(PrReviewJob.serializer(), job), ContentType.Application.Json, status)
    }
    get("/api/reviews/latest") { call.respondJob(gateway.latest()) }
    get("/api/reviews/{id}") { call.respondJob(gateway.get(call.parameters["id"].orEmpty())) }
    post("/api/reviews") {
        try {
            require(call.request.headers["Origin"] == null && call.request.contentType().match(ContentType.Application.Json)) {
                "Запускайте проверку из приложения TalkLoop с JSON-запросом."
            }
            val body = call.receiveText()
            require(body.length <= 1024) { "Запрос слишком большой." }
            val request = json.decodeFromString(PrReviewRequest.serializer(), body)
            request.validate()
            call.respondJob(gateway.start(request), HttpStatusCode.Accepted)
        } catch (error: Exception) {
            val status = when (error) {
                is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest
                is PrReviewBusy -> HttpStatusCode.Conflict
                is PrReviewUnavailable -> HttpStatusCode.ServiceUnavailable
                else -> throw error
            }
            val message = if (error is SerializationException) "Неверный формат запроса." else error.message.orEmpty()
            call.respondText(JsonObject(mapOf("error" to JsonPrimitive(message))).toString(), ContentType.Application.Json, status)
        }
    }
}
