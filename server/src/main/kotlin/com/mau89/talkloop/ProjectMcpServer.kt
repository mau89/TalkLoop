package com.mau89.talkloop

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

fun interface ProjectRepository {
    suspend fun gitBranch(): JsonObject
}

/** The client cannot choose a directory or pass arguments to git. */
class LocalProjectRepository(
    private val root: Path = Paths.get(System.getProperty("talkloop.project.root", ".")).toAbsolutePath().normalize(),
) : ProjectRepository {
    override suspend fun gitBranch(): JsonObject = withContext(Dispatchers.IO) {
        val branch = git("rev-parse", "--abbrev-ref", "HEAD")
        val commit = git("rev-parse", "HEAD")
        buildJsonObject {
            put("branch", if (branch == "HEAD") JsonNull else JsonPrimitive(branch))
            put("detached", branch == "HEAD")
            put("commit", commit)
        }
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Git не ответил за 5 секунд." }
            check(process.exitValue() == 0) { "Не удалось прочитать git-ветку проекта. Проверьте репозиторий на сервере." }
            return process.inputStream.bufferedReader().readText().trim()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

fun createProjectMcpServer(repository: ProjectRepository): Server = Server(
    serverInfo = Implementation("talkloop-project", "1.0.0"),
    options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
).apply {
    addTool(
        name = "git_branch",
        description = "Текущая git-ветка проекта TalkLoop; при detached HEAD — commit без имени ветки.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
    ) { request ->
        try {
            require(request.arguments.isNullOrEmpty()) { "git_branch не принимает аргументы." }
            val state = repository.gitBranch()
            CallToolResult(content = listOf(TextContent(state.toString())), structuredContent = state, isError = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CallToolResult(content = listOf(TextContent(error.message ?: "Не удалось получить git-ветку.")), isError = true)
        }
    }
}
