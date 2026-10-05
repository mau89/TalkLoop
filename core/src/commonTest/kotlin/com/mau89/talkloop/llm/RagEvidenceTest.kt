package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class RagEvidenceTest {
    private val source = DocumentChunkHit("soup-1", "https://example.com/soup", "Луковый суп", "soup.txt",
        "Луковый суп 1 / Приготовление", emptyList(), emptyList(), 80,
        "Когда картофель будет почти готов, добавить лук и морковь и довести до готовности.\nЗатем положить сыр. Варить 3–5 минут, постоянно помешивая.", .90)
    private fun draft(text: String = "После готовности овощей добавить сыр и варить 3–5 минут.",
        quote: String = source.text, id: String = source.chunkId) = Json.encodeToString(RagEvidenceDraft.serializer(),
        RagEvidenceDraft("answered", listOf(RagClaimDraft(text, listOf(RagQuoteDraft(id, quote)))), ""))
    private val yes = """{"supported":true,"unsupportedClaimNumbers":[],"reason":"Подтверждено цитатой."}"""
    private val no = """{"supported":false,"unsupportedClaimNumbers":[1],"reason":"Сыр добавлен раньше готовности овощей."}"""
    private fun tools(hits: List<DocumentChunkHit> = listOf(source), selected: RagSettings = RagSettings(evidenceEnabled = true)) =
        AgentKnowledgeTools(AgentToolProvider { error("MCP отключён") }, DocumentRetriever {
            DocumentSearchResponse(it.query, results = listOf(DocumentStrategyResults(it.strategy, hits)), searchSeconds = .01)
        }) { AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true, rag = selected) }

    @Test fun exactQuotesKeepTrustedMetadataAndOriginalSourceNumbers() {
        val unrelated = source.copy(chunkId = "other", text = "Другой рецепт.")
        val evidence = validateRagEvidence(draft(), listOf(unrelated, source))
        assertEquals("answered", evidence.status)
        assertEquals(listOf(2), evidence.usedSourceNumbers)
        val rendered = renderRagEvidence(evidence, listOf(unrelated, source))
        assertTrue(rendered.contains("[2] Луковый суп"))
        assertTrue(rendered.contains("chunk_id: soup-1"))
        assertTrue(rendered.contains("Раздел: Луковый суп 1 / Приготовление"))
        assertTrue(rendered.contains(source.source))
        assertFalse(rendered.contains("[1]"))
    }

    @Test fun quoteNumbersDoNotMaskAnIncorrectAnswerDuringGrading() {
        val evidence = validateRagEvidence(draft(text = "Варить 30–50 минут."), listOf(source))
        val control = RagControlQuestion("test", "Сколько варить?", "3–5 минут", listOf(source.source), listOf(RagCheck("3–5 минут", listOf("3–5"))))
        val answer = RagAnswer(RagMode.WITH_RAG, renderRagEvidence(evidence, listOf(source)), DEFAULT_MODEL,
            1, 1, 1, "end_turn", sources = listOf(source), citedSourceNumbers = listOf(1), evidence = evidence)
        assertTrue(gradeRagAnswer(control, answer).allChecksMatched)
        assertFalse(gradeRagEvidenceAnswer(control, answer).allChecksMatched)
    }

    @Test fun whitespaceCanDifferButQuotesCannotChangeDigitsPunctuationCaseOrChunk() {
        assertEquals("answered", validateRagEvidence(draft(quote = source.text.replace('\n', ' ')), listOf(source)).status)
        for (raw in listOf(draft(quote = source.text.replace("3–5", "30–50")), draft(quote = source.text.lowercase()),
            draft(id = "invented"), draft(quote = "Положить сыр до готовности овощей."), draft(quote = "3–5"))) {
            val rejected = validateRagEvidence(raw, listOf(source))
            assertEquals("unknown", rejected.status)
            assertTrue(rejected.claims.isEmpty())
            assertTrue(rejected.usedSourceNumbers.isEmpty())
        }
    }

    @Test fun shortIngredientLineIsValidEvidenceButBareNumbersAreNot() {
        val ingredient = source.copy(text = "лук: 10 г")
        assertEquals("answered", validateRagEvidence(draft(text = "Лука нужно 10 г.", quote = ingredient.text), listOf(ingredient)).status)
        assertEquals("unknown", validateRagEvidence(draft(quote = "10 г"), listOf(ingredient)).status)
    }

    @Test fun missingEvidenceInvalidJsonAndMixedUnknownAnswerAreRejected() {
        for (raw in listOf("Ответ [1]", "{}", """{"status":"answered","claims":[],"clarification":""}""",
            """{"status":"answered","claims":[{"text":"Факт","citations":[]}],"clarification":""}""",
            draft(text = "Варить 3–5 минут [99]."), draft().replace("\"answered\"", "\"unknown\""))) {
            assertEquals("unknown", validateRagEvidence(raw, listOf(source)).status)
        }
    }

    @Test fun weakContextForcesUnknownAndClarificationWithoutLlmEvenWithFilterOff() = runTest {
        val llm = FakeLlmClient()
        val agent = TalkLoopAgent(llm, toolProvider = tools(listOf(source.copy(score = .85))))
        val text = agent.respond("Борщ?")
        assertTrue(text.startsWith("Не знаю"))
        assertTrue(text.contains("Уточните"))
        assertFalse(text.contains("Источники:"))
        assertTrue(llm.requests.isEmpty())
        assertEquals("weak_context", agent.lastToolCall.value?.evidence?.reason)
        assertEquals(2, agent.history.value.size)
    }

    @Test fun freshRagRunnerReportsLocalRefusalWithoutStaleUsage() = runTest {
        val llm = FakeLlmClient()
        val runner = RagAgent(AgentRuntime(llm), DocumentRetriever {
            DocumentSearchResponse(it.query, results = listOf(DocumentStrategyResults(it.strategy, emptyList())), searchSeconds = .01)
        }, mainAgentConfig = AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT))
        val answer = runner.answer("Борщ?", RagMode.WITH_RAG, RagSettings(evidenceEnabled = true))
        assertTrue(answer.complete)
        assertEquals("local_refusal", answer.stopReason)
        assertEquals(0, answer.inputTokens)
        assertEquals(0, answer.evidence?.apiCalls)
        assertTrue(answer.citedSourceNumbers.isEmpty())
    }

    @Test fun semanticMismatchCannotEnterHistoryEvenWithRealQuote() = runTest {
        val wrong = draft(text = "Сыр добавляют, когда картофель почти готов.")
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(wrong, no, wrong, no)))
        val agent = TalkLoopAgent(llm, toolProvider = tools())
        val response = agent.respond("Когда добавляют сыр?")
        assertTrue(response.startsWith("Не знаю"))
        assertTrue(agent.history.value.none { !it.fromUser && "картофель почти готов" in it.text })
        assertEquals("unsupported_claim", agent.lastToolCall.value?.evidence?.reason)
        assertTrue(agent.lastToolCall.value?.evidence?.claims.orEmpty().isEmpty())
        assertEquals(4, llm.requests.size)
        assertFalse(llm.requests[1].single().text.contains("Ожидание"))
    }

    @Test fun schemaIsUsedAndOnlyRenderedAnswerEntersHistoryWithBothCallsCounted() = runTest {
        val generation = LlmAnswer(draft(), "end_turn", null, 100, 70, cacheReadInputTokens = 10)
        val verification = LlmAnswer(yes, "end_turn", null, 40, 20, cacheReadInputTokens = 5)
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(generation, verification)))
        val agent = TalkLoopAgent(llm, toolProvider = tools())
        val response = agent.respond("Когда добавляют сыр?")
        assertTrue(response.contains("Ответ:"))
        assertTrue(response.contains("Источники:"))
        assertTrue(response.contains("Цитаты:"))
        assertTrue(llm.specs.all { it.jsonSchema != null })
        assertEquals(155, agent.statistics.value.inputTokens)
        assertEquals(90, agent.statistics.value.outputTokens)
        assertEquals(45, agent.statistics.value.lastTurn?.verificationInputTokens)
        assertEquals("claude-sonnet-5", llm.specs.last().model)
        assertEquals(.000182, agent.statistics.value.lastTurn!!.inputCostUsd, .000000001)
        assertEquals(.00055, agent.statistics.value.lastTurn!!.outputCostUsd, .000000001)
        assertEquals(110.0 / DEFAULT_CONTEXT_WINDOW_TOKENS, agent.statistics.value.lastTurn?.contextUsage)
        assertEquals(1, agent.statistics.value.requestCount)
        assertEquals(2, agent.history.value.size)
        assertEquals(response, agent.history.value.last().text)
        assertFalse(response.contains("\"claims\""))
    }

    @Test fun inventedQuoteIsRejectedBeforeCallingSemanticVerifier() = runTest {
        val wrong = draft(quote = "Варить борщ сорок минут без соли.")
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(wrong, wrong)))
        val agent = TalkLoopAgent(llm, toolProvider = tools())
        assertTrue(agent.respond("Как варить?").startsWith("Не знаю"))
        assertEquals(2, llm.requests.size)
        assertTrue(llm.specs.all { it.model == DEFAULT_MODEL })
        assertEquals("invalid_evidence", agent.lastToolCall.value?.evidence?.reason)
    }

    @Test fun contradictoryOrMalformedVerifierVerdictFailsClosed() = runTest {
        for (verdict in listOf("Ошибка JSON", """{"supported":true,"unsupportedClaimNumbers":[1],"reason":"Ошибка"}""")) {
            val llm = FakeLlmClient(ArrayDeque<Any>(listOf(draft(), verdict, draft(), verdict)))
            assertTrue(TalkLoopAgent(llm, toolProvider = tools()).respond("Как варить?").startsWith("Не знаю"))
        }
    }

    @Test fun verifierFailureNeverPublishesDraftAndCancellationPropagates() = runTest {
        val unavailable = FakeLlmClient(ArrayDeque<Any>(listOf(draft(), LlmException("Недоступен"))))
        val agent = TalkLoopAgent(unavailable, toolProvider = tools())
        assertTrue(agent.respond("Как варить?").contains("Повторите запрос"))
        assertEquals("verification_unavailable", agent.lastToolCall.value?.evidence?.reason)
        val cancelled = FakeLlmClient(ArrayDeque<Any>(listOf(draft(), CancellationException("Отмена"))))
        val cancelledAgent = TalkLoopAgent(cancelled, toolProvider = tools())
        assertFailsWith<CancellationException> { cancelledAgent.respond("Как варить?") }
        assertTrue(cancelledAgent.history.value.isEmpty())
    }

    @Test fun truncatedDraftDoesNotProduceSourcesOrAnotherModelCall() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(LlmAnswer(draft(), "max_tokens", null, 10, 10))))
        val agent = TalkLoopAgent(llm, toolProvider = tools())
        assertTrue(agent.respond("Как варить?").startsWith("Не знаю"))
        assertEquals(1, llm.requests.size)
        assertEquals("incomplete_response", agent.lastToolCall.value?.evidence?.reason)
    }

    @Test fun noncontiguousIngredientQuoteIsRepairedAndBothChecksRemainMandatory() = runTest {
        val olivye = source.copy(chunkId = "olivye", title = "Оливье", section = "Основной рецепт",
            text = "Примерно на 4 порции:\nМясо — 200 г\nКартофель — 200 г\nМайонез — 150 г")
        val bad = draft(text = "Майонеза примерно на 4 порции нужно 150 г.",
            quote = "Примерно на 4 порции:\nМайонез — 150 г", id = olivye.chunkId)
        val good = draft(text = "Майонеза примерно на 4 порции нужно 150 г.", quote = olivye.text, id = olivye.chunkId)
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(
            LlmAnswer(bad, "end_turn", null, 100, 70, cacheReadInputTokens = 10),
            LlmAnswer(good, "end_turn", null, 65, 35, cacheReadInputTokens = 5),
            LlmAnswer(yes, "end_turn", null, 40, 20, cacheReadInputTokens = 5))))
        val agent = TalkLoopAgent(llm, toolProvider = tools(listOf(olivye)))
        val response = agent.respond("Сколько майонеза примерно на четыре порции?")
        assertTrue(response.contains("150 г"))
        assertTrue(response.contains("Цитаты:"))
        assertEquals(3, llm.requests.size)
        assertTrue(llm.requests[1].single().text.contains("quote_not_in_chunk"))
        assertTrue(llm.requests[1].single().text.contains("olivye"))
        assertTrue(llm.specs.all { it.jsonSchema != null })
        assertEquals("verified", agent.lastToolCall.value?.evidence?.reason)
        assertEquals(1, agent.lastToolCall.value?.evidence?.repairAttempts)
        assertEquals("invalid_evidence", agent.lastToolCall.value?.evidence?.initialReason)
        assertEquals(3, agent.lastToolCall.value?.evidence?.apiCalls)
        assertEquals(180, agent.lastToolCall.value?.evidence?.generationInputTokens)
        assertEquals(45, agent.lastToolCall.value?.evidence?.verificationInputTokens)
        val usage = agent.statistics.value.lastTurn!!
        assertEquals(225, usage.inputTokens)
        assertEquals(125, usage.outputTokens)
        assertEquals(70, usage.repairInputTokens)
        assertEquals(.0002475, usage.inputCostUsd, .000000001)
        assertEquals(.000725, usage.outputCostUsd, .000000001)
        assertEquals(110.0 / DEFAULT_CONTEXT_WINDOW_TOKENS, usage.contextUsage)
        assertEquals(response, agent.history.value.last().text)
    }

    @Test fun semanticFeedbackCanCorrectClaimButCannotBypassSecondVerifier() = runTest {
        val llm = FakeLlmClient(ArrayDeque<Any>(listOf(draft(text = "Сыр добавляют, когда картофель почти готов."), no, draft(), yes)))
        val agent = TalkLoopAgent(llm, toolProvider = tools())
        assertTrue(agent.respond("Когда добавляют сыр?").contains("После готовности овощей"))
        assertEquals(4, llm.requests.size)
        assertTrue(llm.requests[2].single().text.contains("Сыр добавлен раньше готовности овощей"))
        assertEquals(4, agent.lastToolCall.value?.evidence?.apiCalls)
        assertEquals("unsupported_claim", agent.lastToolCall.value?.evidence?.initialReason)
        assertEquals("verified", agent.lastToolCall.value?.evidence?.reason)
    }

    @Test fun repairFailureIsTechnicalAndCancellationDoesNotWriteHistory() = runTest {
        val bad = draft(quote = "Выдуманная цитата не существует.")
        val failed = TalkLoopAgent(FakeLlmClient(ArrayDeque<Any>(listOf(bad, LlmException("Ошибка")))), toolProvider = tools())
        assertTrue(failed.respond("Когда добавлять?").contains("Повторите запрос"))
        assertEquals("repair_unavailable", failed.lastToolCall.value?.evidence?.reason)
        assertTrue(failed.lastToolCall.value?.evidence?.claims.orEmpty().isEmpty())
        val cancelled = TalkLoopAgent(FakeLlmClient(ArrayDeque<Any>(listOf(bad, CancellationException("Отмена")))), toolProvider = tools())
        assertFailsWith<CancellationException> { cancelled.respond("Когда добавлять?") }
        assertTrue(cancelled.history.value.isEmpty())
    }

    @Test fun diagnosticDistinguishesMissingRecipeInvalidQuoteAndTechnicalFailure() {
        assertTrue(ragEvidenceDiagnostic(RagEvidenceResult("unknown", "weak_context")).contains("порога"))
        assertTrue(ragEvidenceDiagnostic(RagEvidenceResult("unknown", "invalid_evidence", validationErrors = listOf("claim_2_quote_not_in_chunk"))).contains("утверждения 2"))
        assertTrue(ragEvidenceDiagnostic(RagEvidenceResult("unknown", "verification_unavailable")).contains("Повторите"))
    }
}
