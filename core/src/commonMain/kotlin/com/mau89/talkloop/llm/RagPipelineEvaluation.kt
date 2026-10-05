package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class RagPipelineCase(
    val control: RagControlQuestion,
    val baseline: RagAnswer? = null, val improved: RagAnswer? = null,
    val baselineGrade: RagAnswerGrade? = null, val improvedGrade: RagAnswerGrade? = null,
    val baselineError: String? = null, val improvedError: String? = null,
)

@Serializable
data class RagPipelineReport(
    val schemaVersion: Int = 1, val createdAt: String, val model: String,
    val baselineSettings: RagSettings, val improvedSettings: RagSettings,
    val cases: List<RagPipelineCase>, val agentProfile: String = "first_tab_agent",
    val evaluation: String = "Оба режима используют RAG. Признаки и ссылки проверяются автоматически, точность и полнота — вручную. Для каждого ответа новая история; ожидания не передаются модели.",
)

@Serializable
data class RagRetrievalProbe(
    val id: String, val question: String, val expectedTitles: List<String>,
    val traces: List<RagRetrievalTrace>,
)

@Serializable
data class RagRetrievalProbeReport(val createdAt: String, val purpose: String, val probes: List<RagRetrievalProbe>)
