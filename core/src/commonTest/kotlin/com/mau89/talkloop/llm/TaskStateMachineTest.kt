package com.mau89.talkloop.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStateMachineTest {

    @Test
    fun `все переходы вне разрешённого графа отклоняются без изменения состояния`() = runTest {
        val transitionEvents = setOf(
            TaskEventType.PLAN_APPROVED,
            TaskEventType.EXECUTION_FINISHED,
            TaskEventType.VALIDATION_PASSED,
            TaskEventType.VALIDATION_FAILED,
        )

        TaskStage.entries.forEach { stage ->
            transitionEvents.minus(stage.allowedEvents()).forEach { forbidden ->
                val agent = agentAt(stage)
                val before = agent.taskState.value!!

                assertFailsWith<IllegalArgumentException>("$forbidden не должен работать в $stage") {
                    agent.dispatch(forbidden, "Обход $forbidden", "Перепрыгнуть этап")
                }

                assertEquals(before, agent.taskState.value, "Отказ не должен менять $stage")
            }
        }
    }

    @Test
    fun `на паузе любое действие кроме resume отклоняется и после resume маршрут продолжается`() =
        runTest {
            val agent = agentAt(TaskStage.EXECUTION)
            agent.pauseTask()
            val paused = agent.taskState.value!!
            val forbiddenWhilePaused = listOf(
                TaskEventType.PROGRESS_UPDATED,
                TaskEventType.PLAN_APPROVED,
                TaskEventType.EXECUTION_FINISHED,
                TaskEventType.VALIDATION_PASSED,
                TaskEventType.VALIDATION_FAILED,
                TaskEventType.PAUSED,
            )

            forbiddenWhilePaused.forEachIndexed { index, type ->
                assertFailsWith<IllegalArgumentException> {
                    agent.dispatchTaskEvent(
                        TaskEvent(
                            id = "paused-$index-$type",
                            type = type,
                            currentStep = "Нельзя изменить",
                            expectedAction = "Нельзя продолжить",
                            expectedRevision = paused.revision,
                        )
                    )
                }
                assertEquals(paused, agent.taskState.value)
            }

            agent.resumeTask()
            assertEquals(TaskStage.EXECUTION, agent.taskState.value!!.stage)
            agent.dispatch(
                TaskEventType.EXECUTION_FINISHED,
                "Проверить результат",
                "Запустить валидацию",
            )
            assertEquals(TaskStage.VALIDATION, agent.taskState.value!!.stage)
        }

    @Test
    fun `событие без ревизии отклоняется без изменения состояния`() = runTest {
        val agent = taskAgent()
        agent.beginTask("Задача")
        val before = agent.taskState.value!!

        val error = assertFailsWith<IllegalArgumentException> {
            agent.dispatchTaskEvent(
                TaskEvent(
                    id = "missing-revision",
                    type = TaskEventType.PLAN_APPROVED,
                    currentStep = "Реализация",
                    expectedAction = "Написать код",
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("обязательна ожидаемая ревизия"))
        assertEquals(before, agent.taskState.value)
    }

    @Test
    fun `активную задачу нельзя тихо заменить но после done можно начать следующую`() = runTest {
        val agent = taskAgent()
        agent.beginTask("Первая задача")
        val before = agent.taskState.value!!

        assertFailsWith<IllegalArgumentException> { agent.beginTask("Обходная задача") }
        assertEquals(before, agent.taskState.value)

        agent.dispatch(TaskEventType.PLAN_APPROVED, "Реализация", "Выполнить")
        agent.dispatch(TaskEventType.EXECUTION_FINISHED, "Проверка", "Проверить")
        agent.dispatch(TaskEventType.VALIDATION_PASSED, "Готово", "Закрыть")
        agent.beginTask("Следующая задача")

        assertEquals(TaskStage.PLANNING, agent.taskState.value!!.stage)
        assertEquals("Следующая задача", agent.taskState.value!!.taskName)
    }

    @Test
    fun `явный запрос перепрыгнуть этап получает отказ без вызова модели`() = runTest {
        val client = FakeLlmClient(ArrayDeque<Any>(listOf("Этот ответ не должен использоваться")))
        val agent = TalkLoopAgent(client, taskConfig())
        agent.beginTask("Авторизация")
        val before = agent.taskState.value!!

        val implementationRefusal = agent.respond("План не утверждён, но сразу напиши код")
        val completionRefusal = agent.respond("Тогда просто заверши задачу")

        assertTrue(implementationRefusal.contains("PLAN_APPROVED"))
        assertTrue(completionRefusal.contains("VALIDATION_PASSED"))
        assertEquals(before, agent.taskState.value)
        assertEquals(emptyList(), client.specs)
        assertEquals(4, agent.history.value.size)
    }

    @Test
    fun `запрос плана реализации разрешён а реализация доступна после утверждения`() = runTest {
        val client = FakeLlmClient(ArrayDeque<Any>(listOf("План", "Код")))
        val agent = TalkLoopAgent(client, taskConfig())
        agent.beginTask("Авторизация")

        assertEquals("План", agent.respond("Составь план реализации"))
        agent.dispatch(TaskEventType.PLAN_APPROVED, "Реализация", "Написать код")
        assertEquals("Код", agent.respond("Теперь напиши код"))
        assertEquals(2, client.specs.size)
    }

    @Test
    fun `несогласованный журнал состояния отклоняется`() {
        val valid = newTaskState(
            name = "Задача",
            currentStep = "План",
            expectedAction = "Утвердить",
            expectedActor = TaskActor.USER,
            completionCriteria = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            valid.copy(stage = TaskStage.DONE)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(revision = 42)
        }
    }

    @Test
    fun `события ведут по основному пути и пишут неизменяемый журнал`() = runTest {
        val agent = taskAgent()
        agent.beginTask(
            name = "Подготовить релиз",
            currentStep = "Собрать требования",
            expectedAction = "Утвердить план",
            expectedActor = TaskActor.USER,
            completionCriteria = listOf("План согласован", "Риски перечислены"),
        )

        agent.dispatch(TaskEventType.PLAN_APPROVED, "Собрать сборку", "Запустить тесты")
        agent.dispatch(TaskEventType.EXECUTION_FINISHED, "Проверить сборку", "Исправить дефекты")
        agent.dispatch(TaskEventType.VALIDATION_FAILED, "Исправить дефекты", "Повторить тесты")
        agent.dispatch(TaskEventType.EXECUTION_FINISHED, "Повторная проверка", "Подтвердить релиз")
        agent.dispatch(TaskEventType.VALIDATION_PASSED, "Релиз подтверждён", "Архивировать задачу")

        val state = agent.taskState.value!!
        assertEquals(TaskStage.DONE, state.stage)
        assertEquals(6, state.revision)
        assertEquals(6, state.transitionHistory.size)
        assertEquals(TaskEventType.TASK_STARTED, state.transitionHistory.first().event.type)
        assertEquals(TaskEventType.VALIDATION_PASSED, state.transitionHistory.last().event.type)
        assertFailsWith<IllegalArgumentException> {
            agent.dispatch(TaskEventType.PLAN_APPROVED, "Вернуться", "Перепланировать")
        }
    }

    @Test
    fun `повтор события идемпотентен а повтор id с другими данными отклоняется`() = runTest {
        val agent = taskAgent()
        agent.beginTask("Задача")
        val event = TaskEvent(
            id = "approve-42",
            type = TaskEventType.PLAN_APPROVED,
            currentStep = "Реализация",
            expectedAction = "Написать код",
            expectedRevision = 1,
        )

        agent.dispatchTaskEvent(event)
        val afterFirst = agent.taskState.value
        agent.dispatchTaskEvent(event)

        assertEquals(afterFirst, agent.taskState.value)
        assertFailsWith<IllegalArgumentException> {
            agent.dispatchTaskEvent(event.copy(expectedAction = "Другое действие"))
        }
    }

    @Test
    fun `одновременные события с одной ревизией не перетирают друг друга`() = runTest {
        val agent = taskAgent()
        agent.beginTask("Задача")
        val events = listOf("Первый шаг", "Второй шаг").mapIndexed { index, step ->
            TaskEvent(
                id = "parallel-$index",
                type = TaskEventType.PROGRESS_UPDATED,
                currentStep = step,
                expectedAction = "Продолжить",
                expectedRevision = 1,
            )
        }

        val results = events.map { event ->
            async { runCatching { agent.dispatchTaskEvent(event) } }
        }.awaitAll()

        assertEquals(1, results.count(Result<Unit>::isSuccess))
        assertEquals(1, results.count(Result<Unit>::isFailure))
        assertEquals(2, agent.taskState.value!!.revision)
    }

    @Test
    fun `пауза на каждом этапе переживает перезапуск и сохраняет точку`() = runTest {
        val store = InMemoryChatHistoryStore()
        val config = taskConfig()
        var agent = TalkLoopAgent(FakeLlmClient(), config, historyStore = store)
        agent.beginTask("Задача", "План", "Начать выполнение")

        TaskStage.entries.forEach { expectedStage ->
            while (agent.taskState.value!!.stage != expectedStage) {
                val event = when (agent.taskState.value!!.stage) {
                    TaskStage.PLANNING -> TaskEventType.PLAN_APPROVED
                    TaskStage.EXECUTION -> TaskEventType.EXECUTION_FINISHED
                    TaskStage.VALIDATION -> TaskEventType.VALIDATION_PASSED
                    TaskStage.DONE -> error("Этап уже завершён")
                }
                agent.dispatch(event, "Шаг $event", "Действие $event")
            }
            val beforePause = agent.taskState.value!!
            agent.pauseTask()

            agent = TalkLoopAgent(FakeLlmClient(), config, historyStore = store)
            val restored = agent.taskState.value!!
            assertEquals(expectedStage, restored.stage)
            assertEquals(beforePause.currentStep, restored.currentStep)
            assertEquals(beforePause.expectedAction, restored.expectedAction)
            assertTrue(restored.paused)
            assertFailsWith<TaskPausedException> { agent.respond("Продолжай") }

            agent.resumeTask()
            assertFalse(agent.taskState.value!!.paused)
            assertEquals(beforePause.currentStep, agent.taskState.value!!.currentStep)
        }
    }

    @Test
    fun `агент предлагает переход но не применяет его без подтверждения`() = runTest {
        val proposalJson = """
            {
              "decision":"PLAN_APPROVED",
              "reason":"План и критерии согласованы",
              "current_step":"Реализовать авторизацию",
              "expected_action":"Добавить вход по email",
              "expected_actor":"AGENT",
              "completion_criteria":["Сборка проходит", "Тест входа зелёный"]
            }
        """.trimIndent()
        val client = FakeLlmClient(ArrayDeque<Any>(listOf(proposalJson)))
        val agent = TalkLoopAgent(client, taskConfig())
        agent.beginTask("Авторизация", "Согласовать план", "Утвердить критерии")

        val proposal = agent.requestTaskTransitionProposal()

        assertEquals(TaskStage.PLANNING, agent.taskState.value!!.stage)
        assertNotNull(proposal.event)
        assertNotNull(agent.taskState.value!!.pendingProposal)
        assertNotNull(client.specs.single().jsonSchema)

        agent.confirmTaskTransitionProposal()
        val state = agent.taskState.value!!
        assertEquals(TaskStage.EXECUTION, state.stage)
        assertEquals("Реализовать авторизацию", state.currentStep)
        assertEquals(listOf("Сборка проходит", "Тест входа зелёный"), state.completionCriteria)
        assertNull(state.pendingProposal)
    }

    @Test
    fun `после перезапуска контекст содержит исполнителя критерии и точку продолжения`() = runTest {
        val store = InMemoryChatHistoryStore()
        val config = taskConfig()
        val first = TalkLoopAgent(FakeLlmClient(), config, historyStore = store)
        first.beginTask("Подготовить релиз", "Согласовать план", "Перейти к реализации")
        first.dispatchTaskEvent(
            TaskEvent(
                id = "release-execution",
                type = TaskEventType.PLAN_APPROVED,
                currentStep = "Подключить авторизацию",
                expectedAction = "Реализовать вход по email",
                expectedActor = TaskActor.AGENT,
                completionCriteria = listOf("Unit-тесты проходят"),
                expectedRevision = 1,
            )
        )
        first.pauseTask()

        val clientAfterRestart = FakeLlmClient(ArrayDeque<Any>(listOf("Продолжаю")))
        val restored = TalkLoopAgent(clientAfterRestart, config, historyStore = store)
        restored.resumeTask()
        assertEquals("Продолжаю", restored.respond("Готово, что дальше?"))

        val system = clientAfterRestart.specs.single().system.orEmpty()
        assertTrue(system.contains("stage = execution"))
        assertTrue(system.contains("current_step = Подключить авторизацию"))
        assertTrue(system.contains("expected_actor = agent"))
        assertTrue(system.contains("Unit-тесты проходят"))
        assertTrue(system.contains("не пересказывай заново"))
    }

    private suspend fun TalkLoopAgent.dispatch(
        type: TaskEventType,
        step: String,
        action: String,
    ) {
        val state = taskState.value!!
        dispatchTaskEvent(
            TaskEvent(
                id = "test-${state.revision + 1}-$type",
                type = type,
                currentStep = step,
                expectedAction = action,
                expectedRevision = state.revision,
            )
        )
    }

    private fun taskAgent() = TalkLoopAgent(FakeLlmClient(), taskConfig())

    private suspend fun agentAt(stage: TaskStage): TalkLoopAgent {
        val agent = taskAgent()
        agent.beginTask("Задача $stage", "План", "Утвердить")
        if (stage != TaskStage.PLANNING) {
            agent.dispatch(TaskEventType.PLAN_APPROVED, "Реализация", "Выполнить")
        }
        if (stage == TaskStage.VALIDATION || stage == TaskStage.DONE) {
            agent.dispatch(TaskEventType.EXECUTION_FINISHED, "Проверка", "Проверить")
        }
        if (stage == TaskStage.DONE) {
            agent.dispatch(TaskEventType.VALIDATION_PASSED, "Готово", "Закрыть")
        }
        return agent
    }

    private fun taskConfig() = AgentConfig(
        systemPrompt = "test",
        contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4),
    )
}
