package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentStatistics
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.formatUsd
import kotlinx.coroutines.runBlocking

/**
 * Один воспроизводимый сценарий из 10 пользовательских реплик. Ветка A слово в
 * слово совпадает с линейными прогонами; ветка B показывает независимое решение.
 */
private val SCENARIO_PREFIX = listOf(
    "Цель: мобильное приложение для записи на занятия с репетитором.",
    "Пользователи: ученики и преподаватели.",
    "Поддерживаем Android и iOS.",
    "Расписание должно открываться офлайн.",
    "Стороннюю аналитику не подключаем.",
    "Язык первого релиза — русский.",
    "Дедлайн MVP — 15 ноября.",
)

private val BRANCH_A_SUFFIX = listOf(
    "В варианте A вход по email и паролю.",
    "В варианте A нужны push-напоминания, но без встроенной оплаты.",
    "Сформируй итоговое ТЗ: перечисли все принятые требования и ничего не додумывай.",
)

private val BRANCH_B_SUFFIX = listOf(
    "В варианте B вход только по номеру телефона.",
    "В варианте B нужна встроенная оплата, push-напоминаний не будет.",
    "Сформируй итоговое ТЗ: перечисли все принятые требования и ничего не додумывай.",
)

private val SYSTEM = """
    Ты аналитик, который собирает требования к продукту.
    До просьбы сформировать итоговое ТЗ отвечай одной короткой строкой-подтверждением.
    В итоговом ТЗ используй только сведения из доступного контекста, ничего не выдумывай.
""".trimIndent()

private data class RunResult(
    val strategy: String,
    val answer: String,
    val remembered: Int,
    val required: Int,
    val statistics: AgentStatistics,
    val note: String,
)

fun main() = runBlocking {
    val apiKey = readApiKey()
    if (apiKey == null) {
        println(MISSING_KEY_HINT)
        return@runBlocking
    }
    val client = AnthropicLlmClient(apiKey)

    println("День 10. Один сценарий сбора ТЗ, 10 реплик на каждый путь.\n")
    val results = mutableListOf<RunResult>()
    results += runLinear(
        name = "Sliding Window",
        client = client,
        strategy = ContextStrategy.SlidingWindow(keepLastMessages = 6),
    )
    results += runLinear(
        name = "Sticky Facts",
        client = client,
        strategy = ContextStrategy.StickyFacts(keepLastMessages = 6, maxFacts = 12),
    )
    results += runBranches(client)

    println("\n${"─".repeat(76)}")
    println("СРАВНЕНИЕ")
    results.forEach { result ->
        val stats = result.statistics
        println(
            "${result.strategy}: детали ${result.remembered}/${result.required} · " +
                "${stats.allTokens} токенов · ${formatUsd(stats.allCostUsd)} · ${result.note}"
        )
    }
    println(
        """

        Как читать результат:
        - качество/стабильность: сколько проверяемых деталей осталось в финальном ТЗ;
        - токены: весь оплачиваемый input+output, включая обновление facts;
        - удобство: Sliding прост, Facts прозрачен, Branching сохраняет варианты без смешивания.
        """.trimIndent()
    )
}

private suspend fun runLinear(
    name: String,
    client: AnthropicLlmClient,
    strategy: ContextStrategy,
): RunResult {
    val agent = TalkLoopAgent(
        llmClient = client,
        config = AgentConfig(
            model = DEFAULT_MODEL,
            systemPrompt = SYSTEM,
            maxTokens = 600,
            temperature = 0.0,
            contextStrategy = strategy,
        ),
    )
    var answer = ""
    (SCENARIO_PREFIX + BRANCH_A_SUFFIX).forEachIndexed { index, message ->
        answer = agent.respond(message)
        println("[$name ${index + 1}/10] $answer")
    }
    return result(name, answer, agent.statistics.value, "один линейный диалог")
}

private suspend fun runBranches(client: AnthropicLlmClient): List<RunResult> {
    val agent = TalkLoopAgent(
        llmClient = client,
        config = AgentConfig(
            model = DEFAULT_MODEL,
            systemPrompt = SYSTEM,
            maxTokens = 600,
            temperature = 0.0,
            contextStrategy = ContextStrategy.Branching,
        ),
    )
    SCENARIO_PREFIX.forEachIndexed { index, message ->
        println("[Branching ${index + 1}/7] ${agent.respond(message)}")
    }
    val checkpoint = agent.createCheckpoint("Общие требования")
    val branchA = agent.createBranch("Вариант A", checkpoint.id)
    val branchB = agent.createBranch("Вариант B", checkpoint.id)

    agent.switchBranch(branchA.id)
    var answerA = ""
    BRANCH_A_SUFFIX.forEach { answerA = agent.respond(it) }
    val statsAfterA = agent.statistics.value

    agent.switchBranch(branchB.id)
    var answerB = ""
    BRANCH_B_SUFFIX.forEach { answerB = agent.respond(it) }
    val totalStats = agent.statistics.value

    println("[Branching · A] $answerA")
    println("[Branching · B] $answerB")
    return listOf(
        result("Branching · A", answerA, statsAfterA, "ветка A из общего checkpoint"),
        result("Branching · A+B", answerB, totalStats, "две независимые ветки"),
    )
}

private val REQUIRED_DETAILS = listOf(
    listOf("запис", "репетитор"),
    listOf("учен", "преподав"),
    listOf("android", "ios"),
    listOf("офлайн"),
    listOf("аналитик"),
    listOf("русск"),
    listOf("15 ноября"),
)

private fun result(
    name: String,
    answer: String,
    statistics: AgentStatistics,
    note: String,
): RunResult {
    val normalized = answer.lowercase()
    val remembered = REQUIRED_DETAILS.count { alternatives ->
        alternatives.all { marker -> marker in normalized }
    }
    return RunResult(
        strategy = name,
        answer = answer,
        remembered = remembered,
        required = REQUIRED_DETAILS.size,
        statistics = statistics,
        note = note,
    )
}
