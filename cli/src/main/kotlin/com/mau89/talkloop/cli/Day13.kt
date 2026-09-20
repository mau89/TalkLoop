package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.InMemoryChatHistoryStore
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.TaskActor
import com.mau89.talkloop.llm.TaskStage
import kotlinx.coroutines.runBlocking

private val DAY_13_SYSTEM = """
    Ты агент выпуска мобильного приложения. Работай строго с текущей точки задачи,
    не повторяй уже завершённые объяснения и предлагай только следующее действие.
""".trimIndent()

fun main() = runBlocking {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return@runBlocking
    }

    val store = InMemoryChatHistoryStore()
    val config = AgentConfig(
        model = DEFAULT_MODEL,
        systemPrompt = DAY_13_SYSTEM,
        maxTokens = 500,
        temperature = 0.0,
        contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4),
    )
    val client = AnthropicLlmClient(apiKey)
    val beforePause = TalkLoopAgent(client, config, historyStore = store)

    beforePause.beginTask(
        name = "Подготовить релиз TalkLoop",
        currentStep = "Составить план релиза",
        expectedAction = "Утвердить обязательные проверки",
        expectedActor = TaskActor.USER,
        completionCriteria = listOf(
            "Список платформ согласован",
            "Smoke-тесты перечислены",
        ),
    )
    beforePause.transitionTask(
        nextStage = TaskStage.EXECUTION,
        currentStep = "Собрать release-кандидат",
        expectedAction = "Запустить smoke-тесты Android и iOS",
    )
    beforePause.pauseTask()
    println("День 13. Состояние перед перезапуском: ${beforePause.taskState.value}")

    // Новый экземпляр имитирует перезапуск приложения и загружает тот же снимок.
    val afterRestart = TalkLoopAgent(client, config, historyStore = store)
    println("Восстановлено после перезапуска: ${afterRestart.taskState.value}")
    afterRestart.resumeTask()
    println("\nАгент после продолжения без повторного объяснения:")
    println(afterRestart.respond("Release-кандидат собран. Продолжай."))

    afterRestart.transitionTask(
        nextStage = TaskStage.VALIDATION,
        currentStep = "Проверить результаты smoke-тестов",
        expectedAction = "Подтвердить отсутствие блокирующих дефектов",
    )
    afterRestart.transitionTask(
        nextStage = TaskStage.DONE,
        currentStep = "Релиз проверен",
        expectedAction = "Зафиксировать результат",
    )
    println("\nФинальное состояние: ${afterRestart.taskState.value}")
    println("Событий в журнале: ${afterRestart.taskState.value?.transitionHistory?.size}")
}
