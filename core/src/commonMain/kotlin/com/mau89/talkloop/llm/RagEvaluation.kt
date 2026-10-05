package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class RagCheck(val description: String, val anyOf: List<String>, val noneOf: List<String> = emptyList())

@Serializable
data class RagControlQuestion(
    val id: String, val question: String, val expectation: String,
    val expectedSources: List<String>, val checks: List<RagCheck>,
)

private fun recipe(name: String) = "https://ru.wikibooks.org/wiki/Рецепт:" + name.replace(' ', '_')
private fun check(label: String, vararg patterns: String) = RagCheck(label, patterns.toList())

/** Frozen before the model run. These expectations are never included in its prompt. */
val RAG_CONTROL_QUESTIONS = listOf(
    RagControlQuestion("q01", "По кулинарной книге: когда добавляют плавленый сыр в «Луковый суп 1» и сколько после этого варят?",
        "Сыр добавляют после готовности картофеля с луком и морковью; варят 3–5 минут, помешивая до растворения сыра.",
        listOf(recipe("Луковый суп")), listOf(
            check("После готовности картофеля", "картофел.*готов", "готов.*картофел"),
            check("3–5 минут", "(?<!\\d)3\\s*[-–—]\\s*5\\s*мин", "тр[иехё]+.*пят.*мин"),
            check("Помешивать до растворения", "помеш", "раствор"))),
    RagControlQuestion("q02", "Какие количества риса, мяса, моркови и масла указаны для классического ферганского плова в книге?",
        "Рис 250 г; баранина или говядина 150 г; морковь 150 г; масло или вытопленный жир 55 г.",
        listOf(recipe("Плов")), listOf(
            check("Рис 250 г", "рис[^\\n]{0,50}250", "250[^\\n]{0,30}рис"),
            check("Мясо 150 г", "(?:мяс|баранин|говядин)[^\\n]{0,80}150", "150[^\\n]{0,50}(?:мяс|баранин|говядин)"),
            check("Морковь 150 г", "морков[^\\n]{0,50}150", "150[^\\n]{0,30}морков"),
            check("Масло/жир 55 г", "(?:масл|жир)[^\\n]{0,80}55", "55[^\\n]{0,50}(?:масл|жир)"))),
    RagControlQuestion("q03", "Как по книге замочить рис для более рассыпчатого плова: температура воды и время?",
        "Посолить сухой рис, залить водой около 60 °C, выдержать 30–120 минут, слить и промыть тёплой водой.",
        listOf(recipe("Плов")), listOf(
            check("Около 60 °C", "(?<!\\d)60\\s*(?:°|град|C|С)"),
            check("30–120 минут", "(?<!\\d)30\\s*[-–—]\\s*120", "30.*(?:2\\s*час|двух\\s*час)"),
            check("Промыть рис", "промы"))),
    RagControlQuestion("q04", "При какой температуре и сколько минут по книге выпекают «Булочку с корицей 1»?",
        "170–180 градусов, 12–15 минут.", listOf(recipe("Булочка с корицей")), listOf(
            check("170–180 градусов", "(?<!\\d)170\\s*[-–—]\\s*180"),
            check("12–15 минут", "(?<!\\d)12\\s*[-–—]\\s*15\\s*мин"))),
    RagControlQuestion("q05", "По варианту «Белый бульон 1»: сколько телячьих костей и воды нужно на литр бульона и сколько его варят?",
        "750 г костей; всего 2,5 л воды, в том числе 1,5 л после первого кипячения; основной этап варки 3 часа.",
        listOf(recipe("Белый бульон")), listOf(
            check("750 г костей", "(?<!\\d)750\\s*(?:г|грам)"),
            check("Всего 2,5 л воды", "(?<!\\d)2[,.]5\\s*(?:л|литр)"),
            check("Основная варка 3 часа", "(?<!\\d)3\\s*(?:ч|час)", "три\\s*час"))),
    RagControlQuestion("q06", "Какие количества желтка, масла и горчицы нужны для «Майонеза 1» по книге?",
        "Один свежий нехолодный желток, 100 г растительного масла, четверть чайной ложки готовой горчицы.",
        listOf(recipe("Майонез")), listOf(
            check("Один желток", "(?:1|один|одного)\\s*(?:свежий\\s*)?желт", "желт[^\\n]{0,50}(?:1\\s*(?:шт|штук)|один)"),
            check("100 г масла", "(?<!\\d)100\\s*(?:г|грам)"),
            check("Четверть чайной ложки", "четверт", "1\\s*/\\s*4", "0[,.]25"))),
    RagControlQuestion("q07", "В основном рецепте оливье из книги какие количества мяса, картофеля и майонеза указаны примерно на четыре порции?",
        "200 г отварного или жареного мяса, 200 г отварного картофеля, 150 г майонеза.",
        listOf(recipe("Оливье")), listOf(
            check("Мясо 200 г", "мяс[^\\n]{0,70}200", "200[^\\n]{0,70}мяс"),
            check("Картофель 200 г", "картофел[^\\n]{0,70}200", "200[^\\n]{0,70}картофел"),
            check("Майонез 150 г", "майонез[^\\n]{0,50}150", "150[^\\n]{0,50}майонез"))),
    RagControlQuestion("q08", "Какие нормы мяса, макарон, маргарина и лука на одну порцию приведены для «Макарон по-флотски 4»?",
        "Мясо 100 г, макароны 70 г, маргарин 10 г, лук 10 г; не смешивать с вариантом 5.",
        listOf(recipe("Макароны по-флотски")), listOf(
            check("Мясо 100 г", "мяс[^\\n]{0,50}100", "100[^\\n]{0,30}мяс"),
            check("Макароны 70 г", "макарон[^\\n]{0,50}70", "70[^\\n]{0,30}макарон"),
            check("Маргарин 10 г", "маргарин[^\\n]{0,50}10", "10[^\\n]{0,30}маргарин"),
            check("Лук 10 г", "лук[^\\n]{0,50}10", "10[^\\n]{0,30}лук"))),
    RagControlQuestion("q09", "В варианте «Салат Нисуаз 2» на шесть персон сколько помидоров и анчоусов, и чем разрешено заменить анчоусы?",
        "10 средних спелых помидоров; 12 филе анчоусов или одна банка тунца 300 г.",
        listOf(recipe("Салат Нисуаз")), listOf(
            check("10 помидоров", "помидор[^\\n]{0,50}10", "10[^\\n]{0,50}помидор"),
            check("12 филе анчоусов", "анчоус[^\\n]{0,50}12", "12[^\\n]{0,50}анчоус"),
            check("Тунец 300 г", "тун[^\\n]{0,50}300", "300[^\\n]{0,50}тун"))),
    RagControlQuestion("q10", "Приведи точные количества свёклы, капусты и мяса для борща из этой кулинарной базы. Если такого рецепта нет в доступных материалах, сообщи об этом.",
        "В корпусе нет рецепта борща. Сообщить об отсутствии сведений и не придумывать количества из общих знаний.",
        emptyList(), listOf(
            check("Сообщить об отсутствии сведений", "нет.*(?:сведени|рецепт|информац|борщ)", "отсутств", "не.*(?:найден|представлен|содерж|доступн)", "недостаточ"),
            // Явная граница кириллических единиц: JVM и Native по-разному трактуют \b.
            RagCheck("Не придумывать нормы", emptyList(), listOf("\\d+\\s*(?:г(?![а-яёa-z])|грам|мл(?![а-яёa-z])|литр|кг(?![а-яёa-z]))")))),
)

@Serializable
data class RagAnswerGrade(
    val matchedChecks: List<String>, val missedChecks: List<String>,
    val complete: Boolean, val expectedSourcesRetrieved: Boolean?, val expectedSourcesCited: Boolean?,
    val invalidCitations: List<Int>,
) {
    val allChecksMatched: Boolean get() = complete && missedChecks.isEmpty()
}

/** Explicit pattern checks, not a semantic judge. Negation and paraphrases need manual review. */
fun gradeRagAnswer(control: RagControlQuestion, answer: RagAnswer): RagAnswerGrade {
    val text = answer.text.replace('ё', 'е')
    fun matches(pattern: String) = Regex(pattern.replace('ё', 'е'), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(text)
    val (matched, missed) = control.checks.partition { check ->
        (check.anyOf.isEmpty() || check.anyOf.any(::matches)) && check.noneOf.none(::matches)
    }
    val citedSources = answer.citedSourceNumbers.mapNotNull { answer.sources.getOrNull(it - 1)?.source }.toSet()
    val retrieved = answer.sources.map { it.source }.toSet()
    val hasExpected = control.expectedSources.isNotEmpty()
    return RagAnswerGrade(matched.map { it.description }, missed.map { it.description }, answer.complete,
        control.expectedSources.all { it in retrieved }.takeIf { hasExpected && answer.mode == RagMode.WITH_RAG },
        control.expectedSources.all { it in citedSources }.takeIf { hasExpected && answer.mode == RagMode.WITH_RAG },
        answer.invalidCitationNumbers)
}

@Serializable
data class RagEvaluationCase(
    val control: RagControlQuestion, val comparison: RagComparison,
    val withoutRagGrade: RagAnswerGrade? = null, val withRagGrade: RagAnswerGrade? = null,
)

fun evaluateRagComparison(control: RagControlQuestion, comparison: RagComparison) = RagEvaluationCase(
    control, comparison, comparison.withoutRag?.let { gradeRagAnswer(control, it) },
    comparison.withRag?.let { gradeRagAnswer(control, it) },
)

@Serializable
data class RagEvaluationReport(
    val schemaVersion: Int = 1, val createdAt: String, val model: String,
    val settings: RagSettings, val cases: List<RagEvaluationCase>,
    val evaluation: String = "Проверка явных признаков ответа и ссылок; не семантический judge. Нужна ручная оценка точности, полноты и отсутствия выдумок.",
    val agentProfile: String = "isolated_cookbook",
)
