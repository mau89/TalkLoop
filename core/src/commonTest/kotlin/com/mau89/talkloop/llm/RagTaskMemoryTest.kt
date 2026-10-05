package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class RagTaskMemoryTest {
    private val goal = RagMemoryFact("Готовлю обед", 1)
    private fun raw(goal: RagMemoryFact? = this.goal, constraints: List<RagMemoryFact> = emptyList()) =
        Json.encodeToString(RagMemoryDraft.serializer(), RagMemoryDraft(goal, null, emptyList(), constraints, emptyList(),
            "Сколько сыра в луковом супе 1?", "Луковый суп 1 сыр"))
    private fun emptySearch(request: DocumentSearchRequest) = DocumentSearchResponse(request.query,
        results = listOf(DocumentStrategyResults(request.strategy, emptyList())), searchSeconds = .001)
    private fun tools(resolver: RagTaskMemoryResolver, retriever: DocumentRetriever = DocumentRetriever(::emptySearch)) =
        AgentKnowledgeTools(AgentToolProvider { null }, retriever, resolver) {
            AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true,
                rag = RagSettings(evidenceEnabled = true, filterEnabled = true), taskMemoryEnabled = true)
        }
    private fun resolution(request: RagMemoryRequest, constraints: List<RagMemoryFact> = emptyList()) = RagMemoryResolution(
        RagConversationTurn(request.question, "Сколько сыра в луковом супе 1?", "Луковый суп 1 сыр",
            RagTaskMemory(goal = goal, constraints = constraints, revision = request.context.taskMemory.revision + 1), 100, 20),
        LlmAnswer("memory", "end_turn", null, 100, 20), DEFAULT_MODEL)

    @Test fun memoryOnlyAcceptsExactUserExcerptsWithValidTurnNumbers() {
        val users = listOf("Готовлю обед, без замен.", "А сколько его?")
        val (state, _) = validateRagMemoryDraft(raw(constraints = listOf(RagMemoryFact("без замен", 1))), users, 5)
        assertEquals(goal, state.goal)
        assertEquals(6, state.revision)
        for (invalid in listOf(raw(RagMemoryFact("Готовлю ужин", 1)), raw(RagMemoryFact("Готовлю обед", 2)),
            raw(RagMemoryFact("Готовлю обед", 0)), "{}")) {
            assertFailsWith<RagTaskMemoryException> { validateRagMemoryDraft(invalid, users, 0) }
        }
    }

    @Test fun resolverDoesNotTreatAssistantRecipeFactsAsUserMemory() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(raw(RagMemoryFact("Мяса 200 г", 1)))))
        val resolver = LlmRagTaskMemoryResolver(llm)
        val history = listOf(ChatMessage(true, "Готовлю обед"), ChatMessage(false, "Мяса 200 г"))
        assertFailsWith<RagTaskMemoryException> { resolver.resolve(RagMemoryRequest("А дальше?", AgentToolContext(history))) }
    }

    @Test fun everyFollowupSearchesResolvedQuestionAndPreservesOriginalTrace() = runTest {
        val searches = mutableListOf<DocumentSearchRequest>()
        val provider = tools(RagTaskMemoryResolver { resolution(it) }, DocumentRetriever { searches += it; emptySearch(it) })
        val call = provider.callFor("А сколько его?", AgentToolContext(listOf(ChatMessage(true, "Готовлю обед"))))!!
        assertEquals(listOf("Луковый суп 1 сыр", "Сколько сыра в луковом супе 1?"), searches.map { it.query })
        assertTrue(searches.all { it.limit == 10 })
        assertEquals(2, call.ragConversation?.searchAttempts)
        assertEquals("А сколько его?", call.retrieval?.originalQuery)
        assertTrue(call.result.contains("Сколько сыра в луковом супе 1?"))
        assertTrue(call.directResponse!!.contains("Источники:"))
        assertTrue(call.directResponse!!.contains("Уточните"))
    }

    @Test fun fullDialogueAndTaskMemorySurviveWindowEvictionAndJsonRestart() = runTest {
        val values = mutableMapOf<String, String>()
        fun store() = JsonChatHistoryStore(object : StringStore {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        })
        val contextSizes = mutableListOf<Int>()
        val provider = tools(RagTaskMemoryResolver { contextSizes += it.context.history.size; resolution(it) })
        fun chat() = TalkLoopAgent(FakeLlmClient(), AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT, contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4)),
            historyStore = store(), toolProvider = provider)
        var agent = chat()
        repeat(7) { agent.respond(if (it == 0) "Готовлю обед" else "А сколько его?") }
        assertEquals(4, agent.history.value.size)
        assertEquals(14, agent.dialogueArchive.value.size)
        assertEquals(goal, agent.ragTaskMemory.value.goal)
        agent = chat()
        assertEquals(14, agent.dialogueArchive.value.size)
        assertEquals(7, agent.ragTaskMemory.value.revision)
        assertEquals(goal, agent.ragTaskMemory.value.goal)
        agent.respond("А дальше?")
        assertEquals((0..14 step 2).toList(), contextSizes)
        assertEquals(16, agent.dialogueArchive.value.size)
        assertEquals(8, agent.ragTaskMemory.value.revision)
    }

    @Test fun changingConstraintReplacesItWithoutResettingGoal() = runTest {
        val users = listOf("Готовлю обед, подробно", "Теперь кратко вместо подробно")
        val before = validateRagMemoryDraft(raw(constraints = listOf(RagMemoryFact("подробно", 1))), users.take(1), 0).first
        val after = validateRagMemoryDraft(raw(constraints = listOf(RagMemoryFact("кратко", 2))), users, before.revision).first
        assertEquals(before.goal, after.goal)
        assertEquals(listOf(RagMemoryFact("кратко", 2)), after.constraints)
        assertFalse(after.constraints.any { it.text == "подробно" })
    }

    @Test fun sourceAbsenceIsExplicitAndMemoryCallIsCountedOnLocalRefusal() = runTest {
        val llm = FakeLlmClient()
        val agent = TalkLoopAgent(llm, toolProvider = tools(RagTaskMemoryResolver { resolution(it) }))
        val response = agent.respond("Готовлю обед")
        assertTrue(response.contains("Источники:\nРелевантные источники не найдены"))
        assertEquals(0, agent.lastToolCall.value?.evidence?.apiCalls)
        assertEquals(100, agent.statistics.value.inputTokens)
        assertEquals(20, agent.statistics.value.outputTokens)
        assertEquals(100, agent.statistics.value.lastTurn?.memoryInputTokens)
        assertEquals(0.0, agent.statistics.value.lastTurn?.contextUsage)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun failedRetrievalOrCancellationCannotCommitPlannedMemoryOrHistory() = runTest {
        for (failure in listOf(IllegalStateException("Поиск недоступен"), CancellationException("Отмена"))) {
            val store = InMemoryChatHistoryStore()
            val agent = TalkLoopAgent(FakeLlmClient(), historyStore = store, toolProvider =
                tools(RagTaskMemoryResolver { resolution(it) }, DocumentRetriever { throw failure }))
            assertFails { agent.respond("Готовлю обед") }
            assertEquals(RagTaskMemory(), agent.ragTaskMemory.value)
            assertTrue(agent.history.value.isEmpty())
            assertTrue(agent.dialogueArchive.value.isEmpty())
            assertEquals(RagTaskMemory(), store.loadMemory().ragTaskMemory)
        }
    }

    @Test fun newTaskClearsArchiveAndRagMemoryAndCannotLeakIntoNextDialogue() = runTest {
        val store = InMemoryChatHistoryStore()
        val agent = TalkLoopAgent(FakeLlmClient(), AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT, contextStrategy = ContextStrategy.MemoryLayers()),
            historyStore = store, toolProvider = tools(RagTaskMemoryResolver { resolution(it) }))
        agent.respond("Готовлю обед")
        agent.startNewTask()
        assertTrue(agent.history.value.isEmpty())
        assertTrue(agent.dialogueArchive.value.isEmpty())
        assertEquals(RagTaskMemory(), agent.ragTaskMemory.value)
        assertNull(agent.lastToolCall.value)
        assertEquals(RagTaskMemory(), store.loadMemory().ragTaskMemory)
        assertTrue(store.loadMemory().dialogueArchive.isEmpty())
    }

    @Test fun failedSaveCannotPublishNewMemoryOrEraseExistingTask() = runTest {
        val delegate = InMemoryChatHistoryStore()
        var fail = false
        val store = object : ChatHistoryStore by delegate {
            override fun saveMemory(memory: AgentMemorySnapshot) {
                if (fail) error("Диск недоступен")
                delegate.saveMemory(memory)
            }
        }
        val agent = TalkLoopAgent(FakeLlmClient(), AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT, contextStrategy = ContextStrategy.MemoryLayers()),
            historyStore = store, toolProvider = tools(RagTaskMemoryResolver { resolution(it) }))
        agent.respond("Готовлю обед")
        val previous = agent.dialogueArchive.value
        val memory = agent.ragTaskMemory.value
        fail = true
        assertFailsWith<IllegalStateException> { agent.startNewTask() }
        assertEquals(previous, agent.dialogueArchive.value)
        assertEquals(memory, agent.ragTaskMemory.value)
        assertFailsWith<IllegalStateException> { agent.respond("А сколько его?") }
        assertEquals(previous, agent.dialogueArchive.value)
        assertEquals(memory, agent.ragTaskMemory.value)
    }

    @Test fun literalMemoryCorrectionIsBoundedAndAllItsTokensAreCounted() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(raw(RagMemoryFact("Готовлю ужин", 1)), raw())))
        val result = LlmRagTaskMemoryResolver(llm).resolve(RagMemoryRequest("Готовлю обед", AgentToolContext()))
        assertEquals(goal, result.turn.memory.goal)
        assertEquals(2, result.turn.memoryApiCalls)
        assertEquals(1, result.turn.memoryRepairAttempts)
        assertEquals(4, result.usage.totalInputTokens)
        assertEquals(6, result.usage.outputTokens)
        assertEquals(2, llm.requests.size)
        assertTrue(llm.requests.last().single().text.contains("отклонённый JSON"))
        val bad = raw(RagMemoryFact("Готовлю ужин", 1))
        val repeated = FakeLlmClient(ArrayDeque<Any>(listOf(bad, bad)))
        assertFailsWith<RagTaskMemoryException> { LlmRagTaskMemoryResolver(repeated).resolve(RagMemoryRequest("Готовлю обед", AgentToolContext())) }
        assertEquals(2, repeated.requests.size)
    }

    @Test fun memoryGenerationAndVerifierCostsAreCombinedWithoutInflatingMainContextWindow() = runTest {
        val source = DocumentChunkHit("meat", "https://example.com/recipe", "Рецепт", "recipe.txt", "Состав",
            emptyList(), emptyList(), 10, "Мясо — 200 г", .90)
        val answer = Json.encodeToString(RagEvidenceDraft.serializer(), RagEvidenceDraft("answered",
            listOf(RagClaimDraft("Мяса нужно 200 г.", listOf(RagQuoteDraft(source.chunkId, source.text)))), ""))
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(LlmAnswer(answer, "end_turn", null, 20, 30),
            LlmAnswer("""{"supported":true,"unsupportedClaimNumbers":[],"reason":"Цитата подтверждает норму."}""", "end_turn", null, 40, 50))))
        val provider = tools(RagTaskMemoryResolver { resolution(it) }, DocumentRetriever {
            emptySearch(it).copy(results = listOf(DocumentStrategyResults(it.strategy, listOf(source))))
        })
        val agent = TalkLoopAgent(llm, toolProvider = provider)
        assertTrue(agent.respond("Готовлю обед").contains("Источники:"))
        val usage = agent.statistics.value.lastTurn!!
        assertEquals(160, usage.inputTokens)
        assertEquals(100, usage.outputTokens)
        assertEquals(.00095, usage.costUsd, .000000001)
        assertEquals(20.0 / DEFAULT_CONTEXT_WINDOW_TOKENS, usage.contextUsage)
        assertEquals(100, usage.memoryInputTokens)
        assertEquals(40, usage.verificationInputTokens)
    }

    @Test fun strictLoadProtectsBrokenOrNewerSessionFromBeingOverwritten() {
        var raw = "{broken"
        val storage = object : StringStore {
            override fun read(key: String) = raw
            override fun write(key: String, value: String) { raw = value }
            override fun remove(key: String) { raw = "" }
        }
        assertFailsWith<IllegalStateException> { JsonChatHistoryStore(storage, strictLoading = true).loadMemory() }
        assertEquals("{broken", raw)
        raw = """{"version":999,"messages":[]}"""
        assertFailsWith<IllegalStateException> { JsonChatHistoryStore(storage, strictLoading = true).loadMemory() }
        assertTrue(JsonChatHistoryStore(storage).load().isEmpty())
    }
}
