package com.mau89.talkloop.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class OllamaLlmClientTest {
    @Test
    fun privateServiceKeyRequiresHttpsAndReachesTagsAndChat() = runTest {
        assertFailsWith<IllegalArgumentException> {
            OllamaLlmClient("http://89.40.70.118:18443", serviceToken = "private-test-key")
        }
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("Bearer private-test-key", request.headers[HttpHeaders.Authorization])
            paths += request.url.encodedPath
            val response = if (request.url.encodedPath == "/api/tags")
                """{"models":[{"name":"qwen2.5:0.5b"}]}"""
            else """{"message":{"role":"assistant","content":"Париж"},"done":true}"""
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("https://89.40.70.118:18443", model = VPS_OLLAMA_MODEL,
            tuning = VPS_OLLAMA_TUNING, serviceToken = "private-test-key", httpEngine = engine)
        try {
            client.checkConnection()
            assertEquals("Париж", client.answer(listOf(ChatMessage(true, "Столица Франции?")), ResponseSpec()).text)
            assertEquals(listOf("/api/tags", "/api/chat"), paths)
        } finally { client.close() }
    }

    @Test
    fun vpsBudgetFitsAgentInstructionsInvariantsAndThreeChatTurns() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val options = body.getValue("options").jsonObject
            assertEquals(8192, options.getValue("num_ctx").jsonPrimitive.int)
            assertEquals(256, options.getValue("num_predict").jsonPrimitive.int)
            val messages = body.getValue("messages").jsonArray
            val system = messages.first().jsonObject.getValue("content").jsonPrimitive.content
            assertContains(system, "ALLERGY-001")
            assertContains(system, "working_memory")
            val bytes = 32 + messages.sumOf { it.jsonObject.getValue("content").jsonPrimitive.content.encodeToByteArray().size + 16 }
            assertTrue(bytes > 2048, "A plain-question profile is too small for the agent")
            assertTrue(bytes + 256 <= 8192, "Fresh agent conversation fits service preflight")
            calls++
            respond("""{"message":{"role":"assistant","content":"Короткий ответ"},"done":true,"prompt_eval_count":900,"eval_count":5}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("https://89.40.70.118:18443", model = VPS_OLLAMA_MODEL,
            tuning = VPS_OLLAMA_TUNING, httpEngine = engine)
        try {
            val agent = TalkLoopAgent(client, config = AgentConfig(model = VPS_OLLAMA_MODEL,
                systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT, maxTokens = 256, contextWindowTokens = 8192,
                contextStrategy = ContextStrategy.MemoryLayers(10), contextCompression = ContextCompressionConfig(enabled = false)),
                invariantStore = InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS))
            for (question in listOf("Привет", "Сколько будет 12 + 9?", "Объясни, чем сервер отличается от приложения.")) {
                assertEquals("Короткий ответ", agent.respond(question))
            }
            assertEquals(3, calls)
            assertEquals(3, agent.statistics.value.requestCount)
        } finally { client.close() }
    }

    @Test
    fun editedThinkingSwitchReachesNativeApi() = runTest {
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertTrue(body.getValue("think").jsonPrimitive.boolean)
            assertEquals(777, body.getValue("options").jsonObject.getValue("num_predict").jsonPrimitive.int)
            respond("""{"message":{"role":"assistant","content":"Ответ"},"done":true,"done_reason":"stop"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine,
            tuning = OllamaTuning(answerMaxTokens = 777, thinking = true))
        try { client.answer(emptyList(), ResponseSpec(maxTokens = 2000)) } finally { client.close() }
    }

    @Test
    fun tunedContextRejectsAddedRecipePromptBeforeNetworkRequest() = runTest {
        val engine = MockEngine { error("Переполнение должно обнаруживаться до HTTP") }
        val tuning = OllamaTuning(contextWindowTokens = 512, answerMaxTokens = 80)
        val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine, tuning = tuning)
        try {
            val schema = buildJsonObject {
                putJsonObject("properties") { putJsonObject("claims") {}; putJsonObject("clarification") {} }
            }
            val spec = ResponseSpec(system = "Кратко", maxTokens = 100, jsonSchema = schema)
            assertTrue(estimateOllamaInputTokens(emptyList(), spec) < 512)
            assertTrue(client.countInputTokens(emptyList(), spec) > 512)
            assertFailsWith<ContextWindowExceededException> { client.answer(emptyList(), spec) }
        } finally { client.close() }
    }

    @Test
    fun recipeProfileReachesNativeApiForMemoryAnswerAndVerifier() = runTest {
        val limits = ArrayDeque(listOf(1400, 1600, 400, 100))
        val temperatures = ArrayDeque(listOf(0.0, 0.0, 0.0, 0.3))
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val options = body.getValue("options").jsonObject
            assertEquals(limits.removeFirst(), options.getValue("num_predict").jsonPrimitive.int)
            assertEquals(24_576, options.getValue("num_ctx").jsonPrimitive.int)
            assertEquals(temperatures.removeFirst(), options.getValue("temperature").jsonPrimitive.double)
            assertEquals(0.0, options.getValue("presence_penalty").jsonPrimitive.double)
            assertEquals(false, body.getValue("think").jsonPrimitive.boolean)
            val system = body.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content
            assertContains(system, "Исходные правила")
            respond("""{"message":{"role":"assistant","content":"{}"},"done":true,"done_reason":"stop"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine, tuning = OllamaTuning())
        try {
            fun schema(vararg names: String) = buildJsonObject {
                putJsonObject("properties") { names.forEach { putJsonObject(it) { put("type", "string") } } }
            }
            for (shape in listOf(schema("resolvedQuestion", "searchQuery"), schema("claims", "clarification"),
                schema("supported", "unsupportedClaimNumbers"))) {
                val spec = ResponseSpec(system = "Исходные правила", maxTokens = 2600, jsonSchema = shape)
                val estimated = client.countInputTokens(emptyList(), spec)
                if ("resolvedQuestion" in shape.toString()) assertEquals(estimateOllamaInputTokens(emptyList(), spec), estimated)
                else assertTrue(estimated > estimateOllamaInputTokens(emptyList(), spec))
                client.answer(emptyList(), spec)
            }
            client.answer(emptyList(), ResponseSpec(system = "Исходные правила", maxTokens = 100, temperature = 0.3))
            assertTrue(limits.isEmpty())
        } finally { client.close() }
    }

    @Test
    fun sendsHistorySystemSchemaAndLimitsWithoutCloudCredentials() = runTest {
        val schema = buildJsonObject { put("type", "object") }
        val engine = MockEngine { request ->
            assertEquals("http://localhost:11434/api/chat", request.url.toString())
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["x-api-key"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("qwen3.5:9b", body["model"]?.jsonPrimitive?.content)
            assertEquals(false, body["stream"]?.jsonPrimitive?.boolean)
            assertEquals(false, body["think"]?.jsonPrimitive?.boolean)
            assertEquals(schema, body["format"])
            val messages = body.getValue("messages").jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"),
                messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals("Отвечай на русском", messages.first().jsonObject["content"]?.jsonPrimitive?.content)
            val options = body.getValue("options").jsonObject
            assertEquals(300, options["num_predict"]?.jsonPrimitive?.int)
            assertEquals(OLLAMA_CONTEXT_WINDOW_TOKENS, options["num_ctx"]?.jsonPrimitive?.int)
            assertEquals(0.2, options["temperature"]?.jsonPrimitive?.double)
            assertEquals("END", options["stop"]?.jsonArray?.single()?.jsonPrimitive?.content)
            respond("""{"message":{"role":"assistant","content":"Ответ","thinking":"скрыто"},"done":true,"done_reason":"stop","prompt_eval_count":42,"eval_count":7} """,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("http://localhost:11434/", httpEngine = engine)
        try {
            val answer = client.answer(listOf(ChatMessage(true, "Первый вопрос"),
                ChatMessage(false, "Первый ответ"), ChatMessage(true, "Уточнение")),
                ResponseSpec(system = "Отвечай на русском", maxTokens = 300,
                    temperature = 0.2, stopSequences = listOf("END"), jsonSchema = schema))
            assertEquals("Ответ", answer.text)
            assertEquals(42, answer.inputTokens)
            assertEquals(7, answer.outputTokens)
            assertEquals("stop", answer.stopReason)
        } finally { client.close() }
    }

    @Test
    fun agentRespondsWithoutTokenCountNetworkRequestsAndRecordsActualUsage() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("/api/chat", request.url.encodedPath)
            respond("""{"message":{"role":"assistant","content":"Четыре"},"done":true,"done_reason":"stop","prompt_eval_count":60,"eval_count":3} """,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine)
        try {
            val agent = TalkLoopAgent(client, AgentConfig(model = DEFAULT_OLLAMA_MODEL,
                systemPrompt = "Отвечай кратко", contextWindowTokens = OLLAMA_CONTEXT_WINDOW_TOKENS))
            assertEquals("Четыре", agent.respond("Сколько будет два плюс два?"))
            assertEquals(1, requests)
            assertEquals(60, agent.statistics.value.inputTokens)
            assertEquals(3, agent.statistics.value.outputTokens)
            assertEquals(0.0, agent.statistics.value.totalCostUsd)
            assertEquals(2, agent.history.value.size)
        } finally { client.close() }
    }

    @Test
    fun connectionCheckRequiresDownloadedModel() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/tags", request.url.encodedPath)
            respond("""{"models":[{"name":"another:9b"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine)
        try {
            assertContains(assertFailsWith<LlmException> { client.checkConnection() }.message.orEmpty(),
                "ollama pull qwen3.5:9b")
        } finally { client.close() }
    }

    @Test
    fun failedOrIncompleteRepliesDoNotEnterAgentHistory() = runTest {
        for (response in listOf(
            """{"error":"model not found"}""" to HttpStatusCode.NotFound,
            """{"message":{"role":"assistant","content":""},"done":true}""" to HttpStatusCode.OK,
            """{"message":{"role":"assistant","content":"partial"},"done":false}""" to HttpStatusCode.OK,
        )) {
            val engine = MockEngine { respond(response.first, response.second,
                headersOf(HttpHeaders.ContentType, "application/json")) }
            val client = OllamaLlmClient("http://localhost:11434", httpEngine = engine)
            try {
                val agent = TalkLoopAgent(client, AgentConfig(model = DEFAULT_OLLAMA_MODEL,
                    systemPrompt = "Test", contextWindowTokens = OLLAMA_CONTEXT_WINDOW_TOKENS))
                assertFailsWith<LlmException> { agent.respond("Привет") }
                assertTrue(agent.history.value.isEmpty())
            } finally { client.close() }
        }
    }

    @Test
    fun cloudSelectionAndOverflowAreRejectedBeforeGeneration() = runTest {
        val engine = MockEngine { error("Запрос не должен отправляться") }
        val client = OllamaLlmClient("http://localhost:11434", contextWindowTokens = 256, httpEngine = engine)
        try {
            assertFailsWith<IllegalArgumentException> {
                client.answer(listOf(ChatMessage(true, "Привет")), ResponseSpec(model = "qwen:cloud"))
            }
            assertFailsWith<ContextWindowExceededException> {
                client.answer(listOf(ChatMessage(true, "А".repeat(200))), ResponseSpec(maxTokens = 50))
            }
        } finally { client.close() }
    }
}
