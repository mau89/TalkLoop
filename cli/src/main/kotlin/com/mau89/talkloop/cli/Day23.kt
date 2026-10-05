package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

fun main(args: Array<String>) = runBlocking {
    if ("--direct" in args) ProxySelector.setDefault(object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
    })
    fun option(name: String, default: String): String {
        val i = args.indexOf(name)
        return if (i < 0) default else args.getOrNull(i + 1) ?: error("После $name требуется значение")
    }
    val baseline = RagSettings(strategy = option("--strategy", "fixed"), limit = option("--after", "5").toInt())
    val improved = baseline.copy(rewriteEnabled = true, filterEnabled = true,
        candidateLimit = option("--before", "10").toInt(), minSimilarity = option("--threshold", "0.86").toDouble())
    baseline.validate(); improved.validate()
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    val output = Path.of(option("--output", "day23-data/comparison.json"))
    fun save(body: String) {
        Files.createDirectories(output.toAbsolutePath().parent)
        val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
        Files.writeString(temporary, body)
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    val address = option("--server", "http://127.0.0.1:8080").trimEnd('/')
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 65_000; connectTimeoutMillis = 10_000 } }
    val retriever = DocumentRetriever { request ->
        val response = http.post("$address/api/documents/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
        }
        val body = response.bodyAsText()
        check(response.status.value in 200..299) { "Локальный поиск: ${response.status.value}: $body" }
        json.decodeFromString(DocumentSearchResponse.serializer(), body)
    }
    try {
        val started = Instant.now().toString()
        if ("--calibrate" in args || "--retrieval-only" in args) {
            val calibration = "--calibrate" in args
            val questions = if (calibration) listOf(
                Triple("c01", "Пожалуйста, подскажи: какие ингредиенты нужны для аджики?", listOf("Аджика")),
                Triple("c02", "По кулинарной книге: из чего готовят вальдорфский салат?", listOf("Вальдорфский салат")),
                Triple("c03", "Как приготовить ткемали?", listOf("Ткемали")),
                Triple("c04", "Какие ингредиенты нужны для шведских фрикаделек?", listOf("Шведские фрикадельки")),
                Triple("c05", "По книге: сколько муки и масла нужно для эчпочмака?", listOf("Эчпочмак")),
                Triple("c06", "Как приготовить тирамису по этой книге?", emptyList()),
                Triple("c07", "Какие ингредиенты нужны для пиццы маргарита из книги?", emptyList()),
                Triple("c08", "Как заменить сопло принтера Bambu Lab A1?", emptyList()),
            ) else RAG_CONTROL_QUESTIONS.map { control -> Triple(control.id, control.question,
                control.expectedSources.map { it.substringAfter("Рецепт:").replace('_', ' ') }) }
            val probes = mutableListOf<RagRetrievalProbe>()
            val modes = listOf(baseline, improved.copy(filterEnabled = false), improved.copy(rewriteEnabled = false), improved)
            for ((id, question, titles) in questions) {
                val traces = modes.map { retrieveRagContext(retriever, question, it).trace }
                probes += RagRetrievalProbe(id, question, titles, traces)
                println("$id: " + traces.joinToString(" / ") { trace ->
                    "${trace.selectedChunkIds.size} фр., max=${trace.candidates.maxOfOrNull { it.score }}"
                })
                save(json.encodeToString(RagRetrievalProbeReport.serializer(), RagRetrievalProbeReport(started,
                    if (calibration) "Отдельные примеры настройки порога, без вызовов LLM" else "Раздельное влияние rewrite и фильтра на 10 контрольных вопросах, без LLM", probes.toList())))
            }
            return@runBlocking
        }
        val model = option("--model", DEFAULT_MODEL)
        val llm = AnthropicLlmClient(readApiKey() ?: error(MISSING_KEY_HINT))
        try {
            val agent = RagAgent(AgentRuntime(llm), retriever, model, mainAgentConfig = AgentConfig(
                model = model, systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
                contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
                contextCompression = ContextCompressionConfig(enabled = false),
            ))
            val cases = mutableListOf<RagPipelineCase>()
            val controls = if ("--question" in args) listOf(RagControlQuestion("custom", option("--question", ""), "Ручная оценка", emptyList(), emptyList())) else RAG_CONTROL_QUESTIONS
            for (control in controls) {
                println("${control.id}: ${control.question}")
                var plain: RagAnswer? = null; var filtered: RagAnswer? = null
                var plainError: String? = null; var filteredError: String? = null
                for ((i, settings) in listOf(baseline, improved).withIndex()) {
                    try {
                        val answer = agent.answer(control.question, RagMode.WITH_RAG, settings)
                        if (i == 0) plain = answer else filtered = answer
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (i == 0) plainError = failure.message ?: "Ошибка" else filteredError = failure.message ?: "Ошибка"
                    }
                }
                cases += RagPipelineCase(control, plain, filtered, plain?.let { gradeRagAnswer(control, it) },
                    filtered?.let { gradeRagAnswer(control, it) }, plainError, filteredError)
                save(json.encodeToString(RagPipelineReport.serializer(), RagPipelineReport(createdAt = started, model = model,
                    baselineSettings = baseline, improvedSettings = improved, cases = cases.toList())))
                println("  Источники: ${plain?.sources?.size} → ${filtered?.sources?.size}; ошибки: ${plainError ?: "нет"} / ${filteredError ?: "нет"}")
                if (plain == null && filtered == null) break
            }
            check(cases.size == controls.size && cases.all { it.baseline != null && it.improved != null }) { "Сравнение неполное: ошибки сохранены в $output" }
            println("Сохранено ${cases.size} сравнений: $output")
        } finally { llm.close() }
    } finally { http.close() }
}
