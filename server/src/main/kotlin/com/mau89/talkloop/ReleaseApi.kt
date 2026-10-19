package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.http.*
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface ReleaseGateway {
    suspend fun start(request: ReleaseRequest): ReleaseJob
    suspend fun latest(): ReleaseJob?
    suspend fun get(id: String): ReleaseJob?
    suspend fun artifact(id: String, name: String): Path?
}

class ReleaseBusy : RuntimeException("На Mac уже готовится релиз. Дождитесь завершения.")

/** Mobile requests only select version/base; the process and output paths stay server-owned. */
class LocalReleaseGateway(
    private val root: Path = Paths.get(System.getProperty("talkloop.project.root", ".")).toAbsolutePath().normalize(),
    private val directory: Path = root.resolve("day35-data/app"),
    private val script: Path = root.resolve("tools/day35/prepare_release.py"),
    private val python: String = "/usr/bin/python3",
) : ReleaseGateway, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "release-timeout").apply { isDaemon = true } }
    private var process: Process? = null
    private var activeId: String? = null
    private var closed = false

    override suspend fun start(request: ReleaseRequest): ReleaseJob = withContext(Dispatchers.IO) {
        request.validate()
        synchronized(lock) {
            check(!closed) { "Сервис релизов остановлен." }
            if (process?.isAlive == true) throw ReleaseBusy()
            val id = UUID.randomUUID().toString()
            val folder = directory.resolve(id)
            Files.createDirectories(folder)
            val job = ReleaseJob(id, request.base, request.version, request.versionCode)
            save(job)
            Files.writeString(directory.resolve("latest.txt"), id)
            try {
                val child = ProcessBuilder(python, script.toString(), "--repo", root.toString(),
                    "--base", request.base, "--version", request.version, "--version-code", request.versionCode.toString(),
                    "--output", folder.resolve("result").toString())
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(folder.resolve("worker.log").toFile()).start()
                process = child; activeId = id
                val timeout = timer.schedule({ synchronized(lock) {
                    if (child.isAlive) { terminate(child); fail(id, "Подготовка превысила 40 минут. Проверьте журнал и повторите запуск.") }
                } }, 40, TimeUnit.MINUTES)
                child.onExit().thenRun { synchronized(lock) {
                    timeout.cancel(false)
                    load(id) // Persist an interrupted/incomplete run, never leave it spinning forever.
                } }
                job
            } catch (_: Exception) {
                fail(id, "Не удалось запустить сборку. Проверьте Python и файловый доступ сервера на Mac.")!!
            }
        }
    }

    override suspend fun latest(): ReleaseJob? = withContext(Dispatchers.IO) { synchronized(lock) {
        val marker = directory.resolve("latest.txt")
        if (Files.isRegularFile(marker)) load(Files.readString(marker).trim()) else null
    } }
    override suspend fun get(id: String): ReleaseJob? = withContext(Dispatchers.IO) { synchronized(lock) { load(id) } }
    override suspend fun artifact(id: String, name: String): Path? = withContext(Dispatchers.IO) { synchronized(lock) {
        val job = load(id) ?: return@synchronized null
        if (job.artifacts.none { it.name == name }) return@synchronized null
        safeFile(id, name)
    } }

    private fun read(id: String): ReleaseJob? {
        if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return null
        val file = directory.resolve(id).resolve("job.json")
        return if (Files.isRegularFile(file)) json.decodeFromString(ReleaseJob.serializer(), Files.readString(file)) else null
    }
    private fun safeFile(id: String, name: String): Path? {
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,100}"))) return null
        val folder = directory.resolve(id).resolve("result")
        val file = folder.resolve(name)
        if (Files.isSymbolicLink(directory.resolve(id)) || Files.isSymbolicLink(folder) || Files.isSymbolicLink(file)) return null
        return file.takeIf { Files.isRegularFile(it) && it.toRealPath().startsWith(folder.toRealPath()) }
    }
    private fun text(id: String, name: String): String {
        val file = safeFile(id, name) ?: return ""
        return if (Files.size(file) <= 80_000) Files.readString(file).take(30_000) else "Файл слишком большой для просмотра; скачайте его отдельно."
    }
    private fun tail(file: Path): String {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return ""
        return Files.newByteChannel(file).use { channel ->
            channel.position((channel.size() - 12_000).coerceAtLeast(0))
            val bytes = ByteBuffer.allocate(12_000)
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* bounded tail */ }
            String(bytes.array(), 0, bytes.position(), Charsets.UTF_8)
        }
    }
    private fun load(id: String): ReleaseJob? {
        val initial = read(id) ?: return null
        val manifest = safeFile(id, "release.json")
        var job = initial
        var apkSha = ""
        if (manifest != null && initial.state != "blocked") {
            try {
                val report = json.parseToJsonElement(Files.readString(manifest)).jsonObject
                val gates = report["gates"]?.let { json.decodeFromJsonElement(ListSerializer(ReleaseGate.serializer()), it) }.orEmpty()
                val state = report["state"]?.jsonPrimitive?.content.orEmpty()
                job = initial.copy(state = if (state in listOf("running", "prepared", "blocked")) state else "blocked",
                    message = report["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: if (gates.lastOrNull()?.state == "running") "Выполняются проверки и сборка…" else "Подготовка релиза…",
                    head = report["head"]?.jsonPrimitive?.content.orEmpty(), gates = gates,
                    kotlinTests = (report["tests"] as? JsonObject)?.get("tests")?.jsonPrimitive?.intOrNull ?: 0,
                    pythonTests = gates.sumOf { it.tests })
                apkSha = (report["apk"] as? JsonObject)?.get("sha256")?.jsonPrimitive?.content.orEmpty()
            } catch (_: Exception) {
                job = initial.copy(state = "blocked", message = "Не удалось прочитать отчёт сборки. Проверьте журнал на Mac.")
            }
        }
        val active = activeId == id && process?.isAlive == true
        val zip = "TalkLoop-${job.version}-candidate.zip"
        if (job.state == "prepared" && safeFile(id, zip) == null) {
            job = job.copy(state = if (active) "running" else "blocked", message = if (active) "Упаковывается результат…" else "Пакет релиза не завершён. Подготовьте релиз заново.")
        }
        if (job.isRunning && !active) job = fail(id, "Подготовка прервана: сервер или процесс сборки остановлен. Запустите релиз заново.")!!
        val expected = linkedMapOf("TalkLoop-${job.version}-unsigned.apk" to "Скачать APK (без подписи)",
            "report.md" to "Скачать отчёт", "release-notes.md" to "Скачать release notes", "release.json" to "Данные проверок")
        if (job.state == "prepared") expected[zip] = "Скачать ZIP-пакет"
        for (name in listOf("python-day32.log", "python-day34.log", "python-day35.log", "gradle.log", "error.log")) expected[name] = "Журнал $name"
        val artifacts = expected.mapNotNull { (name, label) -> safeFile(id, name)?.let {
            ReleaseArtifact(name, label, Files.size(it), if (name.endsWith(".apk")) apkSha else "")
        } }
        val log = safeFile(id, "error.log") ?: safeFile(id, job.gates.lastOrNull()?.log ?: "gradle.log") ?: directory.resolve(id).resolve("worker.log")
        return job.copy(artifacts = artifacts, releaseNotes = text(id, "release-notes.md"), report = text(id, "report.md"), logTail = tail(log))
    }
    private fun save(job: ReleaseJob) {
        val temporary = directory.resolve(job.id).resolve("job.server.tmp")
        Files.writeString(temporary, json.encodeToString(ReleaseJob.serializer(), job))
        Files.move(temporary, temporary.resolveSibling("job.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun fail(id: String, message: String): ReleaseJob? = read(id)?.copy(state = "blocked", message = message)?.also(::save)
    private fun terminate(child: Process) { child.descendants().forEach { it.destroyForcibly() }; child.destroyForcibly() }
    override fun close() = synchronized(lock) {
        closed = true
        process?.takeIf { it.isAlive }?.let { terminate(it); activeId?.let { id -> fail(id, "Сервер остановлен. Подготовьте релиз заново.") } }
        timer.shutdownNow(); Unit
    }
}

fun Route.releaseRoutes(gateway: ReleaseGateway) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun io.ktor.server.application.ApplicationCall.reply(job: ReleaseJob?, status: HttpStatusCode = HttpStatusCode.OK) {
        if (job == null) respondText("{\"error\":\"Релиз не найден.\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
        else respondText(json.encodeToString(ReleaseJob.serializer(), job), ContentType.Application.Json, status)
    }
    get("/api/releases/latest") { call.reply(gateway.latest()) }
    get("/api/releases/{id}") { call.reply(gateway.get(call.parameters["id"].orEmpty())) }
    post("/api/releases") {
        try {
            require(call.request.headers["Origin"] == null && call.request.contentType().match(ContentType.Application.Json)) { "Запускайте релиз из приложения TalkLoop с JSON-запросом." }
            val body = call.receiveText(); require(body.length <= 2000) { "Запрос слишком большой." }
            val request = json.decodeFromString(ReleaseRequest.serializer(), body); request.validate()
            call.reply(gateway.start(request), HttpStatusCode.Accepted)
        } catch (error: Exception) {
            val status = when (error) {
                is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest
                is ReleaseBusy -> HttpStatusCode.Conflict
                else -> throw error
            }
            val message = if (error is SerializationException) "Неверный формат параметров релиза." else error.message.orEmpty()
            call.respondText(JsonObject(mapOf("error" to JsonPrimitive(message))).toString(), ContentType.Application.Json, status)
        }
    }
    get("/api/releases/{id}/artifacts/{name}") {
        val name = call.parameters["name"].orEmpty()
        val file = gateway.artifact(call.parameters["id"].orEmpty(), name)
        if (file == null) call.respondText("Файл релиза не найден.", status = HttpStatusCode.NotFound)
        else {
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName.toString()).toString())
            call.respondFile(file.toFile())
        }
    }
}
