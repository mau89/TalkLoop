package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.LongTermMemoryKind
import com.mau89.talkloop.llm.MemoryWrite
import com.mau89.talkloop.llm.TalkLoopAgent
import kotlinx.coroutines.runBlocking

private val SYSTEM = """
    Ты помощник руководителя продукта.
    Отвечай кратко и используй только пользовательскую реплику и доступные слои памяти.
    В конце ответа перечисли, на какие записи памяти ты опирался.
""".trimIndent()

fun main() = runBlocking {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return@runBlocking
    }
    val agent = TalkLoopAgent(
        llmClient = AnthropicLlmClient(apiKey),
        config = AgentConfig(
            model = DEFAULT_MODEL,
            systemPrompt = SYSTEM,
            maxTokens = 500,
            temperature = 0.0,
            contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4),
        ),
    )

    println("День 11. Явная запись в три слоя памяти.\n")

    // Рабочий слой: только то, что нужно для текущего релиза.
    agent.startNewTask("План релиза TalkLoop")
    agent.remember(MemoryWrite.Working("deadline", "15 ноября"))
    agent.remember(MemoryWrite.Working("deliverable", "Android и iOS приложение"))

    // Long-term: пригодится и после завершения текущей задачи.
    agent.remember(
        MemoryWrite.LongTerm(LongTermMemoryKind.PROFILE, "answer_language", "русский")
    )
    agent.remember(
        MemoryWrite.LongTerm(LongTermMemoryKind.DECISION, "technology", "Kotlin Multiplatform")
    )
    agent.remember(
        MemoryWrite.LongTerm(
            LongTermMemoryKind.KNOWLEDGE,
            "analytics_policy",
            "стороннюю аналитику не подключаем",
        )
    )

    println("Слои перед ответом:")
    printLayers(agent)
    println("\nАгент: ${agent.respond("Составь краткий план текущего релиза.")}")
    println("Краткосрочная после хода: ${agent.history.value.size} сообщения")

    println("\nНачинаем новую задачу: short-term и working очищаются, long-term остаётся.")
    agent.startNewTask("Идея следующей версии")
    printLayers(agent)
    println(
        "\nАгент: " + agent.respond(
            "Какие мои постоянные настройки и решения ты помнишь? " +
                "Не переноси срок и состав прошлого релиза в новую задачу.",
        )
    )
}

private fun printLayers(agent: TalkLoopAgent) {
    println("  short-term: ${agent.history.value.map { it.text }}")
    println(
        "  working: task=${agent.workingMemory.value.taskName}, " +
            "items=${agent.workingMemory.value.items}"
    )
    println("  long-term: ${agent.longTermMemory.value.items}")
}
