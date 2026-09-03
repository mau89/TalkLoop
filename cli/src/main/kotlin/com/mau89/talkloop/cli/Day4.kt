package com.mau89.talkloop.cli

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.TEMPERATURE_LAB_MAX_TOKENS
import com.mau89.talkloop.llm.TEMPERATURE_LAB_PROMPT
import com.mau89.talkloop.llm.TEMPERATURE_PRESETS
import com.mau89.talkloop.llm.distinctCount
import com.mau89.talkloop.llm.diversityLabel
import com.mau89.talkloop.llm.effectiveTemperature
import com.mau89.talkloop.llm.temperatureWasClamped

/**
 * День 4. Температура.
 *
 * Один и тот же запрос уходит в модель с temperature = 0, 0.7 и 1.2.
 * Сравнение точности, креативности и разнообразия — на глаз, здесь только
 * ответы рядом и счётчик расхождения между прогонами.
 *
 * Запуск: ./gradlew :cli:day4 -q --console=plain
 *   число прогонов на температуру: --args=5
 */

private val DEFAULT_REPEATS = 3

fun main(args: Array<String>) {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return
    }

    val repeats = args.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_REPEATS
    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    println("День 4. Один запрос — три температуры. Модель: $DEFAULT_MODEL\n")
    println("ЗАПРОС (одинаковый во всех прогонах):")
    println(TEMPERATURE_LAB_PROMPT.prependIndent("  "))
    println("  прогонов на температуру: $repeats\n")

    val batches = TEMPERATURE_PRESETS.mapNotNull { temperature ->
        runBatch(client, temperature, repeats)
    }
    if (batches.size == TEMPERATURE_PRESETS.size) printComparison(batches)
}

private data class Run(
    val index: Int,
    val text: String,
    val outputTokens: Long,
)

private data class Batch(
    val temperature: Double,
    val runs: List<Run>,
)

private fun runBatch(client: AnthropicClient, temperature: Double, repeats: Int): Batch? {
    printHeader(temperature)

    val runs = buildList {
        repeat(repeats) { attempt ->
            val reply = ask(client, temperature) ?: return null
            val run = Run(
                index = attempt + 1,
                text = reply.text,
                outputTokens = reply.outputTokens,
            )
            add(run)
            printRun(run)
        }
    }
    printBatchFooter(runs)
    return Batch(temperature = temperature, runs = runs)
}

private data class TempReply(val text: String, val outputTokens: Long)

private fun ask(client: AnthropicClient, temperature: Double): TempReply? {
    val params = MessageCreateParams.builder()
        .model(DEFAULT_MODEL)
        .maxTokens(TEMPERATURE_LAB_MAX_TOKENS.toLong())
        .temperature(effectiveTemperature(temperature))
        .addUserMessage(TEMPERATURE_LAB_PROMPT)

    return try {
        val response = client.messages().create(params.build())
        TempReply(
            text = response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .trim(),
            outputTokens = response.usage().outputTokens(),
        )
    } catch (e: AnthropicServiceException) {
        println("temperature=$temperature — ошибка API: ${e.message}\n")
        null
    }
}

private val RULE = "─".repeat(72)

private fun printHeader(temperature: Double) {
    println(RULE)
    val sent = effectiveTemperature(temperature)
    val clamped = if (temperatureWasClamped(temperature)) " → в API уходит $sent" else ""
    println("  TEMPERATURE = $temperature$clamped")
    println(RULE)
}

private fun printRun(run: Run) {
    println("  прогон ${run.index}:")
    println(run.text.prependIndent("    "))
    println("    токенов: ${run.outputTokens}")
    println()
}

private fun printBatchFooter(runs: List<Run>) {
    val texts = runs.map { it.text }
    println("  ответы: ${diversityLabel(distinctCount(texts), runs.size)}")
    println()
}

private fun printComparison(batches: List<Batch>) {
    println(RULE)
    println("  СВОДКА")
    println(RULE)
    println("  %-12s %12s %8s".format("temperature", "разнообразие", "токенов"))
    batches.forEach { batch ->
        val texts = batch.runs.map { it.text }
        val tokens = batch.runs.sumOf { it.outputTokens }
        println(
            "  %-12s %12s %8d".format(
                batch.temperature,
                diversityLabel(distinctCount(texts), batch.runs.size),
                tokens,
            )
        )
    }
    println(
        """

        Как читать:
          точность   — на глаз: факты и расчёты стабильнее при 0;
          креатив    — на глаз: чем выше температура, тем смелее формулировки;
          разнообразие — сколько разных ответов на нескольких прогонах при одной температуре.

        Когда что брать:
          temperature = 0    — факты, классификация, извлечение данных, код по шаблону;
          temperature = 0.7  — диалог, объяснения, черновики — баланс точности и живости;
          temperature = 1.2  — названия, слоганы, сюжеты — где важнее новизна, чем повторяемость.

        Повторите прогон: модель недетерминирована даже при 0, а разброс нагляднее
        на 3–5 прогонах — во вкладке «Температура» или с --args=5.
        """.trimIndent()
    )
}
