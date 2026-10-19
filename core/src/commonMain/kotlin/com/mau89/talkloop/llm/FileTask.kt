package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class FileTaskRequest(val goal: String) {
    fun validate() = require(goal.isNotBlank() && goal.length <= 2000) { "Опишите цель длиной до 2000 символов." }
}

@Serializable
data class FileTaskEvent(val action: String, val path: String = "", val query: String = "", val message: String = "", val ok: Boolean = true)

@Serializable
data class FileTaskChange(val path: String, val beforeSha256: String? = null, val afterSha256: String)

@Serializable
data class FileTask(
    val id: String, val goal: String, val state: String = "running", val message: String = "Ассистент выбирает файлы…",
    val model: String = "qwen3.5:9b-q8_0", val mode: String = "ollama", val summary: String = "",
    val events: List<FileTaskEvent> = emptyList(), val readFiles: List<String> = emptyList(),
    val changes: List<FileTaskChange> = emptyList(), val diff: String = "",
) {
    val isRunning: Boolean get() = state == "running"
}
