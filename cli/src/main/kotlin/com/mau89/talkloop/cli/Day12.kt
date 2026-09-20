package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.AssistantPreferences
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.UserProfile
import kotlinx.coroutines.runBlocking

private val DAY_12_SYSTEM = """
    Ты продуктовый консультант. Дай практичный ответ на запрос пользователя.
    Не описывай внутренние настройки и не сообщай, что получил профиль.
""".trimIndent()

private val DAY_12_PROFILES = listOf(
    UserProfile(
        id = "product-lead",
        displayName = "Руководитель продукта",
        preferences = AssistantPreferences(
            language = "русский",
            style = "кратко, уверенно, по делу",
            format = "не более трёх пунктов",
            constraints = listOf("без вводных фраз", "без эмодзи"),
        ),
    ),
    UserProfile(
        id = "junior-developer",
        displayName = "Начинающий разработчик",
        preferences = AssistantPreferences(
            language = "русский",
            style = "доброжелательно, простыми словами, с пояснением причин",
            format = "секции: объяснение, пример, следующий шаг",
            constraints = listOf(
                "не использовать термины без короткого объяснения",
                "общий ответ не длиннее 300 слов",
            ),
        ),
    ),
)

fun main() = runBlocking {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return@runBlocking
    }

    val client = AnthropicLlmClient(apiKey)
    println("День 12. Один запрос — разные профили.\n")
    val agent = TalkLoopAgent(
        llmClient = client,
        config = AgentConfig(
            model = DEFAULT_MODEL,
            systemPrompt = DAY_12_SYSTEM,
            maxTokens = 1_000,
            temperature = 0.0,
            contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 4),
        ),
    )
    DAY_12_PROFILES.forEach { agent.setUserProfile(it) }
    println("Сохранены профили: ${agent.userProfiles.value.joinToString { it.id }}\n")

    DAY_12_PROFILES.forEach { profile ->
        agent.startNewTask("Проверка профиля ${profile.id}")
        agent.selectUserProfile(profile.id)

        println("=== ${profile.displayName} (${profile.id}) ===")
        println(agent.respond("Предложи план внедрения push-уведомлений в мобильное приложение."))
        println("\nПовторно, без напоминания предпочтений:")
        println(agent.respond("Какие два риска проверить первыми?"))
        println()
    }
}
