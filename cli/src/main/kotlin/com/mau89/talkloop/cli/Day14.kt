package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ChatMessage
import com.mau89.talkloop.llm.FOOD_ASSISTANT_INVARIANTS
import com.mau89.talkloop.llm.InMemoryInvariantStore
import com.mau89.talkloop.llm.LlmAnswer
import com.mau89.talkloop.llm.LlmClient
import com.mau89.talkloop.llm.ResponseSpec
import com.mau89.talkloop.llm.TalkLoopAgent
import kotlinx.coroutines.runBlocking

private val DAY_14_SYSTEM = """
    Ты кулинарный ассистент. Предлагай понятные домашние рецепты, совместимые
    с обязательными правилами пользователя.
""".trimIndent()

fun main(args: Array<String>) = runBlocking {
    val invariantStore = InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS)
    println("День 14. Инварианты находятся в отдельном хранилище:")
    invariantStore.load().forEach { println("- [${it.id}] ${it.statement}") }

    val guardedAgent = TalkLoopAgent(
        llmClient = FailIfCalledLlmClient,
        config = AgentConfig(systemPrompt = DAY_14_SYSTEM),
        invariantStore = invariantStore,
    )
    val conflict = "Добавь арахисовую пасту в соус."
    println("\nКонфликтный запрос: $conflict")
    println("Ответ:\n${guardedAgent.respond(conflict)}")
    println("Проверка: ${guardedAgent.lastInvariantCheck.value}")

    if ("--conflict-only" in args) return@runBlocking

    val apiKey = readApiKey()
    if (apiKey == null) {
        println("\nСовместимый сетевой пример пропущен. $MISSING_KEY_HINT")
        return@runBlocking
    }
    val compatibleAgent = TalkLoopAgent(
        llmClient = AnthropicLlmClient(apiKey),
        config = AgentConfig(systemPrompt = DAY_14_SYSTEM, temperature = 0.0),
        invariantStore = invariantStore,
    )
    val compatible = "Предложи простой овощной ужин из продуктов обычного магазина."
    println("\nСовместимый запрос: $compatible")
    println("Ответ:\n${compatibleAgent.respond(compatible)}")
    println("Проверка: ${compatibleAgent.lastInvariantCheck.value}")
}

private data object FailIfCalledLlmClient : LlmClient {
    override suspend fun reply(history: List<ChatMessage>): String =
        error("Конфликт должен быть отклонён до LLM")

    override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer =
        error("Конфликт должен быть отклонён до LLM")

    override suspend fun countInputTokens(
        history: List<ChatMessage>,
        spec: ResponseSpec,
    ): Int = error("Конфликт должен быть отклонён до LLM")
}
