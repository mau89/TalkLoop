package com.mau89.talkloop.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SupportAssistantTest {
    private fun context(code: String = "EMAIL_NOT_VERIFIED") = SupportContext(
        SupportUser("U1001", "Елена", "Basic (демо)", "ru"),
        SupportTicket("T1001", "U1001", "Не работает авторизация", "open", "Android", "1.1.0", code,
            mapOf("emailVerified" to "false"), "Письмо не найдено"),
    )
    private fun found(request: DocumentSearchRequest, file: String = "docs/support/faq.md", empty: Boolean = false) =
        DocumentSearchResponse(request.query, results = listOf(DocumentStrategyResults("structural", if (empty) emptyList() else listOf(
            DocumentChunkHit("faq-email", "faq", "FAQ", file, "Подтверждение почты", emptyList(), emptyList(), 50,
                "EMAIL_NOT_VERIFIED: проверьте письмо подтверждения.", 0.9),
        ))), searchSeconds = 0.01)

    private class Model(var body: String = """{"answer":"Проверьте письмо подтверждения [1].","disposition":"answered","sourceNumbers":[1]}""") : LlmClient {
        var calls = 0
        var payload: JsonObject? = null
        var spec: ResponseSpec? = null
        var complete = true
        override suspend fun reply(history: List<ChatMessage>) = error("unused")
        override suspend fun countInputTokens(history: List<ChatMessage>, spec: ResponseSpec) = 1
        override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
            calls++; payload = Json.parseToJsonElement(history.single().text).jsonObject; this.spec = spec
            return LlmAnswer(body, if (complete) "stop" else "max_tokens", null, 100, 20)
        }
    }

    @Test fun refreshesTicketForEveryTurnAndUsesOnlyCurrentQuestionAndHistory() = runTest {
        val llm = Model()
        var calls = 0
        val queries = mutableListOf<String>()
        val agent = SupportAssistant(llm, SupportContextGateway { user, ticket ->
            assertEquals("U1001", user); assertEquals("T1001", ticket)
            context(if (++calls == 1) "EMAIL_NOT_VERIFIED" else "UPDATED_CODE")
        }, DocumentRetriever { queries += it.query; found(it) })
        val first = agent.answer(SupportRequest("U1001", "T1001", "Почему не работает авторизация?"))
        assertEquals("EMAIL_NOT_VERIFIED", first.context.ticket.issueCode)
        val history = listOf(ChatMessage(true, "Я проверила папку Спам"))
        val second = agent.answer(SupportRequest("U1001", "T1001", "Что дальше?", history))
        assertEquals(2, calls)
        assertEquals("Что дальше?", queries.last())
        assertEquals("UPDATED_CODE", second.context.ticket.issueCode)
        assertEquals("Я проверила папку Спам", llm.payload!!["conversation"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf(1), second.usedSources)
        assertEquals("docs/support/faq.md", second.sources.single().file)
        assertNotNull(llm.spec!!.jsonSchema)
        assertFalse(llm.payload.toString().contains("U1002"))
    }

    @Test fun wrongTicketAndInvalidInputNeverReachRetrievalOrModel() = runTest {
        val llm = Model()
        val agent = SupportAssistant(llm, SupportContextGateway { _, _ -> context().copy(ticket = context().ticket.copy(userId = "U1002")) },
            DocumentRetriever { error("must not search") })
        assertFailsWith<IllegalArgumentException> { agent.answer(SupportRequest("U1001", "T1001", "Вход")) }
        assertFailsWith<IllegalArgumentException> { agent.answer(SupportRequest("../secrets", "T1001", "Вход")) }
        assertEquals(0, llm.calls)
    }

    @Test fun missingEvidenceAbstainsWithoutCallingModelAndOtherCorpusIsRejected() = runTest {
        val llm = Model()
        val crm = SupportContextGateway { _, _ -> context() }
        val agent = SupportAssistant(llm, crm, DocumentRetriever { found(it, empty = true) })
        assertEquals("needs_info", agent.answer(SupportRequest("U1001", "T1001", "Вопрос")).disposition)
        val cookbook = SupportAssistant(llm, crm, DocumentRetriever { found(it, file = "cookbook/recipe.md") })
        assertFailsWith<IllegalArgumentException> { cookbook.answer(SupportRequest("U1001", "T1001", "Вопрос")) }
        assertEquals(0, llm.calls)
    }

    @Test fun inventedSourceInvalidShapeAndTruncatedAnswerAreNotPublished() = runTest {
        val llm = Model()
        val agent = SupportAssistant(llm, SupportContextGateway { _, _ -> context() }, DocumentRetriever { found(it) })
        for (bad in listOf(
            """{"answer":"Сбросьте пароль [99]","disposition":"answered","sourceNumbers":[99]}""",
            """{"answer":"Ответ [2]","disposition":"answered","sourceNumbers":[1]}""",
            """{"answer":"Ответ","disposition":"answered","sourceNumbers":[]}""",
            """{"answer":"Ответ","disposition":"answered","sourceNumbers":["1"]}""",
            """{"answer":"Ответ","disposition":"answered","sourceNumbers":{}}""", "not json",
        )) {
            llm.body = bad
            assertFailsWith<IllegalStateException>(bad) { agent.answer(SupportRequest("U1001", "T1001", "Вопрос")) }
        }
        llm.complete = false
        assertFailsWith<IllegalStateException> { agent.answer(SupportRequest("U1001", "T1001", "Вопрос")) }
    }
}
