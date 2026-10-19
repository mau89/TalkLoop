package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ReleaseApiTest {
    private val request = ReleaseRequest("8d64764", "1.1.0", 2)
    private fun script(root: Path): Path = root.resolve("worker.py").also { file ->
        Files.writeString(file, """
            import argparse, pathlib, json, time, hashlib, zipfile
            p=argparse.ArgumentParser()
            for x in ['repo','base','version','version-code','output']: p.add_argument('--'+x)
            a=p.parse_args(); o=pathlib.Path(a.output); o.mkdir(parents=True)
            def save(data):
                t=o/'release.tmp'; t.write_text(json.dumps(data)); t.replace(o/'release.json')
            save({'state':'running','gates':[{'name':'gradle','state':'running','log':'gradle.log'}]})
            for i in range(600):
                if (pathlib.Path(a.repo)/'finish').exists(): break
                time.sleep(.05)
            else: raise SystemExit(1)
            apk='TalkLoop-'+a.version+'-unsigned.apk'
            (o/apk).write_bytes(b'test apk artifact')
            (o/'release-notes.md').write_text('Notes from the test worker')
            (o/'report.md').write_text('Report from the test worker')
            (o/'gradle.log').write_text('Old log line\n'*3000+'BUILD SUCCESSFUL')
            save({'state':'prepared','message':'Candidate ready','head':'a'*40,
                'tests':{'tests':3},'apk':{'sha256':hashlib.sha256((o/apk).read_bytes()).hexdigest()},
                'gates':[{'name':'gradle','state':'passed','log':'gradle.log'}]})
            with zipfile.ZipFile(o/('TalkLoop-'+a.version+'-candidate.zip'),'w') as z: z.write(o/apk,apk)
        """.trimIndent())
    }

    @Test fun mobileStartProgressDownloadAndRestoreUseRealBackgroundProcess() = testApplication {
        val root = Files.createTempDirectory("release-api-")
        val gateway = LocalReleaseGateway(root = root, script = script(root))
        try {
            application { routing { releaseRoutes(gateway) } }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/releases/latest").status)
            val start = client.post("/api/releases") { contentType(ContentType.Application.Json); setBody(Json.encodeToString(ReleaseRequest.serializer(), request)) }
            assertEquals(HttpStatusCode.Accepted, start.status)
            val id = Json.decodeFromString<ReleaseJob>(start.bodyAsText()).id
            assertEquals(HttpStatusCode.Conflict, client.post("/api/releases") {
                contentType(ContentType.Application.Json); setBody(Json.encodeToString(ReleaseRequest.serializer(), request))
            }.status)
            Files.writeString(root.resolve("finish"), "ok")
            var job = Json.decodeFromString<ReleaseJob>(client.get("/api/releases/$id").bodyAsText())
            repeat(150) {
                if (job.isRunning) { delay(20); job = Json.decodeFromString(client.get("/api/releases/$id").bodyAsText()) }
            }
            assertEquals("prepared", job.state, job.message)
            assertEquals(3, job.kotlinTests)
            assertEquals("Notes from the test worker", job.releaseNotes)
            assertTrue(job.logTail.endsWith("BUILD SUCCESSFUL")); assertTrue(job.logTail.length <= 12_000)
            val apk = job.artifacts.single { it.name.endsWith(".apk") }
            val download = client.get("/api/releases/$id/artifacts/${apk.name}")
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals("test apk artifact", download.bodyAsText())
            assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.contains("attachment"))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/releases/$id/artifacts/secrets.properties").status)
            assertNull(gateway.artifact(id, "../worker.py"))
            val outside = root.resolve("secrets.properties"); Files.writeString(outside, "private test fixture")
            val report = root.resolve("day35-data/app/$id/result/report.md")
            Files.delete(report); Files.createSymbolicLink(report, outside)
            assertNull(gateway.artifact(id, "report.md"))
            gateway.close()
            LocalReleaseGateway(root = root).use { restored ->
                assertEquals(id, restored.latest()!!.id)
                assertEquals("prepared", restored.get(id)!!.state)
                assertNotNull(restored.artifact(id, "TalkLoop-1.1.0-candidate.zip"))
            }
        } finally { gateway.close(); root.toFile().deleteRecursively() }
    }

    @Test fun interruptedPreparationDoesNotRemainRunningAfterServerRestart() = runBlocking {
        val root = Files.createTempDirectory("release-interrupted-")
        val gateway = LocalReleaseGateway(root = root, script = script(root))
        try {
            val started = gateway.start(request)
            gateway.close()
            LocalReleaseGateway(root = root).use { restored ->
                val job = restored.get(started.id)!!
                assertEquals("blocked", job.state)
                assertFalse(job.isRunning)
                assertNull(restored.artifact(started.id, "TalkLoop-1.1.0-candidate.zip"))
            }
        } finally { gateway.close(); root.toFile().deleteRecursively() }
    }

    @Test fun invalidParametersAndBrowserRequestsNeverLaunchBuild() = testApplication {
        var starts = 0
        val gateway = object : ReleaseGateway {
            override suspend fun start(request: ReleaseRequest): ReleaseJob { starts++; error("must not start") }
            override suspend fun latest(): ReleaseJob? = null
            override suspend fun get(id: String): ReleaseJob? = null
            override suspend fun artifact(id: String, name: String): Path? = null
        }
        application { routing { releaseRoutes(gateway) } }
        for (body in listOf("{", "{}", """{"base":"--help","version":"1.1.0","versionCode":2}""",
            """{"base":"HEAD","version":"../1.1.0","versionCode":2}""", """{"base":"HEAD","version":"1.1.0","versionCode":0}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/releases") { contentType(ContentType.Application.Json); setBody(body) }.status)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/releases") {
            contentType(ContentType.Application.Json); header("Origin", "https://example.com")
            setBody(Json.encodeToString(ReleaseRequest.serializer(), request))
        }.status)
        assertEquals(0, starts)
    }
}
