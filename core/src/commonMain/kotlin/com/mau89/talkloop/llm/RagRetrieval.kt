package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

@Serializable
data class RagCandidateDecision(val hit: DocumentChunkHit, val reason: String)

@Serializable
data class RagRetrievalTrace(
    val originalQuery: String, val searchQuery: String,
    val settings: RagSettings,
    val candidates: List<DocumentChunkHit>,
    val rejected: List<RagCandidateDecision>,
    val selectedChunkIds: List<String>,
    val searchSeconds: Double,
)

data class RagRetrievedContext(
    val request: DocumentSearchRequest, val sources: List<DocumentChunkHit>, val trace: RagRetrievalTrace,
)

/** Conservative local rewrite: only boilerplate/spacing, never ingredients, numbers or negation. */
fun rewriteRagQuery(question: String): String {
    val normalized = question.trim().replace(Regex("\\s+"), " ")
    if (Regex("(?:^|\\s)не\\s+(?:по|в|из)\\s+книг", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return normalized
    val rewritten = normalized
        .replace(Regex("^(?:пожалуйста[, :]*)?(?:подскажи|расскажи|скажи)(?:те)?[, :]*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(?:по кулинарной книге|по книге)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(?:по книге|в книге|из книги)(?=[?.!,;:]|$)", RegexOption.IGNORE_CASE), "")
        .trim()
    return rewritten.ifBlank { normalized }
}

/** E5 cosine scores are not probabilities. The cutoff is configurable and corpus-specific. */
suspend fun retrieveRagContext(
    retriever: DocumentRetriever, question: String, settings: RagSettings, standaloneQuery: String? = null,
): RagRetrievedContext {
    settings.validate()
    val search = standaloneQuery ?: question
    val query = if (settings.rewriteEnabled) rewriteRagQuery(search) else search
    val request = DocumentSearchRequest(query, strategy = settings.strategy,
        limit = if (settings.filterEnabled) settings.candidateLimit else settings.limit)
    request.validate()
    val found = retriever.search(request)
    require(found.query == query) { "Поиск вернул результаты другого вопроса." }
    val candidates = found.results.singleOrNull { it.strategy == settings.strategy }?.hits
        ?: error("Поиск не вернул выбранный индекс.")
    require(candidates.size <= request.limit) { "Поиск превысил лимит фрагментов." }
    require(candidates.all { it.score.isFinite() && it.score in -1.0..1.0 }) { "Поиск вернул некорректное сходство." }
    val rejected = mutableListOf<RagCandidateDecision>()
    val kept = mutableListOf<DocumentChunkHit>()
    val seen = mutableSetOf<String>()
    // Stable descending order also protects against a server returning unsorted candidates.
    val processing = settings.filterEnabled || settings.evidenceEnabled
    val ordered = if (processing) candidates.sortedByDescending { it.score } else candidates
    for (hit in ordered) {
        val reason = when {
            !processing -> null
            !seen.add(hit.chunkId) -> "duplicate"
            hit.score < settings.minSimilarity -> "below_similarity"
            kept.size >= settings.limit -> "top_k"
            else -> null
        }
        if (reason == null) kept += hit else rejected += RagCandidateDecision(hit, reason)
    }
    return RagRetrievedContext(request, kept, RagRetrievalTrace(question, query, settings,
        candidates, rejected, kept.map { it.chunkId }, found.searchSeconds))
}
