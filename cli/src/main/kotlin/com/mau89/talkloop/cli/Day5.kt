package com.mau89.talkloop.cli

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.mau89.talkloop.llm.MODEL_LAB_EXPECTED_HINT
import com.mau89.talkloop.llm.MODEL_LAB_MAX_TOKENS
import com.mau89.talkloop.llm.MODEL_LAB_PROMPT
import com.mau89.talkloop.llm.MODEL_PRESETS
import com.mau89.talkloop.llm.ModelPreset
import com.mau89.talkloop.llm.estimateCostUsd
import com.mau89.talkloop.llm.formatDuration
import com.mau89.talkloop.llm.formatThroughput
import com.mau89.talkloop.llm.formatUsd
import com.mau89.talkloop.llm.scoreModelAnswer
import com.mau89.talkloop.llm.tokensPerSecond

/**
 * День 5. Версии моделей.
 *
 * Один и тот же запрос уходит в слабую, среднюю и сильную модель линейки.
 * Замеряем время, токены и стоимость. Качество — на глаз; эталон
 * необязателен: ./gradlew :cli:day5 -q --console=plain --args=8
 */

fun main(args: Array<String>) {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return
    }

    val expected = args.firstOrNull()?.trim().orEmpty()
    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    println("День 5. Один запрос — три модели.\n")
    println("ЗАПРОС (одинаковый во всех прогонах):")
    println(MODEL_LAB_PROMPT.prependIndent("  "))
    if (expected.isBlank()) {
        println("  верный ответ: не задан — качество на глаз")
        println("  чтобы сверить: --args=$MODEL_LAB_EXPECTED_HINT")
    } else {
        println("  верный ответ: $expected")
    }
    println("  temperature не задаём: Sonnet 5 / Opus 5 параметр не принимают\n")

    val runs = MODEL_PRESETS.mapNotNull { preset -> ask(client, preset, expected) }
    if (runs.size == MODEL_PRESETS.size) printComparison(runs, expected)
}

private data class ModelRun(
    val preset: ModelPreset,
    val text: String,
    val correct: Boolean?,
    val inputTokens: Long,
    val outputTokens: Long,
    val durationMs: Long,
    val costUsd: Double,
)

private fun ask(client: AnthropicClient, preset: ModelPreset, expected: String): ModelRun? {
    printHeader(preset)

    val params = MessageCreateParams.builder()
        .model(preset.id)
        .maxTokens(MODEL_LAB_MAX_TOKENS.toLong())
        .addUserMessage(MODEL_LAB_PROMPT)

    val started = System.nanoTime()
    val response = try {
        client.messages().create(params.build())
    } catch (e: AnthropicServiceException) {
        println("  ${preset.id} — ошибка API: ${e.message}\n")
        return null
    }
    val durationMs = (System.nanoTime() - started) / 1_000_000

    val text = response.content()
        .mapNotNull { block -> block.text().orElse(null)?.text() }
        .joinToString("\n")
        .trim()
    val inputTokens = response.usage().inputTokens()
    val outputTokens = response.usage().outputTokens()
    val run = ModelRun(
        preset = preset,
        text = text,
        correct = scoreModelAnswer(expected, text),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        durationMs = durationMs,
        costUsd = estimateCostUsd(preset, inputTokens.toInt(), outputTokens.toInt()),
    )
    printRun(run)
    return run
}

private val RULE = "─".repeat(72)

private fun printHeader(preset: ModelPreset) {
    println(RULE)
    println("  ${preset.title.uppercase()}  ·  ${preset.id}")
    println(RULE)
}

private fun printRun(run: ModelRun) {
    println(run.text.prependIndent("    "))
    println()
    if (run.correct != null) {
        println("    качество: ${qualityLabel(run.correct)}")
    }
    println("    время: ${formatDuration(run.durationMs)}")
    println("    токены: ${run.inputTokens} in / ${run.outputTokens} out")
    println("    скорость: ${formatThroughput(tokensPerSecond(run.outputTokens.toInt(), run.durationMs))}")
    println("    стоимость: ${formatUsd(run.costUsd)}")
    println()
}

private fun qualityLabel(correct: Boolean?): String = when (correct) {
    true -> "верно"
    false -> "неверно"
    null -> "на глаз"
}

private fun printComparison(runs: List<ModelRun>, expected: String) {
    println(RULE)
    println("  СВОДКА")
    println(RULE)
    println(
        "  %-10s %-22s %8s %11s %8s %10s %10s".format(
            "класс",
            "модель",
            "качество",
            "время",
            "in/out",
            "ток/с",
            "стоимость",
        )
    )
    runs.forEach { run ->
        println(
            "  %-10s %-22s %8s %11s %8s %10s %10s".format(
                run.preset.title,
                run.preset.id,
                qualityLabel(run.correct),
                formatDuration(run.durationMs),
                "${run.inputTokens}/${run.outputTokens}",
                formatThroughput(tokensPerSecond(run.outputTokens.toInt(), run.durationMs)),
                formatUsd(run.costUsd),
            )
        )
    }

    val cheapest = runs.minBy { it.costUsd }
    val fastest = runs.minBy { it.durationMs }
    val scored = expected.isNotBlank()
    val correct = runs.count { it.correct == true }
    val qualityLine = if (scored) {
        "качество   — сверка с эталоном ($expected);"
    } else {
        "качество   — на глаз (эталон не задан; чтобы сверить: --args=$MODEL_LAB_EXPECTED_HINT);"
    }
    val scoreLine = if (scored) {
        "В этом прогоне: верных $correct из ${runs.size},"
    } else {
        "В этом прогоне: качество на глаз,"
    }
    println(
        """

        Как читать:
          $qualityLine
          скорость   — wall-clock до полного ответа и токены в секунду;
          ресурсоёмкость — вход+выход и оценка по тарифу Anthropic.

        $scoreLine
        быстрее всех — ${fastest.preset.title} (${fastest.preset.id}),
        дешевле всех — ${cheapest.preset.title} (${cheapest.preset.id}).

        Когда что брать:
          слабая  — массовые короткие запросы, классификация, черновик;
          средняя — повседневный диалог и разбор, если ошибка не катастрофа;
          сильная — сложные рассуждения, когда цена ошибки выше цены токенов.

        Каталог Hugging Face (начало / середина / конец списка) — тот же приём
        на другом провайдере: https://huggingface.co/inference/models
        Тарифы Anthropic: https://platform.claude.com/docs/en/about-claude/pricing
        """.trimIndent()
    )
}
