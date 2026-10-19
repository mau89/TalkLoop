package com.mau89.talkloop

import com.mau89.talkloop.llm.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SupportTicketUnavailable : RuntimeException("Выбранный тикет недоступен этому демо-клиенту.")

interface SupportRepository : SupportContextGateway {
    suspend fun catalog(): SupportCatalog
}

/** Reads the fixed demo file on every call; clients cannot supply paths. */
class JsonSupportRepository(
    private val file: Path = Paths.get(System.getProperty("talkloop.project.root", ".")).resolve("tools/day33/support-demo.json"),
) : SupportRepository {
    @Serializable
    private data class Database(val users: List<SupportUser>, val tickets: List<SupportTicket>)

    private fun read(): Database {
        check(Files.size(file) <= 128_000) { "Демо-база поддержки слишком большая." }
        return Json.decodeFromString(Database.serializer(), Files.readString(file)).also { data ->
            check(data.users.size <= 50 && data.tickets.size <= 100 &&
                data.users.map { it.id }.distinct().size == data.users.size &&
                data.tickets.map { it.id }.distinct().size == data.tickets.size &&
                data.tickets.all { ticket -> data.users.any { it.id == ticket.userId } }) { "Некорректная демо-база поддержки." }
        }
    }

    override suspend fun catalog(): SupportCatalog = withContext(Dispatchers.IO) {
        val data = read()
        SupportCatalog(data.users, data.tickets.map { SupportTicketSummary(it.id, it.userId, it.subject, it.status) })
    }

    override suspend fun context(userId: String, ticketId: String): SupportContext = withContext(Dispatchers.IO) {
        val data = read()
        val user = data.users.singleOrNull { it.id == userId } ?: throw SupportTicketUnavailable()
        val ticket = data.tickets.singleOrNull { it.id == ticketId && it.userId == userId } ?: throw SupportTicketUnavailable()
        check(ticket.notes.length <= 2000 && ticket.facts.size <= 15 && ticket.facts.all { it.key.length <= 80 && it.value.length <= 300 }) {
            "Контекст тикета слишком большой."
        }
        SupportContext(user, ticket)
    }
}

fun createSupportMcpServer(repository: SupportRepository): Server = Server(
    serverInfo = Implementation("talkloop-support", "1.0.0"),
    options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
).apply {
    addTool(name = "support_ticket_context",
        description = "Актуальный демо-клиент и его тикет поддержки TalkLoop. Только чтение, без изменения аккаунтов.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            put("userId", buildJsonObject { put("type", "string"); put("description", "ID выбранного демо-клиента U1001/U1002") })
            put("ticketId", buildJsonObject { put("type", "string"); put("description", "ID его тикета T1001/T1002/T1003") })
        }, required = listOf("userId", "ticketId")),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
    ) { request ->
        try {
            val args = request.arguments.orEmpty()
            require(args.keys == setOf("userId", "ticketId")) { "Передайте только userId и ticketId." }
            val userId = args["userId"]?.jsonPrimitive?.content.orEmpty()
            val ticketId = args["ticketId"]?.jsonPrimitive?.content.orEmpty()
            SupportRequest(userId, ticketId, "context").validate()
            val context = repository.context(userId, ticketId)
            val content = Json.encodeToJsonElement(SupportContext.serializer(), context).jsonObject
            CallToolResult(content = listOf(TextContent(content.toString())), structuredContent = content, isError = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = if (error is SupportTicketUnavailable || error is IllegalArgumentException) error.message else "Не удалось прочитать демо-базу поддержки."
            CallToolResult(content = listOf(TextContent(message.orEmpty())), isError = true)
        }
    }
}
