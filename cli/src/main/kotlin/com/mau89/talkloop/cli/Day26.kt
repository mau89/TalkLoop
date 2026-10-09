package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

/** The same LlmClient and agent as the app, with no Anthropic key. */
fun main(args: Array<String>) = runBlocking {
    val address = args.getOrNull(0) ?: "http://127.0.0.1:11434"
    val model = args.getOrNull(1) ?: DEFAULT_OLLAMA_MODEL
    val client = OllamaLlmClient(address, model)
    val prompts = listOf(
        "Простой" to "Переведи на русский: I am learning English.",
        "Средний" to "Исправь фразу «Yesterday I go to school» и объясни по-русски, почему она неправильная.",
        "Сложный" to "Составь план изучения английского на 7 дней для уровня A2. У меня 20 минут в день. На каждый день дай упражнение, пример и способ проверить результат.",
    )
    try {
        println(client.checkConnection())
        val results = prompts.map { (level, prompt) ->
            // Independent tasks avoid carrying an earlier task into the harder prompt.
            val agent = TalkLoopAgent(client, AgentConfig(model = model,
                systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT, maxTokens = 2400,
                contextWindowTokens = OLLAMA_CONTEXT_WINDOW_TOKENS,
                contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
                contextCompression = ContextCompressionConfig(enabled = false)))
            val started = System.nanoTime()
            val answer = agent.respond(prompt)
            val seconds = (System.nanoTime() - started) / 1e9
            val usage = agent.statistics.value.lastTurn ?: error("Нет метрик ответа")
            check(usage.stopReason != "max_tokens") { "Ответ оборван лимитом генерации" }
            println("\n$level запрос: $prompt\nОтвет: $answer\nВремя: ${"%.1f".format(seconds)} с")
            buildJsonObject {
                put("complexity", level); put("prompt", prompt); put("answer", answer)
                put("seconds", seconds); put("inputTokens", usage.inputTokens); put("outputTokens", usage.outputTokens)
                put("stopReason", usage.stopReason)
            }
        }
        val report = buildJsonObject {
            put("date", OffsetDateTime.now(ZoneId.of("Asia/Yekaterinburg")).toString())
            put("provider", "Ollama local HTTP API"); put("address", address); put("model", model)
            put("agent", "TalkLoopAgent"); put("contextWindow", OLLAMA_CONTEXT_WINDOW_TOKENS)
            put("preflightTokens", "conservative UTF-8 byte estimate; usage from Ollama response")
            put("results", JsonArray(results))
        }
        File("docs/day26-local-llm.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("\nРезультаты сохранены в docs/day26-local-llm.json")
    } finally { client.close() }
}
