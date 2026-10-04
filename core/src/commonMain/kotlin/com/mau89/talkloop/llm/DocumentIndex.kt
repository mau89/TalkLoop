package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

/** Shared contract between the local index server and the document lab. */
@Serializable
data class DocumentIndexCatalog(
    val documents: Int,
    val words: Int,
    val textPages: Double,
    val models: List<String>,
    val embeddingModel: String,
    val dimension: Int,
    val strategies: List<DocumentStrategyStats>,
    val title: String = "Документы",
    val language: String = "",
    val exampleQueries: List<String> = emptyList(),
    val attribution: String = "",
)

@Serializable
data class DocumentStrategyStats(
    val strategy: String,
    val chunks: Int,
    val meanTokens: Double,
    val crossSectionChunks: Int,
    val hitAt5: Double? = null,
)

@Serializable
data class DocumentSearchRequest(
    val query: String,
    val model: String? = null,
    val strategy: String = "fixed",
    val limit: Int = 5,
) {
    fun validate() {
        require(query.isNotBlank() && query.length <= 2000) {
            "Введите вопрос длиной от 1 до 2000 символов."
        }
        require(strategy in listOf("fixed", "structural", "both")) { "Неизвестная стратегия." }
        require(limit in 1..10) { "Количество результатов должно быть от 1 до 10." }
    }
}

@Serializable
data class DocumentSearchResponse(
    val query: String,
    val model: String? = null,
    val results: List<DocumentStrategyResults>,
    val searchSeconds: Double,
)

@Serializable
data class DocumentStrategyResults(
    val strategy: String,
    val hits: List<DocumentChunkHit>,
)

@Serializable
data class DocumentChunkHit(
    val chunkId: String,
    val source: String,
    val title: String,
    val file: String,
    val section: String,
    val anchors: List<String>,
    val models: List<String>,
    val tokenCount: Int,
    val text: String,
    val score: Double,
)
