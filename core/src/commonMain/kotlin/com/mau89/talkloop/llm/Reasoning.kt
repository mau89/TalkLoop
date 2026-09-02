package com.mau89.talkloop.llm

/**
 * Лаборатория рассуждения: одна задача — четыре способа её решить.
 *
 * Задача взята с единственным проверяемым ответом, и это главное решение файла.
 * Сравнивать «качество рассуждений» на глаз бессмысленно — красивым выглядит любое,
 * в том числе то, что привело к неверному числу. Поэтому ответ вытаскивается из
 * текста и сверяется с эталоном программой, а способы сравниваются долей верных
 * ответов на нескольких прогонах.
 */

/** Чем задан способ рассуждения. Сама задача во всех четырёх режимах одна и та же. */
enum class ReasoningMode(val title: String, val hint: String, val calls: Int) {
    DIRECT(
        title = "Прямой ответ",
        hint = "Только текст задачи. Никаких указаний, как думать.",
        calls = 1,
    ),
    STEP_BY_STEP(
        title = "Пошагово",
        hint = "Просим разбить на шаги и проверять каждый промежуточный результат.",
        calls = 1,
    ),
    SELF_PROMPT(
        title = "Промпт от модели",
        hint = "Сначала модель пишет промпт под эту задачу, потом решает по нему. Два запроса.",
        calls = 2,
    ),
    EXPERTS(
        title = "Совет экспертов",
        hint = "Аналитик, инженер и критик решают по очереди, каждый со своим выводом.",
        calls = 1,
    ),
}

/**
 * Потолок один на все режимы.
 *
 * Иначе сравнение было бы про мои настройки, а не про способы: дай совету экспертов
 * втрое больше токенов — и он выиграет уже поэтому. Хватает на трёх экспертов,
 * остальным режимам не мешает.
 */
const val REASONING_MAX_TOKENS = 3000

/** Маркер ответа. Разбирать свободный текст нечем, а сверять с эталоном надо. */
const val ANSWER_PREFIX = "ОТВЕТ:"

/** Вывод одного эксперта внутри совета — отдельно от итогового ответа. */
const val EXPERT_ANSWER_PREFIX = "ВЫВОД:"

/** Ответ на задачу не своего типа. Отказ тоже машиночитаем — иначе он ломает сравнение. */
const val REFUSAL_ANSWER = "ОТКАЗ"

/**
 * Тип задач, которые лаборатория берёт. Ограничение общее для всех четырёх режимов
 * и стоит на входе не ради вежливости: способы сравниваются долей верных ответов,
 * а «напиши стихотворение» верного ответа не имеет — такой запрос не сравнил бы
 * ничего, зато потратил бы четыре прогона.
 */
private val TASK_SCOPE = """
    Ты решаешь только задачи с одним проверяемым ответом: логические, счётные,
    арифметические — те, где ответом служит число, слово или день недели.

    Всё остальное — написать текст, посоветовать, объяснить, обсудить, перевести —
    не твоя работа. За такое не берись даже частично: выведи единственной строкой
    $ANSWER_PREFIX $REFUSAL_ANSWER и остановись.
""".trimIndent()

/**
 * Единственная инструкция, общая для всех четырёх режимов.
 *
 * Она про форму последней строки, а не про способ думать, — иначе «прямой ответ
 * без инструкций» перестал бы им быть. Без неё сравнивать нечего: ответ пришлось
 * бы вылавливать из текста угадайкой.
 */
private val ANSWER_FORMAT = """
    Последней строкой выведи ровно: $ANSWER_PREFIX <ответ>
    В этой строке — только сам ответ, без пояснений, единиц и markdown.
""".trimIndent()

private val STEP_BY_STEP_PROMPT = """
    Решай задачу пошагово. Раздели её на шаги, выполняй по одному и записывай
    промежуточный результат каждого. Прежде чем идти дальше, проверяй последний шаг.
    Ответ называй только после последнего шага.
""".trimIndent()

/**
 * Эксперт совета: имя и чем он занят. Набор задаётся на экране, а не зашит:
 * состав совета — это и есть содержание способа, его хочется менять под задачу.
 */
data class Expert(val title: String, val duty: String)

/** Набор по умолчанию. Порядок важен: критик работает по уже сказанному. */
val DEFAULT_EXPERTS = listOf(
    Expert(
        title = "АНАЛИТИК",
        duty = "разбирает условие и где в нём ловушка, а потом всё равно называет свой ответ",
    ),
    Expert(
        title = "ИНЖЕНЕР",
        duty = "считает по шагам и доводит до конкретного ответа",
    ),
    Expert(
        title = "КРИТИК",
        duty = "ищет ошибку у предыдущих: перепроверяет счёт и крайние случаи, а не соглашается",
    ),
)

private fun expertsPrompt(experts: List<Expert>): String = """
    Задачу решает совет из ${experts.size} экспертов. Они высказываются по очереди,
    каждый следующий видит сказанное до него:
    ${experts.joinToString("\n    ") { "${it.title} — ${it.duty}" }}

    Формат ответа, без markdown, блок на каждого:
    ${experts.joinToString("\n\n    ") { "${it.title}\n    <рассуждение>\n    $EXPERT_ANSWER_PREFIX <свой ответ>" }}

    $EXPERT_ANSWER_PREFIX — это ответ на саму задачу, числом или словом, в том же
    виде, что и итог. Формула, план и «см. расчёт» выводом не считаются: по этим
    строкам видно, сошлись эксперты или нет, и формула сравнение ломает. Свой вывод
    называет каждый, даже если повторяет предыдущего и даже если это прикидка.
    Если эксперты разошлись, итогом ставь ответ, выдержавший чужую проверку.
""".trimIndent()

/**
 * Промпт для промпта. Просить «напиши промпт» мало: модель охотно решает задачу
 * прямо в нём, и тогда второй запрос уже не рассуждает, а переписывает готовое —
 * способ проверял бы сам себя.
 */
val PROMPT_WRITER_SYSTEM = """
    Ты — инженер по промптам. Тебе дают задачу, ты возвращаешь промпт, по которому
    другая модель решит её как можно точнее: на что смотреть, что проверить,
    где в таких задачах обычно ошибаются.

    Задачу не решай и ответа не называй — ни в каком виде, даже намёком.
    Верни только текст промпта: без вступления, без пояснений, без markdown.
""".trimIndent()

/** Системный промпт режима. [generatedPrompt] обязателен только для [ReasoningMode.SELF_PROMPT]. */
fun systemPromptFor(
    mode: ReasoningMode,
    generatedPrompt: String? = null,
    experts: List<Expert> = DEFAULT_EXPERTS,
): String {
    val instruction = when (mode) {
        ReasoningMode.DIRECT -> null
        ReasoningMode.STEP_BY_STEP -> STEP_BY_STEP_PROMPT
        ReasoningMode.EXPERTS -> {
            require(experts.isNotEmpty()) { "совет без экспертов — решать некому" }
            expertsPrompt(experts)
        }
        ReasoningMode.SELF_PROMPT -> requireNotNull(generatedPrompt?.takeIf { it.isNotBlank() }) {
            "режим ${mode.title} без промпта от модели — решать не по чему"
        }
    }
    return listOfNotNull(TASK_SCOPE, instruction, ANSWER_FORMAT).joinToString("\n\n")
}

fun reasoningSpec(
    mode: ReasoningMode,
    generatedPrompt: String? = null,
    experts: List<Expert> = DEFAULT_EXPERTS,
): ResponseSpec =
    ResponseSpec(
        system = systemPromptFor(mode, generatedPrompt, experts),
        maxTokens = REASONING_MAX_TOKENS,
    )

/** Задача уходит как есть: меняется системный промпт, а не условие. */
fun reasoningRequest(task: String): List<ChatMessage> =
    listOf(ChatMessage(fromUser = true, text = task.trim()))

/** Первый запрос режима «промпт от модели»: промпт просим, задачу решать не даём. */
fun promptWriterRequest(task: String): List<ChatMessage> =
    listOf(ChatMessage(fromUser = true, text = "Задача:\n${task.trim()}"))

val PROMPT_WRITER_SPEC = ResponseSpec(
    system = PROMPT_WRITER_SYSTEM,
    maxTokens = REASONING_MAX_TOKENS,
)

/**
 * Задача с известным ответом. [expected] пуст — задача своя, сверять не с чем,
 * и тогда экран честно показывает ответы без вердикта.
 */
data class ReasoningTask(val title: String, val text: String, val expected: String)

/**
 * Готовые задачи. Все три — с одним проверяемым ответом и с местом, где легко
 * ошибиться на один шаг: в задаче, которую модель решает с первого раза всегда,
 * четыре способа неотличимы.
 */
val REASONING_TASKS = listOf(
    ReasoningTask(
        title = "Арифметика с двумя фильтрами",
        text = "Найди сумму всех целых чисел от 1 до 1000, которые делятся на 3 " +
            "или на 7, но не делятся на 21.",
        expected = "190528",
    ),
    ReasoningTask(
        title = "Дети в семье",
        text = "У Марины братьев столько же, сколько сестёр. У её брата Игоря сестёр " +
            "вдвое больше, чем братьев. Сколько всего детей в семье?",
        expected = "7",
    ),
    ReasoningTask(
        title = "Не та задача",
        text = "Напиши короткое стихотворение про осень.",
        expected = REFUSAL_ANSWER,
    ),
    ReasoningTask(
        title = "День недели",
        text = "1 марта 2021 года было понедельником. Каким днём недели было " +
            "1 января 2021 года?",
        expected = "пятница",
    ),
)

/** Ответ из последней строки с маркером. null — модель формат не соблюла. */
fun extractAnswer(text: String): String? = text.lines()
    .map { stripMarkup(it) }
    .lastOrNull { it.startsWith(ANSWER_PREFIX, ignoreCase = true) }
    ?.substring(ANSWER_PREFIX.length)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/**
 * Приводит ответ к сравнимому виду: регистр, markdown, «190 528» против «190528».
 * Иначе верный ответ считался бы неверным из-за пробела в разрядах.
 */
fun normalizeAnswer(raw: String): String =
    collapseDigitGroups(stripMarkup(raw))
        .lowercase()
        .replace('ё', 'е')
        .trim()
        .trim('.', '!', '"', '«', '»')
        .trim()

/**
 * Совпал ли ответ с эталоном. null — эталона нет (своя задача), проверять нечего.
 *
 * Точное совпадение или отдельным словом в коротком ответе: «7 детей» и
 * «пятница (Friday)» — тот же ответ, а вот целую фразу по вхождению не сверяем,
 * иначе «не 7, а 8» тоже засчиталось бы верным.
 */
fun isRefusal(answer: String?): Boolean =
    answer != null && normalizeAnswer(answer).startsWith(normalizeAnswer(REFUSAL_ANSWER))

fun matchesExpected(expected: String, answer: String?): Boolean? {
    if (expected.isBlank()) return null
    val got = answer?.let(::normalizeAnswer) ?: return false
    val want = normalizeAnswer(expected)
    if (got == want) return true

    val words = got.split(*TOKEN_DELIMITERS).filter { it.isNotBlank() }
    return words.size <= SHORT_ANSWER_WORDS && words.any { it.trim('.', ',') == want }
}

/** Мнение одного эксперта. [answer] == null — эксперт свой вывод не назвал. */
data class ExpertOpinion(val expert: Expert, val reasoning: String, val answer: String?)

/**
 * Разбирает совет на мнения. Пусто — модель формат не соблюла; в этом случае
 * экран показывает только итоговый ответ, придумывать мнения за неё нельзя.
 */
fun parseExperts(text: String, experts: List<Expert> = DEFAULT_EXPERTS): List<ExpertOpinion> {
    val opinions = mutableListOf<ExpertOpinion>()
    var role: Expert? = null
    val reasoning = StringBuilder()
    var answer: String? = null

    fun flush() {
        role?.let { opinions += ExpertOpinion(it, reasoning.toString().trim(), answer) }
        role = null
        reasoning.clear()
        answer = null
    }

    text.lines().map { stripMarkup(it) }.forEach { line ->
        val header = headerExpert(line, experts)
        when {
            header != null -> {
                flush()
                role = header.expert
                if (header.tail.isNotEmpty()) reasoning.appendLine(header.tail)
            }
            role == null -> Unit // до первого эксперта — вступление, если модель его добавила
            line.startsWith(EXPERT_ANSWER_PREFIX, ignoreCase = true) ->
                answer = line.substring(EXPERT_ANSWER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
            // Итоговая строка закрывает последнего эксперта, а не дописывается к нему.
            line.startsWith(ANSWER_PREFIX, ignoreCase = true) -> flush()
            else -> if (line.isNotEmpty()) reasoning.appendLine(line)
        }
    }
    flush()
    return opinions
}

/** Сошлись ли эксперты. null — мнений с ответами меньше двух, сравнивать нечего. */
fun expertsAgree(opinions: List<ExpertOpinion>): Boolean? {
    val answers = opinions.mapNotNull { it.answer }.map(::normalizeAnswer)
    return if (answers.size < 2) null else answers.toSet().size == 1
}

/** Заголовок роли и остаток строки: «КРИТИК» и «КРИТИК: пересчитал» — одно и то же. */
private data class RoleHeader(val expert: Expert, val tail: String)

/**
 * Роль опознаём только по началу строки и только когда за именем сразу конец,
 * двоеточие или тире. Иначе «КРИТИК прав» посреди рассуждения открывало бы
 * новый блок и разрывало предыдущий.
 */
private fun headerExpert(line: String, experts: List<Expert>): RoleHeader? = experts
    .firstOrNull { expert ->
        expert.title.isNotBlank() && line.startsWith(expert.title, ignoreCase = true) &&
            line.drop(expert.title.length).firstOrNull().let { it == null || it == ':' || it == '—' }
    }
    ?.let { expert -> RoleHeader(expert, line.drop(expert.title.length).trimStart(':', '—', ' ')) }

private const val SHORT_ANSWER_WORDS = 4

private val TOKEN_DELIMITERS = charArrayOf(' ', ',', ';', ':', '(', ')', '/', '—', '-', '"')

private const val MARKUP_CHARS = "*`#_"

private fun stripMarkup(line: String): String = line.filterNot { it in MARKUP_CHARS }.trim()

/** «190 528» и «190,528» — то же число. Разделитель убираем только между цифрами. */
private fun collapseDigitGroups(text: String): String = buildString {
    text.forEachIndexed { index, char ->
        val separator = char == ' ' || char == ',' || char == '\u00A0' || char == '\u202F' || char == '\''
        val betweenDigits = index > 0 && text[index - 1].isDigit() &&
            index + 1 < text.length && text[index + 1].isDigit()
        if (!(separator && betweenDigits)) append(char)
    }
}
