package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Разбор ошибок — вход в SRS-очередь; с UI пока не соединён, но формат уже зафиксирован. */
class RecapTest {

    @Test
    fun `валидный разбор превращается в структуру`() {
        val json = """
            {"mistakes":[{"learner":"She don't know","corrected":"She doesn't know",
            "rule":"С he/she/it — doesn't."}],"words":["cinema"]}
        """.trimIndent()

        val recap = parseRecap(json)

        assertEquals(1, recap?.mistakes?.size)
        assertEquals("She doesn't know", recap?.mistakes?.first()?.corrected)
        assertEquals(listOf("cinema"), recap?.words)
    }

    @Test
    fun `markdown-обёртка ломает разбор`() {
        assertNull(parseRecap("```json\n{\"mistakes\":[],\"words\":[]}\n```"))
    }
}
