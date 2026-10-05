package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RagRetrievalTest {
    private fun hit(id: String, score: Double) = DocumentChunkHit(id, "https://example.com/$id", "Рецепт $id",
        "$id.txt", "Приготовление", emptyList(), emptyList(), 10, "Текст $id", score)
    private fun retriever(hits: List<DocumentChunkHit>) = DocumentRetriever {
        DocumentSearchResponse(it.query, results = listOf(DocumentStrategyResults(it.strategy, hits)), searchSeconds = 0.02)
    }

    @Test fun rewritePreservesRecipeVariantNumbersUnitsAndNegation() {
        val query = "  Пожалуйста, подскажи: по кулинарной книге: как готовят «Суп 2» без мяса при 60 °C 30–120 минут?  "
        assertEquals("как готовят «Суп 2» без мяса при 60 °C 30–120 минут?", rewriteRagQuery(query))
        assertEquals("Какие количества нужны?", rewriteRagQuery("Какие количества нужны по книге?"))
        assertEquals("Борщ не найден?", rewriteRagQuery("Борщ не найден?"))
        assertEquals("Расскажи не по книге?", rewriteRagQuery("Расскажи не по книге?"))
        assertEquals("Подскажи", rewriteRagQuery("Подскажи"))
    }

    @Test fun inclusiveThresholdStableSortDeduplicationAndFinalLimitHaveExplicitReasons() = runTest {
        val hits = listOf(hit("low", .859), hit("boundary", .86), hit("best", .95), hit("best", .95), hit("extra", .90))
        val context = retrieveRagContext(retriever(hits), "Вопрос", RagSettings(limit = 2, filterEnabled = true, candidateLimit = 5))
        assertEquals(5, context.request.limit)
        assertEquals(listOf("best", "extra"), context.trace.selectedChunkIds)
        assertEquals(listOf("duplicate", "top_k", "below_similarity"), context.trace.rejected.map { it.reason })
        val boundary = retrieveRagContext(retriever(listOf(hit("yes", .86))), "Вопрос", RagSettings(filterEnabled = true))
        assertEquals(1, boundary.sources.size)
    }

    @Test fun baselinePreservesOriginalOrderAndQueryAndUsesOnlyFinalLimit() = runTest {
        val hits = listOf(hit("first", .82), hit("second", .90))
        val context = retrieveRagContext(retriever(hits), "По книге: Вопрос?", RagSettings(limit = 2))
        assertEquals("По книге: Вопрос?", context.request.query)
        assertEquals(2, context.request.limit)
        assertEquals(hits, context.sources)
        assertTrue(context.trace.rejected.isEmpty())
    }

    @Test fun rewriteAndFilterCanBeEnabledIndependently() = runTest {
        val hits = listOf(hit("weak", .80))
        val rewrite = retrieveRagContext(retriever(hits), "По книге: Вопрос?", RagSettings(rewriteEnabled = true))
        assertEquals("Вопрос?", rewrite.request.query)
        assertEquals(hits, rewrite.sources)
        val filter = retrieveRagContext(retriever(hits), "По книге: Вопрос?", RagSettings(filterEnabled = true))
        assertEquals("По книге: Вопрос?", filter.request.query)
        assertTrue(filter.sources.isEmpty())
    }

    @Test fun filteredPromptUsesOriginalQuestionAndOnlyRenumberedSurvivors() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf("Ответ [1]")))
        val tools = AgentKnowledgeTools(AgentToolProvider { null }, retriever(listOf(hit("weak", .82), hit("strong", .90)))) {
            AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true, rag = RagSettings(rewriteEnabled = true, filterEnabled = true))
        }
        val agent = TalkLoopAgent(llm, toolProvider = tools)
        agent.respond("По книге: Как готовить?")
        val call = checkNotNull(agent.lastToolCall.value)
        assertTrue(call.arguments.contains("Как готовить?"))
        assertFalse(call.arguments.contains("По книге:"))
        assertTrue(call.result.contains("По книге: Как готовить?"))
        assertTrue(call.result.contains("\"number\":1"))
        assertFalse(call.result.contains("Текст weak"))
        assertTrue(llm.specs.single().system.orEmpty().contains("Текст strong"))
        assertFalse(llm.specs.single().system.orEmpty().contains("Текст weak"))
        assertEquals("По книге: Как готовить?", llm.requests.single().single().text)
    }

    @Test fun allRejectedStillCallsGroundedLlmWithEmptyContextAndNoValidCitations() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf("Нет сведений [1]")))
        val agent = RagAgent(AgentRuntime(llm), retriever(listOf(hit("wrong", .84))), mainAgentConfig = AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT))
        val answer = agent.answer("Борщ?", RagMode.WITH_RAG, RagSettings(filterEnabled = true))
        assertTrue(answer.sources.isEmpty())
        assertEquals(listOf(1), answer.invalidCitationNumbers)
        assertEquals(1, answer.retrieval?.rejected?.size)
        assertTrue(llm.specs.single().system.orEmpty().contains("\"fragments\":[]"))
        assertFalse(llm.specs.single().system.orEmpty().contains("Текст wrong"))
    }

    @Test fun settingsSnapshotSurvivesToggleDuringRetrieval() = runTest {
        var selected = AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true,
            rag = RagSettings(rewriteEnabled = true, filterEnabled = true))
        val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val tools = AgentKnowledgeTools(AgentToolProvider { null }, DocumentRetriever {
            started.complete(Unit); resume.await(); retriever(listOf(hit("weak", .8))).search(it)
        }) { selected }
        val pending = async { tools.callFor("По книге: Вопрос?") }
        started.await(); selected = selected.copy(rag = RagSettings()); resume.complete(Unit)
        val call = checkNotNull(pending.await())
        assertTrue(call.documentSources.orEmpty().isEmpty())
        assertEquals(true, call.retrieval?.settings?.filterEnabled)
    }

    @Test fun badParametersAndMalformedResponsesFailBeforeUsingContext() = runTest {
        for (settings in listOf(RagSettings(minSimilarity = Double.NaN), RagSettings(minSimilarity = Double.POSITIVE_INFINITY),
            RagSettings(minSimilarity = 1.01), RagSettings(candidateLimit = 0), RagSettings(candidateLimit = 11),
            RagSettings(limit = 5, candidateLimit = 3, filterEnabled = true))) {
            assertFailsWith<IllegalArgumentException> { retrieveRagContext(DocumentRetriever { error("Не вызывать") }, "Вопрос", settings) }
        }
        assertFailsWith<IllegalArgumentException> { retrieveRagContext(retriever(listOf(hit("bad", Double.NaN))), "Вопрос", RagSettings()) }
        assertFailsWith<IllegalArgumentException> { retrieveRagContext(retriever(List(6) { hit("$it", .9) }), "Вопрос", RagSettings()) }
        assertFailsWith<IllegalArgumentException> { retrieveRagContext(DocumentRetriever {
            retriever(emptyList()).search(it).copy(query = "Другой запрос")
        }, "Вопрос", RagSettings()) }
        assertFailsWith<CancellationException> { retrieveRagContext(DocumentRetriever { throw CancellationException() }, "Вопрос", RagSettings()) }
    }
}
