package com.mau89.talkloop.llm

import kotlinx.serialization.json.Json

/** Настройки считываются один раз до сетевых вызовов, переключение действует на следующий запрос. */
data class AgentKnowledgeSettings(
    val mcpEnabled: Boolean = true,
    val ragEnabled: Boolean = false,
    val rag: RagSettings = RagSettings(),
    val taskMemoryEnabled: Boolean = false,
)

/** Подключает книгу к существующему агенту, сохраняя его историю, память и инварианты. */
class AgentKnowledgeTools(
    private val mcp: AgentToolProvider,
    private val retriever: DocumentRetriever,
    private val memoryResolver: RagTaskMemoryResolver? = null,
    private val settings: () -> AgentKnowledgeSettings,
) : AgentToolProvider {
    override suspend fun callFor(request: String): AgentToolCall? = callFor(request, AgentToolContext())

    override suspend fun callFor(request: String, context: AgentToolContext): AgentToolCall? {
        val selected = settings()
        // Служебные команды погоды и книжного MCP сохраняют прежний маршрут.
        if (selected.mcpEnabled) mcp.callFor(request)?.let { return it }
        if (!selected.ragEnabled) return null
        val question = request.trim()
        val plan = if (selected.taskMemoryEnabled) {
            checkNotNull(memoryResolver) { "Не подключена память RAG-чата." }.resolve(RagMemoryRequest(question, context))
        } else null
        val initial = retrieveRagContext(retriever, question, selected.rag, plan?.turn?.searchQuery)
        val fallback = plan != null && initial.sources.isEmpty() && plan.turn.resolvedQuestion != initial.trace.searchQuery
        val found = if (fallback) retrieveRagContext(retriever, question, selected.rag, plan!!.turn.resolvedQuestion) else initial
        val conversation = plan?.turn?.copy(searchQuery = found.trace.searchQuery, searchAttempts = if (fallback) 2 else 1,
            initialSearchQuery = initial.trace.searchQuery.takeIf { fallback })
        val weakContext = selected.rag.evidenceEnabled && found.sources.isEmpty()
        return AgentToolCall(
            toolName = "document_search",
            toolDescription = "Поиск в локальной кулинарной книге",
            inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
            arguments = Json.encodeToString(DocumentSearchRequest.serializer(), found.request),
            result = encodeRagPrompt(plan?.turn?.resolvedQuestion ?: question, found.sources),
            serverName = "Кулинарная книга",
            documentSources = found.sources,
            retrieval = found.trace,
            evidenceEnabled = selected.rag.evidenceEnabled,
            evidenceVerifierModel = selected.rag.evidenceVerifierModel,
            evidence = if (weakContext) RagEvidenceResult("unknown", "weak_context") else null,
            directResponse = if (weakContext) {
                ragUnknownResponse("weak_context").let { if (plan != null) ragChatResponse(it, found.sources) else it }
            } else null,
            ragConversation = conversation,
            memoryUsage = plan?.usage,
            memoryModel = plan?.model,
        )
    }
}
