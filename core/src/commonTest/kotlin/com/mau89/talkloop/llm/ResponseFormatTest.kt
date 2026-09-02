package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверка формата — единственное, что стоит между ответом модели и тем, что можно
 * показать или сложить в базу, поэтому она сама должна быть проверена. Сети здесь нет.
 */
class ResponseFormatTest {

    private val components = CarComponent.entries.joinToString(",") {
        """"${it.key}":{"summary":"c","details":"d"}"""
    }
    private val carJson = """{"answerable":true,"message":"m","car":"Toyota Levin","components":{$components}}"""
    private val refusalJson = """{"answerable":false,"message":"вне моей темы","car":""}"""

    @Test
    fun `полный ответ про машину разбирается во все шесть узлов`() {
        val car = parseCarAnswer(carJson)

        assertEquals("Toyota Levin", car?.car)
        assertTrue(car?.answerable == true)
        assertEquals(CarComponent.entries, car?.components?.map { it.component })
        assertEquals(true, checkShape(FormatMode.JSON_SCHEMA, carJson).ok)
    }

    @Test
    fun `отказ это валидный формат а не поломка`() {
        val car = parseCarAnswer(refusalJson)

        assertFalse(car!!.answerable)
        assertTrue(car.components.isEmpty())
        assertEquals(true, checkShape(FormatMode.JSON_SCHEMA, refusalJson).ok)
    }

    @Test
    fun `отказ с заполненными узлами — это поломка`() {
        // Модель отказалась, но всё равно придумала машину: в базу такое класть нельзя.
        val contradiction = """{"answerable":false,"message":"m","car":"","components":{$components}}"""

        assertEquals(false, checkShape(FormatMode.JSON_SCHEMA, contradiction).ok)
    }

    @Test
    fun `markdown-обёртка ломает разбор`() {
        // Ровно то, чем режим JSON_PROMPT отличается от JSON_SCHEMA.
        val wrapped = "```json\n$carJson\n```"

        assertNull(parseCarAnswer(wrapped))
        assertEquals(false, checkShape(FormatMode.JSON_PROMPT, wrapped).ok)
    }

    @Test
    fun `лишнее поле верхнего уровня — это другой формат`() {
        val extra = """{"answerable":false,"message":"m","car":"","comment":"держи"}"""

        assertEquals(false, checkShape(FormatMode.JSON_SCHEMA, extra).ok)
    }

    @Test
    fun `неполный набор узлов не проходит`() {
        val partial = """{"answerable":true,"message":"m","car":"X","components":{"engine":{"summary":"c","details":"d"}}}"""

        assertEquals(false, checkShape(FormatMode.JSON_SCHEMA, partial).ok)
    }

    @Test
    fun `строгие блоки — только полные тройки по всем узлам`() {
        val good = CarComponent.entries.joinToString("\n\n") {
            "КОМПОНЕНТ: ${it.title}\nКОРОТКО: c\nПОДРОБНО: d"
        }
        val short = "КОМПОНЕНТ: Двигатель\nКОРОТКО: c\nПОДРОБНО: d"
        val withPreamble = "Конечно, вот разбор:\n$good"

        assertEquals(true, checkShape(FormatMode.STRICT_TEXT, good).ok)
        assertEquals(false, checkShape(FormatMode.STRICT_TEXT, short).ok)
        assertEquals(false, checkShape(FormatMode.STRICT_TEXT, withPreamble).ok)
    }

    @Test
    fun `отказ одной строкой — валидный текстовый формат`() {
        assertEquals(true, checkShape(FormatMode.STRICT_TEXT, "$REFUSAL_PREFIX это вне моей темы").ok)
        // А вот отказ вперемешку с блоками — уже нет.
        assertEquals(false, checkShape(FormatMode.STRICT_TEXT, "$REFUSAL_PREFIX что-то\nКОМПОНЕНТ: Двигатель").ok)
    }

    @Test
    fun `свободный режим не проверяется`() {
        assertNull(checkShape(FormatMode.FREE, "что угодно").ok)
    }

    @Test
    fun `роль эксперта есть во всех режимах а схема только в одном`() {
        FormatMode.entries.forEach { mode ->
            assertTrue(
                buildSpec(mode, maxTokens = 2000).system!!.contains("автомобильный эксперт"),
                "режим $mode потерял роль эксперта",
            )
        }
        assertNull(buildSpec(FormatMode.JSON_PROMPT, maxTokens = 2000).jsonSchema)
        assertEquals(CAR_SCHEMA, buildSpec(FormatMode.JSON_SCHEMA, maxTokens = 2000).jsonSchema)
    }

    @Test
    fun `стоп-слова берутся из поля ввода через запятую`() {
        assertEquals(listOf("КОНЕЦ"), parseStopWords("КОНЕЦ"))
        assertEquals(listOf("В заключение", "Надеюсь"), parseStopWords(" В заключение , Надеюсь "))
        assertEquals(emptyList(), parseStopWords("   ,  , "))

        val spec = buildSpec(FormatMode.FREE, maxTokens = 2000, stopWords = parseStopWords("В заключение, Надеюсь"))
        assertEquals(listOf("В заключение", "Надеюсь"), spec.stopSequences)
    }

    @Test
    fun `по умолчанию стоп-слово работает страховкой а не маркером конца`() {
        // Без явной просьбы модель не знает про слово: оно просто обрывает её,
        // если она сама его напишет. Инструкции в промпте быть не должно.
        val guard = buildSpec(FormatMode.FREE, maxTokens = 2000, stopWords = listOf("В заключение"))

        assertEquals(listOf("В заключение"), guard.stopSequences)
        assertFalse(guard.system!!.contains("В заключение"))
    }

    @Test
    fun `просьба дописать стоп-слово попадает в промпт`() {
        val marker = buildSpec(
            FormatMode.STRICT_TEXT,
            maxTokens = 2000,
            stopWords = listOf("КОНЕЦ", "Надеюсь"),
            askForStopWord = true,
        )

        // Просим дописать только первое: инструкция «выведи одно из» бессмысленна.
        assertTrue(marker.system!!.contains("выведи на отдельной строке КОНЕЦ"))
        assertFalse(marker.system!!.contains("выведи на отдельной строке Надеюсь"))
        assertEquals(listOf("КОНЕЦ", "Надеюсь"), marker.stopSequences)
    }

    @Test
    fun `под схемой дописать не просим но слова всё равно шлём`() {
        val spec = buildSpec(
            FormatMode.JSON_SCHEMA,
            maxTokens = 2000,
            stopWords = listOf(DEFAULT_STOP_WORD),
            askForStopWord = true,
        )

        assertEquals(listOf(DEFAULT_STOP_WORD), spec.stopSequences)
        assertFalse(spec.system!!.contains(DEFAULT_STOP_WORD))
    }

    @Test
    fun `пустое поле стоп-слов не шлёт ничего и не пишет инструкцию`() {
        val spec = buildSpec(FormatMode.FREE, maxTokens = 2000, stopWords = parseStopWords(""), askForStopWord = true)

        assertTrue(spec.stopSequences.isEmpty())
        assertFalse(spec.system!!.contains("выведи на отдельной строке"))
    }

    @Test
    fun `лимит словами попадает в промпт с выбранным числом`() {
        val spec = buildSpec(FormatMode.STRICT_TEXT, maxTokens = 400, wordLimit = 25)

        assertEquals(400, spec.maxTokens)
        assertTrue(spec.system!!.contains("не длиннее 25 слов"))
    }

    @Test
    fun `потолок токенов настраивается отдельно от лимита словами`() {
        val hardOnly = buildSpec(FormatMode.STRICT_TEXT, maxTokens = 150, wordLimit = null)

        assertEquals(150, hardOnly.maxTokens)
        assertFalse(hardOnly.system!!.contains("слов."))
    }

    @Test
    fun `обрезанный ответ не считается совпавшим кроме свободного режима`() {
        val text = CarComponent.entries.joinToString("\n\n") {
            "КОМПОНЕНТ: ${it.title}\nКОРОТКО: c\nПОДРОБНО: d"
        }
        fun answer(stop: String) = LlmAnswer(text, stop, null, 0, 100)

        assertEquals(true, checkShape(FormatMode.STRICT_TEXT, text).ok)
        assertEquals(false, checkAnswer(FormatMode.STRICT_TEXT, answer("max_tokens")).ok)
        assertEquals(true, checkAnswer(FormatMode.STRICT_TEXT, answer("end_turn")).ok)

        // В свободном режиме формат не задавали — «не совпал» писать не о чем,
        // но обрезку всё равно надо показать.
        val free = checkAnswer(FormatMode.FREE, answer("max_tokens"))
        assertNull(free.ok)
        assertTrue(free.truncated)
    }

    @Test
    fun `шаг слайдера делит диапазон нацело`() {
        // Иначе крайние значения слайдера окажутся недостижимы.
        assertEquals(0, (MAX_TOKENS_RANGE.last - MAX_TOKENS_RANGE.first) % MAX_TOKENS_STEP)
        assertEquals(0, (WORD_LIMIT_RANGE.last - WORD_LIMIT_RANGE.first) % WORD_LIMIT_STEP)
        assertTrue(DEFAULT_WORD_LIMIT in WORD_LIMIT_RANGE)
    }
}
