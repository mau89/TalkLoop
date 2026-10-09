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
