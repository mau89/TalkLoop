package com.mau89.talkloop

import io.ktor.client.plugins.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectMcpServerTest {
    @Test fun readsActualBranchAfterSwitchAndDetachedHead() = runBlocking {
        val root = Files.createTempDirectory("talkloop-project-test")
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        try {
            git("init", "-b", "day31-test")
            git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "test")
            val repository = LocalProjectRepository(root)
            assertEquals("day31-test", repository.gitBranch()["branch"]!!.jsonPrimitive.content)
            git("checkout", "-b", "другая-ветка")
            assertEquals("другая-ветка", repository.gitBranch()["branch"]!!.jsonPrimitive.content)
            git("checkout", "--detach")
            val detached = repository.gitBranch()
            assertEquals(JsonNull, detached["branch"])
            assertEquals(true, detached["detached"]!!.jsonPrimitive.boolean)
            assertEquals(40, detached["commit"]!!.jsonPrimitive.content.length)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun nonRepositoryDoesNotInventBranch() = runBlocking {
        val root = Files.createTempDirectory("talkloop-not-git")
        try {
            assertFailsWith<IllegalStateException> { LocalProjectRepository(root).gitBranch() }
            Unit
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun publishesReadOnlyToolAndCallsRepositoryThroughMcp() = testApplication {
        var calls = 0
        application {
            projectModule(enableDnsRebindingProtection = false, projectRepository = ProjectRepository {
                calls++
                buildJsonObject { put("branch", "live-$calls"); put("detached", false); put("commit", "abc") }
            })
        }
        val http = createClient { install(SSE) }
        val mcp = Client(clientInfo = Implementation("day31-test", "1.0.0"))
        try {
            mcp.connect(StreamableHttpClientTransport(http, "http://localhost/mcp"))
            val tool = mcp.listTools().tools.single()
            assertEquals("git_branch", tool.name)
            assertEquals(true, tool.annotations?.readOnlyHint)
            repeat(2) { index ->
                val result = mcp.callTool("git_branch", emptyMap<String, String>())
                assertFalse(result.isError == true)
                assertEquals("live-${index + 1}", result.structuredContent!!["branch"]!!.jsonPrimitive.content)
            }
            val invalid = mcp.callTool("git_branch", mapOf("path" to "/tmp"))
            assertTrue(invalid.isError == true)
            assertEquals(2, calls)
        } finally { mcp.close(); http.close() }
    }
}
