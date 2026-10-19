package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.*
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.nio.file.Paths

/** The assistant really calls the MCP endpoint; it does not bypass it to read JSON. */
class McpSupportContextGateway(private val endpoint: String = "http://127.0.0.1:8083/mcp") : SupportContextGateway {
    override suspend fun context(userId: String, ticketId: String): SupportContext = withTimeout(15_000) {
        val http = HttpClient(CIO) { install(SSE) }
        val client = Client(clientInfo = Implementation("talkloop-support-assistant", "1.0.0"))
        try {
            client.connect(StreamableHttpClientTransport(http, endpoint, requestBuilder = {
                if (method == HttpMethod.Post) {
                    headers.remove(HttpHeaders.Accept)
                    headers.append(HttpHeaders.Accept, "application/json, text/event-stream;q=0.1")
                }
            }))
            val result = client.callTool("support_ticket_context", buildJsonObject { put("userId", userId); put("ticketId", ticketId) })
            if (result.isError == true) throw SupportTicketUnavailable()
            val content = checkNotNull(result.structuredContent) { "MCP поддержки вернул пустой контекст." }
            Json.decodeFromJsonElement(SupportContext.serializer(), content)
        } finally { client.close(); http.close() }
    }
}

interface SupportService {
    suspend fun catalog(): SupportCatalog
    suspend fun answer(request: SupportRequest): SupportReply
}

class SupportBusy : RuntimeException("Поддержка уже готовит ответ. Дождитесь завершения и повторите вопрос.")

class LocalSupportService(
    private val repository: SupportRepository = JsonSupportRepository(),
    private val crm: SupportContextGateway = McpSupportContextGateway(),
    private val index: DocumentIndexGateway = LocalDocumentIndexGateway(
        dataDirectory = Paths.get(System.getProperty("talkloop.project.root", ".")).resolve("day33-data"),
        preparationCommand = ".day21-venv/bin/python tools/day33/index_support.py",
    ),
    private val llm: LlmClient = OllamaLlmClient("http://127.0.0.1:11434", model = COOKBOOK_OLLAMA_MODEL),
) : SupportService, AutoCloseable {
    private val lock = Mutex()
    override suspend fun catalog() = repository.catalog()
    override suspend fun answer(request: SupportRequest): SupportReply {
        request.validate()
        if (!lock.tryLock()) throw SupportBusy()
        try {
            return withTimeout(180_000) {
                SupportAssistant(llm, crm, DocumentRetriever { index.search(it) }).answer(request)
            }
        } finally { lock.unlock() }
    }
    override fun close() { llm.close(); if (index is AutoCloseable) index.close() }
}

fun Route.supportRoutes(service: SupportService) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun io.ktor.server.application.ApplicationCall.failure(status: HttpStatusCode, message: String) =
        respondText(JsonObject(mapOf("error" to JsonPrimitive(message))).toString(), ContentType.Application.Json, status)

    get("/api/support/catalog") {
        try { call.respondText(json.encodeToString(SupportCatalog.serializer(), service.catalog()), ContentType.Application.Json) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { call.failure(HttpStatusCode.ServiceUnavailable, "Не удалось загрузить демо-клиентов. Проверьте tools/day33/support-demo.json на Mac.") }
    }
    post("/api/support/ask") {
        try {
            require(call.request.headers["Origin"] == null && call.request.contentType().match(ContentType.Application.Json)) { "Используйте приложение или JSON-запрос локального сервиса." }
            val body = call.receiveText()
            require(body.length <= 12_000) { "Запрос слишком большой." }
            val request = json.decodeFromString(SupportRequest.serializer(), body)
            request.validate()
            call.respondText(json.encodeToString(SupportReply.serializer(), service.answer(request)), ContentType.Application.Json)
        } catch (_: TimeoutCancellationException) {
            call.failure(HttpStatusCode.GatewayTimeout, "Поддержка не ответила вовремя. Проверьте MCP и Ollama на Mac.")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val status = when (error) {
                is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest
                is SupportTicketUnavailable -> HttpStatusCode.NotFound
                is SupportBusy -> HttpStatusCode.Conflict
                else -> HttpStatusCode.ServiceUnavailable
            }
            val message = when (error) {
                is SerializationException -> "Неверный формат запроса."
                is IllegalArgumentException, is SupportTicketUnavailable, is SupportBusy, is DocumentIndexUnavailable -> error.message.orEmpty()
                else -> "Ответ поддержки не получен. Проверьте локальные MCP, индекс FAQ и Ollama; ошибочный ответ не показан как готовое решение."
            }
            call.failure(status, message)
        }
    }
}
