package com.mau89.talkloop.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals(2, agent.statistics.value.requestCount)
        assertEquals(4, agent.statistics.value.inputTokens)
        assertEquals(6, agent.statistics.value.outputTokens)
        assertEquals(2, agent.statistics.value.turns.size)
    }

    @Test
    fun `новый экземпляр агента продолжает сохранённый до перезапуска диалог`() = runTest {
        val storage = MapStringStore()
        val firstClient = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf("Приятно познакомиться, Маша!")),
        )
        val firstLaunch = TalkLoopAgent(
            firstClient,
            historyStore = JsonChatHistoryStore(storage),
        )
        firstLaunch.respond("Меня зовут Маша")

        val secondClient = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf("Тебя зовут Маша.")),
        )
        val afterRestart = TalkLoopAgent(
            secondClient,
            historyStore = JsonChatHistoryStore(storage),
        )

        assertEquals("Тебя зовут Маша.", afterRestart.respond("Как меня зовут?"))
        assertEquals(
            listOf(
                ChatMessage(fromUser = true, text = "Меня зовут Маша"),
                ChatMessage(fromUser = false, text = "Приятно познакомиться, Маша!"),
                ChatMessage(fromUser = true, text = "Как меня зовут?"),
            ),
            secondClient.requests.single(),
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

    @Test
    fun `день 8 считает текущий запрос всю историю ответ и стоимость`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(
                listOf(
                    LlmAnswer("one", "end_turn", null, inputTokens = 30, outputTokens = 5),
                    LlmAnswer(
                        text = "two",
                        stopReason = "end_turn",
                        stopSequence = null,
                        inputTokens = 10,
                        outputTokens = 7,
                        cacheCreationInputTokens = 20,
                        cacheReadInputTokens = 30,
                        cacheCreation5mInputTokens = 20,
                    ),
                )
            ),
            tokenCounter = { history, spec ->
                history.sumOf { it.text.length } + if (spec.system == null) 0 else 20
            },
        )
        val agent = TalkLoopAgent(client)

        agent.respond("short")
        agent.respond("a much longer request")

        val first = agent.statistics.value.turns[0]
        val second = agent.statistics.value.turns[1]
        assertEquals(5, first.requestTokens)
        assertEquals(30, first.inputTokens)
        assertEquals(5, first.outputTokens)
        assertEquals(21, second.requestTokens)
        assertEquals(60, second.inputTokens)
        assertEquals(7, second.outputTokens)
        assertTrue(second.inputTokens > first.inputTokens)
        assertEquals(90, agent.statistics.value.inputTokens)
        assertEquals(12, agent.statistics.value.outputTokens)
        // Haiku: base input $1/MTok, 5m cache write x1.25, cache read x0.1,
        // output $5/MTok. Полный вход при этом всё равно включает все три части usage.
        assertEquals(0.000128, agent.statistics.value.totalCostUsd, absoluteTolerance = 0.0000001)
    }

    @Test
    fun `переполнение видно до вызова модели и не портит историю`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf("не должен быть вызван")),
            tokenCounter = { history, spec ->
                if (spec.system == null) 8 else history.sumOf { it.text.length } + 20
            },
        )
        val agent = TalkLoopAgent(
            llmClient = client,
            config = AgentConfig(
                systemPrompt = "test",
                contextWindowTokens = 25,
            ),
        )

        val error = assertFailsWith<ContextWindowExceededException> {
            agent.respond("1234567890")
        }

        assertEquals(30, error.inputTokens)
        assertEquals(emptyList(), client.requests)
        assertEquals(emptyList(), agent.history.value)
        assertEquals(TokenTurnOutcome.REJECTED_BEFORE_SEND, agent.statistics.value.lastTurn?.outcome)
        assertEquals(0, agent.statistics.value.inputTokens)
        assertEquals(0.0, agent.statistics.value.totalCostUsd)
    }

    @Test
    fun `ответ оборванный окном контекста оплачивается но не сохраняется`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(
                listOf(
                    LlmAnswer(
                        text = "неполный",
                        stopReason = "model_context_window_exceeded",
                        stopSequence = null,
                        inputTokens = 80,
                        outputTokens = 20,
                    )
                )
            ),
            tokenCounter = { _, spec -> if (spec.system == null) 4 else 80 },
        )
        val agent = TalkLoopAgent(
            llmClient = client,
            config = AgentConfig(systemPrompt = "test", contextWindowTokens = 100),
        )

        assertFailsWith<ContextWindowExceededException> { agent.respond("test") }

        assertEquals(emptyList(), agent.history.value)
        assertEquals(100, agent.statistics.value.totalTokens)
        assertEquals(
            TokenTurnOutcome.RESPONSE_REACHED_CONTEXT_LIMIT,
            agent.statistics.value.lastTurn?.outcome,
        )
    }

    @Test
    fun `день 9 заменяет старые сообщения summary и оставляет последние N дословно`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(
                listOf(
                    "Ответ 1",
                    "Ответ 2",
                    LlmAnswer(
                        text = "Пользователя зовут Маша.",
                        stopReason = "end_turn",
                        stopSequence = null,
                        inputTokens = 40,
                        outputTokens = 8,
                    ),
                    "Тебя зовут Маша.",
                    "Пользователя зовут Маша, он просил это запомнить.",
                )
            ),
        )
        val agent = TalkLoopAgent(
            llmClient = client,
            config = AgentConfig(
                systemPrompt = "test-system",
                contextCompression = ContextCompressionConfig(
                    enabled = true,
                    keepLastMessages = 2,
                    summaryMaxTokens = 100,
                ),
            ),
        )

        agent.respond("Меня зовут Маша")
        agent.respond("Запомни это")

        assertEquals("Пользователя зовут Маша.", agent.summary.value)
        assertEquals(
            listOf(
                ChatMessage(true, "Запомни это"),
                ChatMessage(false, "Ответ 2"),
            ),
            agent.history.value,
        )
        assertEquals(1, agent.statistics.value.compression.compressionCount)
        assertEquals(2, agent.statistics.value.compression.compressedMessages)
        assertEquals(40, agent.statistics.value.compression.summaryInputTokens)
        assertEquals(8, agent.statistics.value.compression.summaryOutputTokens)
        assertEquals(45, agent.statistics.value.compression.latestBeforeTokens)
        assertEquals(23, agent.statistics.value.compression.latestAfterTokens)
        assertEquals(22, agent.statistics.value.compression.latestSavedTokens)
        assertEquals(58, agent.statistics.value.allTokens)

        assertEquals("Тебя зовут Маша.", agent.respond("Как меня зовут?"))
        assertEquals(
            listOf(
                ChatMessage(true, "Запомни это"),
                ChatMessage(false, "Ответ 2"),
                ChatMessage(true, "Как меня зовут?"),
            ),
            client.requests[3],
        )
        assertTrue(client.specs[3].system.orEmpty().contains("Пользователя зовут Маша."))
    }

    @Test
    fun `summary и свежий хвост восстанавливаются после перезапуска`() = runTest {
        val storage = MapStringStore()
        val config = AgentConfig(
            systemPrompt = "test-system",
            contextCompression = ContextCompressionConfig(
                enabled = true,
                keepLastMessages = 2,
            ),
        )
        val firstClient = FakeLlmClient(
            responses = ArrayDeque<Any>(
                listOf("A1", "A2", "Сжатая память"),
            ),
        )
        val first = TalkLoopAgent(
            firstClient,
            config,
            historyStore = JsonChatHistoryStore(storage),
        )
        first.respond("U1")
        first.respond("U2")

        val secondClient = FakeLlmClient(
            ArrayDeque<Any>(listOf("A3", "Обновлённая сжатая память")),
        )
        val afterRestart = TalkLoopAgent(
            secondClient,
            config,
            historyStore = JsonChatHistoryStore(storage),
        )

        assertEquals("Сжатая память", afterRestart.summary.value)
        afterRestart.respond("U3")

        assertEquals("Обновлённая сжатая память", afterRestart.summary.value)
        assertEquals(listOf("U2", "A2", "U3"), secondClient.requests.first().map { it.text })
        assertTrue(secondClient.specs.first().system.orEmpty().contains("Сжатая память"))
    }

    @Test
    fun `сбой summarizer не отменяет готовый ответ и оставляет полную историю`() = runTest {
        val client = FakeLlmClient(
            responses = ArrayDeque<Any>(listOf("A1", "A2", LlmException("summary offline"))),
        )
        val agent = TalkLoopAgent(
            llmClient = client,
            config = AgentConfig(
                systemPrompt = "test",
                contextCompression = ContextCompressionConfig(
                    enabled = true,
                    keepLastMessages = 2,
                ),
            ),
        )

        agent.respond("U1")
        assertEquals("A2", agent.respond("U2"))

        assertEquals(listOf("U1", "A1", "U2", "A2"), agent.history.value.map { it.text })
        assertEquals(null, agent.summary.value)
        assertEquals(0, agent.statistics.value.compression.compressionCount)
        assertEquals("summary offline", agent.statistics.value.compression.lastError)
    }
}

private class FakeLlmClient(
    private val responses: ArrayDeque<Any>,
    private val tokenCounter: (List<ChatMessage>, ResponseSpec) -> Int = { history, spec ->
        history.sumOf { it.text.length } + if (spec.system == null) 0 else 5
    },
) : LlmClient {
    val requests = mutableListOf<List<ChatMessage>>()
    val specs = mutableListOf<ResponseSpec>()

    override suspend fun reply(history: List<ChatMessage>): String =
        answer(history, ResponseSpec()).text

    override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
        requests += history
        specs += spec
        return when (val response = responses.removeFirst()) {
            is Throwable -> throw response
            is LlmAnswer -> response
            else -> LlmAnswer(
                text = response as String,
                stopReason = "end_turn",
                stopSequence = null,
                inputTokens = 2,
                outputTokens = 3,
            )
        }
    }

    override suspend fun countInputTokens(
        history: List<ChatMessage>,
        spec: ResponseSpec,
    ): Int = tokenCounter(history, spec)
}
