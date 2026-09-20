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

    private fun taskConfig() = AgentConfig(
        systemPrompt = "test",
        contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4),
    )
}
