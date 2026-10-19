package com.mau89.talkloop.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val REVIEW_REPOSITORY = "mau89/TalkLoop"

/** Only PR numbers and links to this project are accepted; never arbitrary commands or refs. */
fun parseReviewPr(value: String): Int {
    val text = value.trim()
    val number = Regex("#?([1-9][0-9]{0,8})").matchEntire(text)?.groupValues?.get(1)
        ?: Regex("https://github\\.com/mau89/TalkLoop/pull/([1-9][0-9]{0,8})/?(?:[?#].*)?")
            .matchEntire(text)?.groupValues?.get(1)
    return requireNotNull(number?.toIntOrNull()) {
        "Введите номер PR или ссылку https://github.com/$REVIEW_REPOSITORY/pull/7."
    }
}

@Serializable
data class PrReviewRequest(val prNumber: Int) {
    fun validate() = require(prNumber in 1..999_999_999) { "Неверный номер PR." }
}

@Serializable
data class PrReviewJob(
    val id: String,
    val prNumber: Int,
    val state: String = "running",
    val message: String = "Подготовка проверки…",
    val title: String = "",
    val url: String = "https://github.com/$REVIEW_REPOSITORY/pull/$prNumber",
    val baseSha: String = "",
    val headSha: String = "",
    val model: String = "qwen3.5:9b-q8_0",
    val resultStatus: String = "",
    val summary: String = "",
    val coverage: PrReviewCoverage? = null,
    val findings: List<PrReviewFinding> = emptyList(),
    val sources: List<PrReviewSource> = emptyList(),
    val excluded: List<PrReviewExclusion> = emptyList(),
    val markdown: String = "",
) {
    val isRunning: Boolean get() = state == "running"
}

@Serializable
data class PrReviewCoverage(val reviewedFiles: Int, val totalFiles: Int, val partial: Boolean)

@Serializable
data class PrReviewFinding(
    val category: String,
    val severity: String,
    val file: String,
    val side: String,
    val line: Int,
    val title: String,
    val description: String,
    val suggestion: String,
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    val displayFile: String = file,
)

@Serializable
data class PrReviewSource(val id: String, val kind: String, val file: String, val startLine: Int, val endLine: Int)

@Serializable
data class PrReviewExclusion(val path: String, val reason: String)
