package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class RagChatControl(
    val question: String, val expectation: String, val expectedSource: String,
    val answerPatterns: List<String> = emptyList(),
    val goalPattern: String, val constraintPattern: String,
)

@Serializable
data class RagChatScenario(val id: String, val title: String, val controls: List<RagChatControl>, val restartBeforeTurn: Int = 7)

@Serializable
data class RagChatTurnRecord(
    val number: Int, val control: RagChatControl, val answer: String? = null,
    val context: RagConversationTurn? = null, val sources: List<DocumentChunkHit> = emptyList(),
    val evidence: RagEvidenceResult? = null, val historySize: Int = 0, val archiveSize: Int = 0,
    val restoredBeforeTurn: Boolean = false, val memoryRestoredExactly: Boolean? = null,
    val sourcesPresent: Boolean = false, val expectedSourceCited: Boolean = false,
    val answerChecksPassed: Boolean = false, val goalRetained: Boolean = false, val constraintRetained: Boolean = false,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val costUsd: Double = 0.0,
    val apiCalls: Int = 0, val error: String? = null,
)

@Serializable
data class RagChatScenarioResult(val scenario: RagChatScenario, val turns: List<RagChatTurnRecord>, val sessionFile: String)

@Serializable
data class RagChatReport(
    val schemaVersion: Int = 1, val createdAt: String, val model: String,
    val settings: RagSettings, val scenarios: List<RagChatScenarioResult>,
    val evaluation: String = "Два непрерывных диалога; ожидания не передаются модели. На 7-м ходе каждый агент пересоздан из сохранённого файла. Полная переписка отделена от окна из 10 сообщений. Признаки оцениваются автоматически; точность и ограничения проверяются также вручную.",
)

private fun recipe(name: String) = "https://ru.wikibooks.org/wiki/Рецепт:$name"

val RAG_CHAT_SCENARIOS: List<RagChatScenario> = listOf(
    RagChatScenario("lunch", "Обед: луковый суп и булочка с корицей", buildList {
        fun turn(q: String, expected: String, source: String, vararg patterns: String, brief: Boolean = false) =
            add(RagChatControl(q, expected, recipe(source), patterns.toList(), "обед", if (brief) "одним предложением" else "[Бб]ез замен"))
        turn("Цель — приготовить обед: «Луковый суп 1» и «Булочка с корицей 1». Без замен ингредиентов, только по книге. Отвечай подробно. Начнём с супа: когда добавляют плавленый сыр?",
            "Сначала картофель почти готов, добавить лук и морковь и довести до готовности; затем сыр.", "Луковый_суп", "готов", "сыр")
        turn("Уточнение: работаем с вариантом «Луковый суп 1». Под «сыром» дальше имею в виду плавленый сыр. Сколько картофеля предусмотрено?",
            "5–6 картофелин.", "Луковый_суп", "5\\s*[-–—]\\s*6")
        turn("А лука и моркови сколько в этом варианте?", "Лук 2–3 шт., морковь 2 шт.", "Луковый_суп", "2\\s*[-–—]\\s*3", "морков")
        turn("Сыр в этом варианте добавлять до готовности овощей или после?", "После готовности овощей.", "Луковый_суп", "готов")
        turn("А сколько минут варить после его добавления?", "3–5 минут с помешиванием до растворения сыра.", "Луковый_суп", "3\\s*[-–—]\\s*5", "помеш|раствор")
        turn("На чём обжарить морковь в выбранном супе?", "На маргарине.", "Луковый_суп", "маргарин")
        turn("Готовим тот же обед без замен. Теперь перейдём к «Булочке с корицей 1». При какой температуре её выпекать?", "170–180 °C.", "Булочка_с_корицей", "170\\s*[-–—]\\s*180")
        turn("А сколько минут её выпекать?", "12–15 минут.", "Булочка_с_корицей", "12\\s*[-–—]\\s*15")
        turn("Какой толщины раскатать тесто и какой ширины сделать полосы для неё?", "Толщина 1–1,5 см, ширина 12–14 см.", "Булочка_с_корицей", "1\\s*[-–—]\\s*1[,\\.]5", "12\\s*[-–—]\\s*14")
        turn("Уточнение: теперь отвечай одним предложением вместо подробного разбора. Какую форму придают кусочкам этой булочки?", "Форма книжечки.", "Булочка_с_корицей", "книжеч", brief = true)
        turn("Вернёмся к «Луковому супу 1»: когда добавлять сыр?", "После доведения овощей до готовности.", "Луковый_суп", "готов", brief = true)
        turn("Повтори, сколько минут варить после его добавления и нужно ли помешивать?", "3–5 минут, постоянно помешивая до растворения сыра.", "Луковый_суп", "3\\s*[-–—]\\s*5", "помеш", brief = true)
    }),
    RagChatScenario("salads", "Список продуктов: оливье, нисуаз и майонез", buildList {
        fun turn(q: String, expected: String, source: String, vararg patterns: String) =
            add(RagChatControl(q, expected, recipe(source), patterns.toList(), "список продуктов", "[Нн]е пересчитывай порции"))
        turn("Цель — подготовить список продуктов для салатов по книге. Не пересчитывай порции, не предлагай свои замены. Какие количества мяса, картофеля и майонеза указаны в основном рецепте оливье примерно на четыре порции?",
            "Мясо 200 г, картофель 200 г, майонез 150 г.", "Оливье", "200", "150")
        turn("Уточнение: под «основным салатом» здесь понимаем «Оливье» из книги 1959 года. На сколько порций рассчитаны эти количества?", "Примерно 4 порции.", "Оливье", "4|четыр")
        turn("А картофеля сколько для него?", "200 г.", "Оливье", "200")
        turn("А майонеза для него?", "150 г.", "Оливье", "150")
        turn("Мясо в нём только отварное или допустимо жареное?", "Отварное или жареное.", "Оливье", "отвар", "жарен")
        turn("Напомни норму мяса в основном салате.", "200 г.", "Оливье", "200")
        turn("Цель со списком продуктов та же. Перейдём к «Салату Нисуаз 2» на шесть персон: сколько помидоров?", "10 средних очень спелых помидоров.", "Салат_Нисуаз", "10")
        turn("А сколько анчоусов в этом варианте?", "12 филе.", "Салат_Нисуаз", "12")
        turn("Какую замену анчоусам разрешает сама книга и в каком количестве?", "1 банка тунца 300 г.", "Салат_Нисуаз", "тун", "300")
        turn("Под «заправкой» дальше понимаю «Майонез 1» из книги. Для неё сколько желтков и растительного масла?", "1 свежий нехолодный желток, 100 г масла.", "Майонез", "1|один", "100")
        turn("И сколько горчицы в этой заправке?", "Четверть чайной ложки.", "Майонез", "четвер|1/4|¼")
        turn("Вернись к основному салату примерно на четыре порции и повтори мясо, картофель и майонез для списка продуктов.", "Оливье: 200/200/150 г, исходный вариант.", "Оливье", "200", "150")
    }),
)
