package com.mau89.talkloop.cli

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.mau89.talkloop.llm.DEFAULT_MAX_TOKENS
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.RECAP_REQUEST
import com.mau89.talkloop.llm.TUTOR_SYSTEM_PROMPT
import java.io.File
import java.util.Properties

/**
 * Первый прогон разговорного формата TalkLoop: диалог с LLM-репетитором прямо в терминале.
 *
 * Цель шага — проверить сам формат на себе, а не заложить архитектуру.
 * Ничего из этого файла не обязано пережить проверку гипотезы.
 */

fun main() {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(
            """
            Не найден ключ Anthropic API. Любой из вариантов:
              export ANTHROPIC_API_KEY=sk-ant-...
              cp secrets.properties.example secrets.properties  # и вписать ключ, файл в .gitignore
            """.trimIndent()
        )
        return
    }

    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    val history = mutableListOf<Pair<Boolean, String>>() // true — реплика ученика

    println("TalkLoop. Говорим по-английски. /done — разбор ошибок и выход.\n")

    while (true) {
        print("you> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.isEmpty()) continue

        val ending = input == "/done"
        val message = if (ending) RECAP_REQUEST else input

        val reply = ask(client, history + (true to message))
        if (reply == null) {
            println("Не дошло до модели — попробуй ещё раз.\n")
            continue
        }

        println("\ntutor> $reply\n")
        history += true to message
        history += false to reply
        if (ending) break
    }
}

/** Один запрос в Messages API: вся история + новая реплика, обратно — текст ответа. */
private fun ask(client: AnthropicClient, turns: List<Pair<Boolean, String>>): String? {
    val params = MessageCreateParams.builder()
        .model(DEFAULT_MODEL)
        .maxTokens(DEFAULT_MAX_TOKENS.toLong())
        .system(TUTOR_SYSTEM_PROMPT)
    turns.forEach { (fromLearner, text) ->
        if (fromLearner) params.addUserMessage(text) else params.addAssistantMessage(text)
    }

    return try {
        client.messages().create(params.build())
            .content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim()
            .ifEmpty { null }
    } catch (e: AnthropicServiceException) {
        println("\nОшибка API: ${e.message}")
        null
    }
}

/** Ключ берём из окружения, иначе — из secrets.properties в корне репозитория (он в .gitignore). */
private fun readApiKey(): String? {
    System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }

    val file = File("secrets.properties")
    if (!file.exists()) return null
    val props = Properties().apply { file.inputStream().use { load(it) } }
    return props.getProperty("anthropic.api.key")?.takeIf { it.isNotBlank() }
}
