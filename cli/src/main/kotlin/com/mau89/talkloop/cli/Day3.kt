package com.mau89.talkloop.cli

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.ExpertOpinion
import com.mau89.talkloop.llm.PROMPT_WRITER_SYSTEM
import com.mau89.talkloop.llm.REASONING_MAX_TOKENS
import com.mau89.talkloop.llm.REASONING_TASKS
import com.mau89.talkloop.llm.ReasoningMode
import com.mau89.talkloop.llm.ReasoningTask
import com.mau89.talkloop.llm.expertsAgree
import com.mau89.talkloop.llm.extractAnswer
import com.mau89.talkloop.llm.isRefusal
import com.mau89.talkloop.llm.matchesExpected
import com.mau89.talkloop.llm.parseExperts
import com.mau89.talkloop.llm.systemPromptFor

/**
 * День 3. Разные способы рассуждения.
 *
 * Одна задача уходит в модель четырьмя способами: прямым вопросом, с просьбой
 * решать пошагово, по промпту, который модель написала себе сама, и советом из
 * трёх экспертов. Задача с одним проверяемым ответом — иначе «какой способ точнее»
 * решалось бы на глаз, а на глаз убедительны все четыре.
 *
 * Здесь по одному прогону на способ: видно ответы рядом. Устойчивость способа
 * одним прогоном не мерится — для этого во вкладке «Мышление» тот же набор
 * гоняется по нескольку раз.
 *
 * Запуск: ./gradlew :cli:day3 -q --console=plain
 *   номером аргумента выбирается задача: ./gradlew :cli:day3 -q --console=plain --args=2
 */

fun main(args: Array<String>) {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return
    }

    val index = args.firstOrNull()?.toIntOrNull()?.minus(1) ?: 0
    val task = REASONING_TASKS.getOrElse(index) { REASONING_TASKS.first() }
    val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    println("День 3. Одна задача — четыре способа рассуждения. Модель: $DEFAULT_MODEL\n")
    println("ЗАДАЧА (одинаковая во всех четырёх прогонах) — ${task.title}:")
    println(task.text.prependIndent("  "))
    println("  верный ответ: ${task.expected}\n")

    val solutions = ReasoningMode.entries.mapNotNull { mode -> solve(client, mode, task) }
    if (solutions.size == ReasoningMode.entries.size) printComparison(task, solutions)
}

/** Решение одним способом вместе с тем, во что оно обошлось. */
private data class Solution(
    val mode: ReasoningMode,
    val text: String,
    val answer: String?,
    val correct: Boolean?,
    val calls: Int,
    val outputTokens: Long,
    val truncated: Boolean,
)

private fun solve(client: AnthropicClient, mode: ReasoningMode, task: ReasoningTask): Solution? {
    printHeader(mode)

    // Единственный способ с двумя запросами: промпт сначала надо получить.
    var spentTokens = 0L
    val generatedPrompt = if (mode == ReasoningMode.SELF_PROMPT) {
        val written = ask(client, PROMPT_WRITER_SYSTEM, task.text, label = "промпт") ?: return null
        spentTokens += written.outputTokens
        println("Промпт, который модель написала себе сама:")
        println(written.text.prependIndent("  "))
        println()
        written.text
    } else {
        null
    }

    val reply = ask(client, systemPromptFor(mode, generatedPrompt), task.text, label = mode.title)
        ?: return null
    println(reply.text.ifEmpty { "(пусто)" })

    val answer = extractAnswer(reply.text)
    val solution = Solution(
        mode = mode,
        text = reply.text,
        answer = answer,
        correct = matchesExpected(task.expected, answer),
        calls = mode.calls,
        outputTokens = spentTokens + reply.outputTokens,
        truncated = reply.stopReason == "max_tokens",
    )

    if (mode == ReasoningMode.EXPERTS) printExperts(parseExperts(reply.text))
    printFooter(solution)
    return solution
}

private data class Reply(val text: String, val stopReason: String, val outputTokens: Long)

private fun ask(client: AnthropicClient, system: String, message: String, label: String): Reply? {
    val params = MessageCreateParams.builder()
        .model(DEFAULT_MODEL)
        .maxTokens(REASONING_MAX_TOKENS.toLong())
        .system(system)
        .addUserMessage(message)

    return try {
        val response = client.messages().create(params.build())
        Reply(
            text = response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .trim(),
            stopReason = response.stopReason().map { it.toString() }.orElse("—"),
            outputTokens = response.usage().outputTokens(),
        )
    } catch (e: AnthropicServiceException) {
        println("$label — ошибка API: ${e.message}\n")
        null
    }
}

private val RULE = "─".repeat(72)

private fun printHeader(mode: ReasoningMode) {
    println(RULE)
    println("  ${mode.title.uppercase()} — ${mode.hint}")
    println(RULE)
}

/** Мнения совета печатаем отдельно: расхождение экспертов — главное, что он показывает. */
private fun printExperts(opinions: List<ExpertOpinion>) {
    if (opinions.isEmpty()) {
        println("\n  формат совета не соблюдён — мнения по ролям не разобрались")
        return
    }
    println()
    opinions.forEach { opinion ->
        println("  ${opinion.expert.title}: ${opinion.answer ?: "своего вывода не назвал"}")
    }
    println(
        when (expertsAgree(opinions)) {
            true -> "  эксперты сошлись"
            false -> "  эксперты разошлись — итог не единогласный"
            null -> "  сравнивать нечего: вывод назвал только один"
        }
    )
}

private fun printFooter(solution: Solution) {
    println()
    println("  ответ ....... ${solution.answer ?: "не найден"} ${verdict(solution)}")
    println("  запросов .... ${solution.calls}")
    println("  токенов ..... ${solution.outputTokens}")
    if (solution.truncated) println("  ответ обрезан потолком max_tokens")
    println()
}

private fun verdict(solution: Solution): String = when {
    solution.correct == true -> "— верно"
    isRefusal(solution.answer) -> "— отказ: задача не того типа"
    solution.correct == false -> "— неверно"
    else -> "— эталона нет"
}

private fun printComparison(task: ReasoningTask, solutions: List<Solution>) {
    println(RULE)
    println("  ЧТО ПОЛУЧИЛОСЬ (верный ответ: ${task.expected})")
    println(RULE)
    println("  %-18s %-12s %-9s %8s %8s".format("способ", "ответ", "вердикт", "запросов", "токенов"))
    solutions.forEach { solution ->
        println(
            "  %-18s %-12s %-9s %8d %8d".format(
                solution.mode.title,
                solution.answer?.take(12) ?: "—",
                when {
                    solution.correct == true -> "верно"
                    isRefusal(solution.answer) -> "отказ"
                    solution.correct == false -> "неверно"
                    else -> "—"
                },
                solution.calls,
                solution.outputTokens,
            )
        )
    }
    println(
        """

        Один прогон показывает ответы рядом, но не устойчивость способа: модель
        недетерминирована, и тот же промпт назавтра даст другой ход рассуждения.
        Чтобы сравнивать способы, а не везение, тот же набор гоняется по нескольку
        раз во вкладке «Мышление» — там считается доля верных ответов.
        """.trimIndent()
    )
}
