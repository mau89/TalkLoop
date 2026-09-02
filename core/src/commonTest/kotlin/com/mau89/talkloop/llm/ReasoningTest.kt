package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Вердикт «этот способ точнее» держится на разборе ответа: если ответ вытащен
 * неверно, сравнение способов врёт молча. Поэтому разбор проверен отдельно, без сети.
 */
class ReasoningTest {

    private val expertsAnswer = """
        АНАЛИТИК
        Числа, кратные 3 или 7, но не 21.
        ВЫВОД: 190528

        ИНЖЕНЕР
        166833 + 71071 - 2 * 23688 = 190528.
        ВЫВОД: 190528

        КРИТИК
        Пересчитал границы: 999 и 994 входят.
        ВЫВОД: 190528

        ОТВЕТ: 190528
    """.trimIndent()

    @Test
    fun `ответ берётся из последней строки с маркером`() {
        val text = "Считаю: 3, 7, 21.\nОТВЕТ: 190528"

        assertEquals("190528", extractAnswer(text))
    }

    @Test
    fun `markdown вокруг маркера не мешает`() {
        // Модель регулярно выделяет итог жирным — это тот же ответ, а не другой формат.
        assertEquals("190528", extractAnswer("**ОТВЕТ:** **190528**"))
    }

    @Test
    fun `без маркера ответа нет`() {
        assertNull(extractAnswer("Сумма равна 190528."))
        assertNull(extractAnswer("ОТВЕТ:"))
    }

    @Test
    fun `при нескольких маркерах берём последний`() {
        // Совет экспертов может назвать промежуточный итог до финального.
        val text = "ОТВЕТ: 190913\nКритик пересчитал.\nОТВЕТ: 190528"

        assertEquals("190528", extractAnswer(text))
    }

    @Test
    fun `разделитель разрядов не делает ответ другим`() {
        assertEquals("190528", normalizeAnswer("190 528"))
        assertEquals("190528", normalizeAnswer("190,528"))
        assertEquals("пятница", normalizeAnswer("**Пятница.**"))
    }

    @Test
    fun `эталон сверяется точно или отдельным словом`() {
        assertEquals(true, matchesExpected("190528", "190 528"))
        assertEquals(true, matchesExpected("7", "7 детей"))
        assertEquals(true, matchesExpected("пятница", "Пятница (Friday)"))
        assertEquals(false, matchesExpected("190528", "190913"))
    }

    @Test
    fun `фраза с эталоном внутри не засчитывается`() {
        // Иначе «не 7, а 8» прошло бы как верный ответ.
        assertEquals(false, matchesExpected("7", "не 7, а 8 — я ошибся в первом шаге"))
    }

    @Test
    fun `без эталона вердикта нет а без ответа он отрицательный`() {
        assertNull(matchesExpected("", "190528"))
        assertEquals(false, matchesExpected("190528", null))
    }

    @Test
    fun `строка про ОТВЕТ одинакова во всех режимах`() {
        // Это единственная общая инструкция: без неё ответы не с чем сверять,
        // а с разной — сравнивались бы не способы, а формулировки.
        ReasoningMode.entries.forEach { mode ->
            val system = systemPromptFor(mode, generatedPrompt = "любой промпт")
            assertTrue(system.contains("$ANSWER_PREFIX <ответ>"), "режим $mode без маркера ответа")
        }
    }

    @Test
    fun `прямой режим не подсказывает как думать`() {
        val direct = systemPromptFor(ReasoningMode.DIRECT)
        val steps = systemPromptFor(ReasoningMode.STEP_BY_STEP)

        assertFalse(direct.contains("шаг"), "в прямой режим просочилась инструкция: $direct")
        assertTrue(steps.contains("пошагово"))
    }

    @Test
    fun `совет экспертов перечисляет все роли`() {
        val system = systemPromptFor(ReasoningMode.EXPERTS)

        DEFAULT_EXPERTS.forEach { assertTrue(system.contains(it.title), "нет роли ${it.title}") }
        assertTrue(system.contains(EXPERT_ANSWER_PREFIX))
    }

    @Test
    fun `промпт от модели без промпта не собирается`() {
        // Молча упасть в прямой режим нельзя: способ перестал бы быть собой.
        assertFailsWith<IllegalArgumentException> { systemPromptFor(ReasoningMode.SELF_PROMPT) }
        assertFailsWith<IllegalArgumentException> { systemPromptFor(ReasoningMode.SELF_PROMPT, "  ") }

        val system = systemPromptFor(ReasoningMode.SELF_PROMPT, "Проверь границы диапазона.")
        assertTrue(system.contains("Проверь границы диапазона."))
    }

    @Test
    fun `автору промпта запрещено решать задачу`() {
        assertTrue(PROMPT_WRITER_SYSTEM.contains("не решай"))
        assertEquals(1, promptWriterRequest(" задача ").size)
        assertTrue(promptWriterRequest("задача").single().text.contains("задача"))
    }

    @Test
    fun `совет разбирается на мнения с отдельными выводами`() {
        val opinions = parseExperts(expertsAnswer)

        assertEquals(DEFAULT_EXPERTS, opinions.map { it.expert })
        assertEquals(listOf("190528", "190528", "190528"), opinions.map { it.answer })
        assertTrue(opinions.last().reasoning.contains("Пересчитал"))
        // Итоговая строка — не часть рассуждения критика.
        assertFalse(opinions.last().reasoning.contains(ANSWER_PREFIX))
        assertEquals(true, expertsAgree(opinions))
    }

    @Test
    fun `расхождение экспертов видно`() {
        val split = expertsAnswer.replaceFirst("ВЫВОД: 190528", "ВЫВОД: 190913")

        assertEquals(false, expertsAgree(parseExperts(split)))
    }

    @Test
    fun `эксперт без вывода не выдумывается`() {
        val silent = "АНАЛИТИК\nУсловие про делимость.\n\nОТВЕТ: 190528"
        val opinions = parseExperts(silent)

        assertEquals(1, opinions.size)
        assertNull(opinions.single().answer)
        assertNull(expertsAgree(opinions))
    }

    @Test
    fun `роль в одну строку с рассуждением тоже разбирается`() {
        // Модель регулярно пишет «АНАЛИТИК: ...» вместо заголовка отдельной строкой.
        val inline = "АНАЛИТИК: условие про делимость\nВЫВОД: 190528"
        val opinion = parseExperts(inline).single()

        assertEquals(DEFAULT_EXPERTS.first(), opinion.expert)
        assertEquals("условие про делимость", opinion.reasoning)
        assertEquals("190528", opinion.answer)
    }

    @Test
    fun `имя роли внутри рассуждения не начинает новый блок`() {
        // «КРИТИК прав» — это про критика, а не заголовок его блока.
        val text = "ИНЖЕНЕР\nКРИТИК прав насчёт границ.\nВЫВОД: 190528"
        val opinion = parseExperts(text).single()

        assertEquals(DEFAULT_EXPERTS[1], opinion.expert)
        assertTrue(opinion.reasoning.contains("КРИТИК прав"))
    }

    @Test
    fun `ответ не по формату не даёт мнений`() {
        assertTrue(parseExperts("Сумма равна 190528.").isEmpty())
    }

    @Test
    fun `у каждой готовой задачи есть эталон`() {
        REASONING_TASKS.forEach { task ->
            assertTrue(task.text.isNotBlank(), "пустая задача ${task.title}")
            assertTrue(task.expected.isNotBlank(), "задача ${task.title} без эталона — сравнивать нечем")
        }
    }

    @Test
    fun `потолок токенов один на все режимы`() {
        // Разный потолок сравнивал бы мои настройки, а не способы рассуждения.
        ReasoningMode.entries.forEach { mode ->
            val spec = reasoningSpec(mode, generatedPrompt = "любой промпт")
            assertEquals(REASONING_MAX_TOKENS, spec.maxTokens)
            assertNull(spec.jsonSchema)
        }
        assertEquals(REASONING_MAX_TOKENS, PROMPT_WRITER_SPEC.maxTokens)
    }
}
