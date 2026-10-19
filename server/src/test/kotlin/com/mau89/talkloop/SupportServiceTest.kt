package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class SupportServiceTest {
    private val fixture = Paths.get("../tools/day33/support-demo.json")
    private val context = SupportContext(SupportUser("U1001", "Елена", "Basic", "ru"),
        SupportTicket("T1001", "U1001", "Вход", "open", "Android", "1.1.0", "EMAIL_NOT_VERIFIED", mapOf("emailVerified" to "false"), ""))

    @Test fun jsonRepositoryChecksOwnerAndReadsUpdatesWithoutCaching() = runBlocking {
        val file = Files.createTempFile("support-demo-test", ".json")
        try {
            val text = Files.readString(fixture)
            Files.writeString(file, text)
            val repo = JsonSupportRepository(file)
            assertEquals(2, repo.catalog().users.size)
            assertEquals("EMAIL_NOT_VERIFIED", repo.context("U1001", "T1001").ticket.issueCode)
            assertFailsWith<SupportTicketUnavailable> { repo.context("U1001", "T1002") }
            Files.writeString(file, text.replace("Письмо подтверждения пока не найдено.", "Письмо уже получено."))
            assertTrue(repo.context("U1001", "T1001").ticket.notes.contains("уже получено"))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun actualMcpProtocolPublishesReadOnlyContextAndRejectsCrossUserTicket() = testApplication {
        application { supportModule(enableDnsRebindingProtection = false, repository = JsonSupportRepository(fixture)) }
        val http = createClient { install(SSE) }
        val mcp = Client(clientInfo = Implementation("support-test", "1.0.0"))
        try {
            mcp.connect(StreamableHttpClientTransport(http, "http://localhost/mcp"))
            val tool = mcp.listTools().tools.single()
            assertEquals("support_ticket_context", tool.name)
            assertEquals(true, tool.annotations?.readOnlyHint)
            val reply = mcp.callTool(tool.name, mapOf("userId" to "U1002", "ticketId" to "T1002"))
            assertFalse(reply.isError == true)
            val ticket = Json.decodeFromJsonElement<SupportContext>(reply.structuredContent!!)
            assertEquals("OAUTH_CALLBACK_SCHEME", ticket.ticket.issueCode)
            assertEquals("Максим", ticket.user.name)
            val forbidden = mcp.callTool(tool.name, mapOf("userId" to "U1001", "ticketId" to "T1002"))
            assertTrue(forbidden.isError == true)
            assertNull(forbidden.structuredContent)
            assertFalse(forbidden.content.toString().contains("Максим"))
            assertTrue(mcp.callTool(tool.name, mapOf("userId" to "U1001", "ticketId" to "T1001", "path" to "/tmp")).isError == true)
        } finally { mcp.close(); http.close() }
    }

    @Test fun apiPassesTicketAndHistoryAndRejectsInvalidInputBeforeModel() = testApplication {
        var calls = 0
        val service = object : SupportService {
            override suspend fun catalog() = SupportCatalog(listOf(context.user), listOf(SupportTicketSummary("T1001", "U1001", "Вход", "open")))
            override suspend fun answer(request: SupportRequest): SupportReply {
                calls++
                assertEquals("T1001", request.ticketId)
                assertEquals("Уже проверила Спам", request.history.single().text)
                return SupportReply("Повторите отправку письма [1].", "answered", context,
                    listOf(SupportCitation(1, "docs/support/faq.md", "Вход", "FAQ")), listOf(1))
            }
        }
        application { routing { supportRoutes(service) } }
        assertEquals(HttpStatusCode.OK, client.get("/api/support/catalog").status)
        val response = client.post("/api/support/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"U1001","ticketId":"T1001","question":"Что дальше?","history":[{"fromUser":true,"text":"Уже проверила Спам"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("support_ticket_context", Json.decodeFromString<SupportReply>(response.bodyAsText()).mcpTool)
        for (body in listOf("{", "{}", """{"userId":"U1001","ticketId":"T1001","question":""}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/support/ask") { contentType(ContentType.Application.Json); setBody(body) }.status)
        }
        assertEquals(1, calls)
    }

    @Test fun unavailableTicketDoesNotBecomeSuccessfulGenericAnswer() = testApplication {
        val service = object : SupportService {
            override suspend fun catalog() = SupportCatalog(emptyList(), emptyList())
            override suspend fun answer(request: SupportRequest): SupportReply = throw SupportTicketUnavailable()
        }
        application { routing { supportRoutes(service) } }
        val response = client.post("/api/support/ask") {
            contentType(ContentType.Application.Json); setBody("""{"userId":"U1001","ticketId":"T1002","question":"Вход"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("недоступен"))
    }
}
