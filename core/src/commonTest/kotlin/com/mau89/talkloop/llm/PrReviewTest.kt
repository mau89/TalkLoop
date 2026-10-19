package com.mau89.talkloop.llm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PrReviewTest {
    @Test
    fun acceptsNumbersAndLinksButRejectsOtherRepositoriesAndShellInput() {
        for (input in listOf("7", " #7 ", "https://github.com/mau89/TalkLoop/pull/7/", "https://github.com/mau89/TalkLoop/pull/7#discussion")) {
            assertEquals(7, parseReviewPr(input))
        }
        for (input in listOf("0", "-7", "7;echo secret", "https://evil.example/pull/7",
            "https://github.com/other/TalkLoop/pull/7", "https://github.com/mau89/TalkLoop/pull/7/files", "99999999999")) {
            assertFailsWith<IllegalArgumentException>(input) { parseReviewPr(input) }
        }
    }

    @Test
    fun pythonReportUsesSameContractAndNeverTreatsErrorAsCompleted() {
        val raw = """{"id":"job","prNumber":7,"state":"completed","message":"Готово","findings":[
          {"category":"bugs","severity":"P1","file":"core/Test.kt","side":"old","line":7,
           "title":"Authorization","description":"Removed check","suggestion":"Restore","source_ids":["D1"]}]}"""
        val job = Json.decodeFromString<PrReviewJob>(raw)
        assertFalse(job.isRunning)
        assertEquals(listOf("D1"), job.findings.single().sourceIds)
        assertEquals(job, Json.decodeFromString<PrReviewJob>(Json.encodeToString(job)))
        assertEquals("error", job.copy(state = "error").state)
    }
}
