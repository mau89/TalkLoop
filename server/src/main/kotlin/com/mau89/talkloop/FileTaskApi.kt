package com.mau89.talkloop

import com.mau89.talkloop.llm.FileTask
import com.mau89.talkloop.llm.FileTaskRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface FileTaskGateway {
    suspend fun start(request: FileTaskRequest): FileTask
    suspend fun latest(): FileTask?
    suspend fun get(id: String): FileTask?
    suspend fun apply(id: String): FileTask
}

class FileTaskBusy : RuntimeException("Файловый ассистент уже выполняет задачу. Дождитесь результата.")
class FileTaskConflict(message: String) : RuntimeException(message)

/** One background worker; reviewed code never executes, writes stay in managed docs. */
class LocalFileTaskGateway(
    private val root: Path = Paths.get(System.getProperty("talkloop.project.root", ".")).toAbsolutePath().normalize(),
    private val directory: Path = root.resolve("day34-data/app"),
    private val python: String = "/usr/bin/python3",
) : FileTaskGateway, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "file-agent-timeout").apply { isDaemon = true } }
    private var process: Process? = null
    private var activeId: String? = null
    private var closed = false

    override suspend fun start(request: FileTaskRequest): FileTask = withContext(Dispatchers.IO) {
        request.validate()
        synchronized(lock) {
            check(!closed) { "Сервис файлов остановлен." }
            if (process?.isAlive == true) throw FileTaskBusy()
            val id = UUID.randomUUID().toString()
            val output = directory.resolve(id)
            Files.createDirectories(output)
            val task = FileTask(id, request.goal.trim())
            save(task)
            Files.writeString(directory.resolve("latest.txt"), id)
            try {
                val child = ProcessBuilder(python, root.resolve("tools/day34/file_agent.py").toString(),
                    "--repo", root.toString(), "--goal", task.goal, "--output", output.toString(), "--job-id", id)
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("worker.log").toFile()).start()
                process = child; activeId = id
                val timeout = timer.schedule({ synchronized(lock) {
                    if (child.isAlive) { terminate(child); fail(id, "Задача превысила 10 минут. Уточните цель и повторите запуск.") }
                } }, 10, TimeUnit.MINUTES)
                child.onExit().thenRun { synchronized(lock) {
                    timeout.cancel(false)
                    if (read(id)?.isRunning == true) fail(id, "Файловый ассистент завершился без результата. Проверьте worker.log на Mac.")
                } }
                task
            } catch (_: Exception) {
                fail(id, "Не удалось запустить файловый инструмент. Проверьте Python и tools/day34/file_agent.py.")!!
            }
        }
    }

    override suspend fun latest(): FileTask? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val marker = directory.resolve("latest.txt")
            if (Files.isRegularFile(marker)) load(Files.readString(marker).trim()) else null
        }
    }
    override suspend fun get(id: String): FileTask? = withContext(Dispatchers.IO) { synchronized(lock) { load(id) } }

    override suspend fun apply(id: String): FileTask = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (process?.isAlive == true) throw FileTaskBusy()
            val task = load(id) ?: throw IllegalArgumentException("Задача не найдена.")
            require(task.state in listOf("prepared", "no_changes", "applied")) { "Сначала дождитесь готового diff." }
            val output = directory.resolve(id)
            val child = ProcessBuilder(python, root.resolve("tools/day34/file_agent.py").toString(),
                "--repo", root.toString(), "--apply-plan", output.resolve("plan.json").toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("apply.log").toFile()).start()
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                terminate(child); throw FileTaskConflict("Сохранение не завершилось вовремя. Обновите состояние задачи.")
            }
            if (child.exitValue() != 0) throw FileTaskConflict("Исходники или документ изменились либо план недоступен. Файлы не перезаписаны. Подготовьте новый diff.")
            read(id)!!
        }
    }

    private fun read(id: String): FileTask? {
        if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return null
        val file = directory.resolve(id).resolve("job.json")
        return if (Files.isRegularFile(file)) json.decodeFromString(FileTask.serializer(), Files.readString(file)) else null
    }
    private fun load(id: String): FileTask? {
        val task = read(id) ?: return null
        return if (task.isRunning && (id != activeId || process?.isAlive != true)) fail(id, "Сервер перезапущен: задача прервана. Запустите цель заново.") else task
    }
    private fun save(task: FileTask) {
        val output = directory.resolve(task.id)
        val temporary = output.resolve("job.server.tmp")
        Files.writeString(temporary, json.encodeToString(FileTask.serializer(), task))
        Files.move(temporary, output.resolve("job.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun fail(id: String, message: String): FileTask? = read(id)?.copy(state = "error", message = message)?.also(::save)
    private fun terminate(child: Process) { child.descendants().forEach { it.destroyForcibly() }; child.destroyForcibly() }
    override fun close() = synchronized(lock) {
        closed = true
        process?.takeIf { it.isAlive }?.let { terminate(it); activeId?.let { id -> fail(id, "Сервер остановлен. Запустите цель заново.") } }
        timer.shutdownNow()
        Unit
    }
}

fun Route.fileTaskRoutes(gateway: FileTaskGateway) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun io.ktor.server.application.ApplicationCall.reply(task: FileTask?, status: HttpStatusCode = HttpStatusCode.OK) {
        if (task == null) respondText("{\"error\":\"Задача не найдена.\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
        else respondText(json.encodeToString(FileTask.serializer(), task), ContentType.Application.Json, status)
    }
    suspend fun io.ktor.server.application.ApplicationCall.mutate(block: suspend () -> FileTask, status: HttpStatusCode) {
        try {
            require(request.headers["Origin"] == null && request.contentType().match(ContentType.Application.Json)) { "Используйте JSON-запрос приложения TalkLoop." }
            reply(block(), status)
        } catch (error: Exception) {
            val code = when (error) {
                is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest
                is FileTaskBusy, is FileTaskConflict -> HttpStatusCode.Conflict
                else -> throw error
            }
            val message = if (error is SerializationException) "Неверный формат цели." else error.message.orEmpty()
            respondText(JsonObject(mapOf("error" to JsonPrimitive(message))).toString(), ContentType.Application.Json, code)
        }
    }
    get("/api/file-tasks/latest") { call.reply(gateway.latest()) }
    get("/api/file-tasks/{id}") { call.reply(gateway.get(call.parameters["id"].orEmpty())) }
    post("/api/file-tasks") {
        call.mutate({
            val body = call.receiveText(); require(body.length <= 6000) { "Запрос слишком большой." }
            val request = json.decodeFromString(FileTaskRequest.serializer(), body); request.validate()
            gateway.start(request)
        }, HttpStatusCode.Accepted)
    }
    post("/api/file-tasks/{id}/apply") { call.mutate({ gateway.apply(call.parameters["id"].orEmpty()) }, HttpStatusCode.OK) }
}
