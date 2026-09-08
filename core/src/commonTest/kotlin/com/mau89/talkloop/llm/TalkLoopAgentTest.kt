package com.mau89.talkloop.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TalkLoopAgentTest {

    @Test
    fun `агент отправляет запрос и сохраняет контекст между вызовами`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf("Hello!", "I am fine.")),
        )
        val agent = TalkLoopAgent(client)

        assertEquals("Hello!", agent.respond("Hi"))
        assertEquals("I am fine.", agent.respond("How are you?"))

        assertEquals(
            listOf(
                ChatMessage(fromUser = true, text = "Hi"),
            ),
            client.requests[0],
        )
        assertEquals(
            listOf(
                ChatMessage(fromUser = true, text = "Hi"),
                ChatMessage(fromUser = false, text = "Hello!"),
                ChatMessage(fromUser = true, text = "How are you?"),
            ),
            client.requests[1],
        )
        assertEquals(
            AgentStatistics(
                requestCount = 2,
                inputTokens = 4,
                outputTokens = 6,
            ),
            agent.statistics.value,
        )
    }

    @Test
    fun `агент не добавляет неуспешный запрос в историю`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf(LlmException("API недоступен"), "Recovered")),
        )
        val agent = TalkLoopAgent(client)

        assertFailsWith<LlmException> { agent.respond("First") }
        assertEquals("Recovered", agent.respond("Second"))
        assertEquals(
            listOf(ChatMessage(fromUser = true, text = "Second")),
            client.requests[1],
        )
    }

    @Test
    fun `политики преобразуют данные а judge проверяет итог`() = runTest {
        var judged: JudgeContext? = null
        val client = FakeLlmClient(ArrayDeque<Any>(listOf("  черновик  ")))
        val config = AgentConfig(
            model = "test-model",
            systemPrompt = "test-system",
            inputPolicies = listOf(
                NonBlankInputPolicy,
                InputPolicy { input, _ -> input.uppercase() },
            ),
            outputPolicies = listOf(
                NonBlankOutputPolicy,
                OutputPolicy { output, _ -> "$output!" },
            ),
            judge = AgentJudge { context ->
                judged = context
                JudgeVerdict(accepted = true)
            },
        )

        val result = TalkLoopAgent(client, config).respond("  привет  ")

        assertEquals("черновик!", result)
        assertEquals("ПРИВЕТ", client.requests.single().single().text)
        assertEquals("test-model", client.specs.single().model)
        assertEquals("test-system", client.specs.single().system)
        assertEquals("ПРИВЕТ", judged?.request)
        assertEquals("черновик!", judged?.response)
    }

    @Test
    fun `judge может отклонить ответ до сохранения в историю`() = runTest {
        val client = FakeLlmClient(
            ArrayDeque<Any>(listOf("опасный ответ", "безопасный ответ")),
        )
        var judgeCalls = 0
        val agent = TalkLoopAgent(
            llmClient = client,
            config = AgentConfig(
                systemPrompt = "test",
                judge = AgentJudge {
                    judgeCalls++
                    JudgeVerdict(
                        accepted = judgeCalls > 1,
                        reason = "Ответ не прошёл проверку",
                    )
                },
            ),
        )

        assertFailsWith<AgentRejectedException> { agent.respond("Первый") }
        assertEquals("безопасный ответ", agent.respond("Второй"))
        assertEquals(
            listOf(ChatMessage(fromUser = true, text = "Второй")),
            client.requests[1],
        )
    }

    @Test
    fun `один runtime создаёт сто независимо настроенных агентов`() = runTest {
        val client = FakeLlmClient(ArrayDeque(List<Any>(100) { "ok" }))
        val runtime = AgentRuntime(client)
        val agents = runtime.spawn(
            List(100) { index ->
                AgentConfig(
                    model = "model-$index",
                    systemPrompt = "agent-$index",
                )
            }
        )

        agents.forEachIndexed { index, agent ->
            assertEquals("ok", agent.respond("request-$index"))
        }

        assertEquals(100, client.requests.size)
        assertEquals(100, runtime.agentCount.value)
        assertEquals((0 until 100).map { "model-$it" }, client.specs.map { it.model })
        assertEquals((0 until 100).map { "agent-$it" }, client.specs.map { it.system })
    }
}

private class FakeLlmClient(
    private val responses: ArrayDeque<Any>,
) : LlmClient {
    val requests = mutableListOf<List<ChatMessage>>()
    val specs = mutableListOf<ResponseSpec>()

    override suspend fun reply(history: List<ChatMessage>): String =
        answer(history, ResponseSpec()).text

    override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
        requests += history
        specs += spec
        val text = when (val response = responses.removeFirst()) {
            is Throwable -> throw response
            else -> response as String
        }
        return LlmAnswer(
            text = text,
            stopReason = "end_turn",
            stopSequence = null,
            inputTokens = 2,
            outputTokens = 3,
        )
    }
}
