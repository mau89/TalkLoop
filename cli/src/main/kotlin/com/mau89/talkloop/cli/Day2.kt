package com.mau89.talkloop.cli

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.DEFAULT_STOP_WORD as STOP_MARKER

/**
 * День 2. Формат ответа.
 *
 * Один и тот же запрос уходит в модель дважды — без ограничений и с ними.
 * Ограничений три, и они разной природы, в этом и смысл упражнения:
 *   1. формат    — описан словами в системном промпте;
 *   2. длина     — словами («не длиннее 60 слов») и жёстко через max_tokens;
 *   3. остановка — стоп-последовательность (обрывает генерацию на сервере)
 *                  плюс явная инструкция её дописать.
 *
 * Русский здесь только ради самого задания: промпт репетитора (TutorPrompt.kt)
 * остаётся англоязычным, его трогать незачем.
 *
 * Запуск: ./gradlew :cli:day2 -q --console=plain
 */

/** Одинаковый для обоих прогонов — меняются только ограничения вокруг него. */
private val REQUEST = """
    Ученик написал три фразы:
    1. I very like this film.
    2. Yesterday I go to the cinema with my friend.
    3. She don't know about it.
    Разбери ошибки.
""".trimIndent()

private val CONSTRAINED_SYSTEM = """
    Ты — репетитор английского. Отвечаешь по-русски.

    Формат ответа — только такие блоки, по одному на ошибку:
    ОШИБКА: <фраза ученика как есть>
    ВЕРНО: <исправленная фраза>
    ПРАВИЛО: <одно предложение, не длиннее 10 слов>

    Ограничения:
    - не больше трёх блоков;
    - между блоками — пустая строка;
    - весь ответ — не длиннее 60 слов;
    - никаких вступлений, выводов, похвалы и markdown.

    Дописав последний блок, выведи на отдельной строке $STOP_MARKER и остановись.
""".trimIndent()

fun main() {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return
    }

    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    println("День 2. Один запрос — два уровня контроля ответа. Модель: $DEFAULT_MODEL\n")
    println("ЗАПРОС (одинаковый в обоих прогонах):")
    println(REQUEST.prependIndent("  "))
    println()

    val free = ask(client, "БЕЗ ОГРАНИЧЕНИЙ", maxTokens = 1024)
    val strict = ask(
        client,
        "С ОГРАНИЧЕНИЯМИ",
        maxTokens = 300,
        system = CONSTRAINED_SYSTEM,
        stopSequence = STOP_MARKER,
    )

    if (free != null && strict != null) printComparison(free, strict)
}

/** Ответ модели вместе с тем, как именно он закончился. */
private data class Answer(
    val label: String,
    val text: String,
    val stopReason: String,
    val stopSequence: String?,
    val outputTokens: Long,
    val maxTokens: Long,
)

private fun ask(
    client: AnthropicClient,
    label: String,
    maxTokens: Long,
    system: String? = null,
    stopSequence: String? = null,
): Answer? {
    val params = MessageCreateParams.builder()
        .model(DEFAULT_MODEL)
        .maxTokens(maxTokens)
        .addUserMessage(REQUEST)
    system?.let { params.system(it) }
    stopSequence?.let { params.addStopSequence(it) }

    val message = try {
        client.messages().create(params.build())
    } catch (e: AnthropicServiceException) {
        println("$label — ошибка API: ${e.message}\n")
        return null
    }

    val answer = Answer(
        label = label,
        text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim(),
        stopReason = message.stopReason().map { it.toString() }.orElse("—"),
        stopSequence = message.stopSequence().orElse(null),
        outputTokens = message.usage().outputTokens(),
        maxTokens = maxTokens,
    )

    printAnswer(answer)
    return answer
}

private fun printAnswer(answer: Answer) {
    val rule = "─".repeat(64)
    println(rule)
    println("  ${answer.label}")
    println(rule)
    println(answer.text.ifEmpty { "(пусто)" })
    println()
    println("  stop_reason .. ${answer.stopReason}${answer.stopSequence?.let { " (\"$it\")" } ?: ""}")
    println("  max_tokens ... ${answer.maxTokens}")
    println("  токенов ...... ${answer.outputTokens}")
    println("  слов ......... ${wordCount(answer.text)}, символов: ${answer.text.length}")
    println()
}

private fun printComparison(free: Answer, strict: Answer) {
    val rule = "─".repeat(64)
    println(rule)
    println("  ЧТО ИЗМЕНИЛОСЬ")
    println(rule)
    println("  слов:    ${wordCount(free.text)} → ${wordCount(strict.text)}")
    println("  токенов: ${free.outputTokens} → ${strict.outputTokens}")
    println("  финиш:   ${free.stopReason} → ${strict.stopReason}")
    println(
        """

        Как читать stop_reason:
          end_turn      — модель закончила сама;
          stop_sequence — сервер оборвал генерацию на «$STOP_MARKER», в тексте маркера уже нет;
          max_tokens    — упёрлись в потолок, ответ обрезан на полуслове.

        Инструкция про длину задаёт форму ответа, max_tokens — только страховка
        по деньгам: он режет вслепую и превращает ответ в мусор.
        """.trimIndent()
    )
}

private fun wordCount(text: String): Int =
    text.split(Regex("\\s+")).count { it.isNotBlank() }
