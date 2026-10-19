package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class ReleaseRequest(val base: String, val version: String, val versionCode: Int) {
    fun validate() {
        require(base.isNotBlank() && base.length <= 250 && !base.startsWith('-') && base.none { it.isWhitespace() || it.isISOControl() }) {
            "Укажите тег или коммит предыдущего релиза без пробелов."
        }
        require(version.length <= 50 && version.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?"))) {
            "Укажите версию вида 1.1.0 или 1.1.0-rc.1."
        }
        require(versionCode in 1..2_100_000_000) { "Код версии должен быть от 1 до 2100000000 и выше опубликованного." }
    }
}

@Serializable
data class ReleaseGate(val name: String, val state: String, val log: String = "", val tests: Int = 0)

@Serializable
data class ReleaseArtifact(val name: String, val label: String, val bytes: Long, val sha256: String = "")

@Serializable
data class ReleaseJob(
    val id: String, val base: String, val version: String, val versionCode: Int,
    val state: String = "running", val message: String = "Подготовка релиза запускается…",
    val head: String = "", val model: String = "qwen3.5:9b-q8_0",
    val gates: List<ReleaseGate> = emptyList(), val kotlinTests: Int = 0, val pythonTests: Int = 0,
    val releaseNotes: String = "", val report: String = "", val logTail: String = "",
    val artifacts: List<ReleaseArtifact> = emptyList(),
) {
    val isRunning: Boolean get() = state == "running"
}
