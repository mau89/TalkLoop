package com.mau89.talkloop

import com.mau89.talkloop.llm.PrReviewCoverage
import com.mau89.talkloop.llm.PrReviewJob
import com.mau89.talkloop.llm.PrReviewRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrReviewApiTest {
    @Test
    fun startsAsynchronouslyAndRestoresLatestResult() = testApplication {
        var stored: PrReviewJob? = null
        val gateway = object : PrReviewGateway {
            override suspend fun start(request: PrReviewRequest): PrReviewJob = PrReviewJob("job", request.prNumber).also { stored = it }
            override suspend fun latest() = stored
            override suspend fun get(id: String) = stored?.takeIf { it.id == id }
        }
        application { routing { prReviewRoutes(gateway) } }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/reviews/latest").status)
        val response = client.post("/api/reviews") {
            contentType(ContentType.Application.Json)
            setBody("{\"prNumber\":7}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("running", Json.decodeFromString<PrReviewJob>(response.bodyAsText()).state)
        stored = stored!!.copy(state = "completed", summary = "Есть замечание", coverage = PrReviewCoverage(1, 2, true))
        for (path in listOf("latest", "job")) {
            val job = Json.decodeFromString<PrReviewJob>(client.get("/api/reviews/$path").bodyAsText())
            assertEquals(stored, job)
            assertTrue(job.coverage!!.partial)
        }
    }

    @Test
    fun malformedRequestsAndBrowserOriginsNeverStartInference() = testApplication {
        var calls = 0
        val gateway = object : PrReviewGateway {
            override suspend fun start(request: PrReviewRequest): PrReviewJob { calls++; error("must not run") }
            override suspend fun latest(): PrReviewJob? = null
            override suspend fun get(id: String): PrReviewJob? = null
        }
        application { routing { prReviewRoutes(gateway) } }
        for (body in listOf("{", "{}", "{\"prNumber\":0}", "{\"prNumber\":\"7;echo secret\"}", " ".repeat(1025))) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/reviews") {
                contentType(ContentType.Application.Json); setBody(body)
            }.status)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/reviews") {
            contentType(ContentType.Application.Json); header("Origin", "https://example.com"); setBody("{\"prNumber\":7}")
        }.status)
        assertEquals(0, calls)
    }

    @Test
    fun duplicateStartReturnsSameJobAndProcessFailureIsVisibleAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("review-gateway-test")
        try {
            Files.createDirectories(root.resolve("tools/day32"))
            Files.writeString(root.resolve("tools/day32/app_review.py"), "import time\ntime.sleep(60)\n")
            val directory = root.resolve("results")
            val gateway = LocalPrReviewGateway(root, directory)
            val job = try {
                val first = gateway.start(PrReviewRequest(7))
                assertEquals(first, gateway.start(PrReviewRequest(7)))
                var busy = false
                try { gateway.start(PrReviewRequest(8)) } catch (error: PrReviewBusy) { busy = true }
                assertTrue(busy)
                assertEquals(null, gateway.get("../../etc/passwd"))
                first
            } finally { gateway.close() }
            LocalPrReviewGateway(root, directory).use { restored ->
                assertEquals("error", restored.latest()!!.state)
                assertEquals(job.id, restored.latest()!!.id)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun workerExitWithoutReportNeverLooksLikeSuccessfulReview() = runBlocking {
        val root = Files.createTempDirectory("review-failed-worker")
        try {
            Files.createDirectories(root.resolve("tools/day32"))
            Files.writeString(root.resolve("tools/day32/app_review.py"), "raise SystemExit(1)\n")
            LocalPrReviewGateway(root).use { gateway ->
                val first = gateway.start(PrReviewRequest(7))
                val result = withTimeout(5000) {
                    var current = gateway.get(first.id)!!
                    while (current.isRunning) { delay(20); current = gateway.get(first.id)!! }
                    current
                }
                assertEquals("error", result.state)
                assertTrue(result.findings.isEmpty())
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
