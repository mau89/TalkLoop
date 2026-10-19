package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/** Real HTTP retrieval + MCP discovery/call + the same TalkLoopAgent as the app. */
fun main(args: Array<String>) = runBlocking {
    val question = args.joinToString(" ").ifBlank { "Как устроен проект TalkLoop и какая сейчас git-ветка?" }
    val model = System.getenv("TALKLOOP_HELP_MODEL") ?: DEFAULT_OLLAMA_MODEL
    val server = System.getenv("TALKLOOP_HELP_SERVER") ?: "http://127.0.0.1:8080"
    val projectMcp = System.getenv("TALKLOOP_HELP_MCP") ?: "http://127.0.0.1:8082/mcp"
    val ollama = System.getenv("TALKLOOP_HELP_OLLAMA") ?: "http://127.0.0.1:11434"
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    val http = HttpClient(CIO) { install(SSE) }
    val llm = OllamaLlmClient(ollama, model)
    val tools = ProjectHelpTools(
        retriever = DocumentRetriever { request ->
            val response = http.post("${server.trimEnd('/')}/api/project/documents/search") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
            }
            val body = response.bodyAsText()
            check(response.status.value == 200) { "Поиск: ${response.status}: $body" }
            json.decodeFromString(DocumentSearchResponse.serializer(), body)
        },
        branch = AgentToolProvider {
            val client = Client(clientInfo = Implementation("talkloop-day31", "1.0.0"))
            try {
                client.connect(StreamableHttpClientTransport(http, projectMcp, requestBuilder = {
                    if (method == HttpMethod.Post) {
                        headers.remove(HttpHeaders.Accept)
                        headers.append(HttpHeaders.Accept, "application/json, text/event-stream;q=0.1")
                    }
                }))
                val tool = client.listTools().tools.single { it.name == "git_branch" }
                val result = client.callTool(tool.name, emptyMap<String, String>())
                check(result.isError != true) { result.content.toString() }
                val content = result.structuredContent?.toString()
                    ?: result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                AgentToolCall(tool.name, tool.description.orEmpty(), tool.inputSchema.toString(), "{}", content,
                    serverName = "talkloop-project")
            } finally { client.close() }
        },
    )
    try {
        val agent = TalkLoopAgent(llm, AgentConfig(model = model, systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
            contextWindowTokens = OLLAMA_CONTEXT_WINDOW_TOKENS, maxTokens = 2400,
            contextCompression = ContextCompressionConfig(enabled = false)), toolProvider = tools)
        val started = System.nanoTime()
        val answer = agent.respond(if (question.startsWith("/help")) question else "/help $question")
        val call = checkNotNull(agent.lastToolCall.value)
        val report = buildJsonObject {
            put("question", question); put("model", model); put("answer", answer)
            put("seconds", (System.nanoTime() - started) / 1e9)
            put("context", Json.parseToJsonElement(call.result))
            put("mcpSteps", buildJsonArray { call.steps.forEach { add(it.toolName) } })
            put("stopReason", agent.statistics.value.lastTurn?.stopReason)
        }
        File("day31-data").mkdirs()
        File("day31-data/last-help.json").writeText(json.encodeToString(JsonObject.serializer(), report) + "\n")
        println(answer)
        println("\nMCP: ${call.steps.joinToString { it.toolName }}; источников: ${call.documentSources.orEmpty().size}")
        println("Результат: day31-data/last-help.json")
    } finally { llm.close(); http.close() }
}
