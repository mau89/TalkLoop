package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.*

class FileTaskApiTest {
    @Test fun goalStartsAsyncAndPreparedDiffCanBeSavedAndRestored() = testApplication {
        var current: FileTask? = null
        val gateway = object : FileTaskGateway {
            override suspend fun start(request: FileTaskRequest) = FileTask("task", request.goal).also { current = it }
            override suspend fun latest() = current
            override suspend fun get(id: String) = current?.takeIf { it.id == id }
            override suspend fun apply(id: String) = current!!.copy(state = "applied", message = "Сохранено").also { current = it }
        }
        application { routing { fileTaskRoutes(gateway) } }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/file-tasks/latest").status)
        val started = client.post("/api/file-tasks") { contentType(ContentType.Application.Json); setBody("""{"goal":"Найди использования компонента и подготовь отчёт"}""") }
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertTrue(Json.decodeFromString<FileTask>(started.bodyAsText()).isRunning)
        current = current!!.copy(state = "prepared", readFiles = listOf("a.kt", "b.kt", "README.md"), diff = "+Report")
        assertEquals(3, Json.decodeFromString<FileTask>(client.get("/api/file-tasks/task").bodyAsText()).readFiles.size)
        val saved = client.post("/api/file-tasks/task/apply") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals("applied", Json.decodeFromString<FileTask>(saved.bodyAsText()).state)
        assertEquals("applied", Json.decodeFromString<FileTask>(client.get("/api/file-tasks/latest").bodyAsText()).state)
    }

    @Test fun invalidGoalAndBrowserPostCannotLaunchOrApply() = testApplication {
        var calls = 0
        val gateway = object : FileTaskGateway {
            override suspend fun start(request: FileTaskRequest): FileTask { calls++; error("must not start") }
            override suspend fun latest(): FileTask? = null
            override suspend fun get(id: String): FileTask? = null
            override suspend fun apply(id: String): FileTask { calls++; error("must not apply") }
        }
        application { routing { fileTaskRoutes(gateway) } }
        for (body in listOf("{", "{}", """{"goal":" "}""")) assertEquals(HttpStatusCode.BadRequest,
            client.post("/api/file-tasks") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/file-tasks/task/apply") {
            contentType(ContentType.Application.Json); header("Origin", "https://example.com"); setBody("{}")
        }.status)
        assertEquals(0, calls)
    }

    @Test fun staleDiffIsReportedAsConflict() = testApplication {
        val gateway = object : FileTaskGateway {
            override suspend fun start(request: FileTaskRequest): FileTask = throw FileTaskBusy()
            override suspend fun latest(): FileTask? = null
            override suspend fun get(id: String): FileTask? = null
            override suspend fun apply(id: String): FileTask = throw FileTaskConflict("Документ изменился; подготовьте новый diff.")
        }
        application { routing { fileTaskRoutes(gateway) } }
        val result = client.post("/api/file-tasks/task/apply") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Conflict, result.status)
        assertTrue(result.bodyAsText().contains("изменился"))
    }
}
