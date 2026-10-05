package com.mau89.talkloop.llm

import kotlinx.serialization.json.Json

/** Настройки считываются один раз до сетевых вызовов, переключение действует на следующий запрос. */
data class AgentKnowledgeSettings(
    val mcpEnabled: Boolean = true,
    val ragEnabled: Boolean = false,
    val rag: RagSettings = RagSettings(),
)

/** Подключает книгу к существующему агенту, сохраняя его историю, память и инварианты. */
class AgentKnowledgeTools(
    private val mcp: AgentToolProvider,
    private val retriever: DocumentRetriever,
    private val settings: () -> AgentKnowledgeSettings,
) : AgentToolProvider {
    override suspend fun callFor(request: String): AgentToolCall? {
        val selected = settings()
        // Служебные команды погоды и книжного MCP сохраняют прежний маршрут.
        if (selected.mcpEnabled) mcp.callFor(request)?.let { return it }
        if (!selected.ragEnabled) return null
        val question = request.trim()
        val context = retrieveRagContext(retriever, question, selected.rag)
        val weakContext = selected.rag.evidenceEnabled && context.sources.isEmpty()
        return AgentToolCall(
            toolName = "document_search",
            toolDescription = "Поиск в локальной кулинарной книге",
            inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
            arguments = Json.encodeToString(DocumentSearchRequest.serializer(), context.request),
            result = encodeRagPrompt(question, context.sources),
            serverName = "Кулинарная книга",
            documentSources = context.sources,
            retrieval = context.trace,
            evidenceEnabled = selected.rag.evidenceEnabled,
            evidenceVerifierModel = selected.rag.evidenceVerifierModel,
            evidence = if (weakContext) RagEvidenceResult("unknown", "weak_context") else null,
            directResponse = if (weakContext) ragUnknownResponse("weak_context") else null,
        )
    }
}
