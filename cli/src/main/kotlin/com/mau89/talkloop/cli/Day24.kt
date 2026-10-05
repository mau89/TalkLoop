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
    val baseline = RagSettings(strategy = option("--strategy", "fixed"), limit = option("--after", "5").toInt(),
        filterEnabled = true, rewriteEnabled = true, candidateLimit = option("--before", "10").toInt(),
        minSimilarity = option("--threshold", "0.86").toDouble())
    val improved = baseline.copy(evidenceEnabled = true, evidenceVerifierModel = option("--verifier-model", "claude-sonnet-5"))
    baseline.validate(); improved.validate()
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    val output = Path.of(option("--output", "day24-data/comparison.json"))
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
        val model = option("--model", DEFAULT_MODEL)
        val llm = AnthropicLlmClient(readApiKey() ?: error(MISSING_KEY_HINT))
        try {
            if ("--audit-drafts" in args || "--repair-draft" in args) {
                val repair = "--repair-draft" in args
                val recorded = json.decodeFromString(RagPipelineReport.serializer(), Files.readString(Path.of(option(if (repair) "--repair-draft" else "--audit-drafts", ""))))
                val audited = recorded.cases.filter { repair || it.control.id in listOf("q01", "q02") }.map { case ->
                    val answer = checkNotNull(case.improved)
                    val raw = checkNotNull(answer.evidence?.rawDraft)
                    val checked = verifyRagEvidence(llm, case.control.question, LlmAnswer(raw, answer.stopReason, null, 0, 0),
                        answer.sources, improved.evidenceVerifierModel,
                        repairSpec = if (repair) ResponseSpec(model = model, maxTokens = DEFAULT_MAX_TOKENS) else null)
                    // The recorded draft is reused, not generated again in this run.
                    val evidence = checked.evidence.copy(apiCalls = checked.evidence.apiCalls - 1)
                    val result = answer.copy(text = renderRagEvidence(evidence, answer.sources), evidence = evidence,
                        inputTokens = evidence.generationInputTokens + evidence.verificationInputTokens,
                        outputTokens = evidence.generationOutputTokens + evidence.verificationOutputTokens,
                        citedSourceNumbers = evidence.usedSourceNumbers, invalidCitationNumbers = emptyList())
                    println("${case.control.id}: ${evidence.status} · ${evidence.semanticVerdict?.reason}")
                    case.copy(improved = result, improvedGrade = gradeRagEvidenceAnswer(case.control, result))
                }
                require(if (repair) audited.isNotEmpty() else audited.size == 2) { "Нет нужных сохранённых черновиков." }
                save(json.encodeToString(RagPipelineReport.serializer(), recorded.copy(createdAt = started, improvedSettings = improved,
                    cases = audited, evaluation = if (repair) "Регрессия исправления: повторное использование сохранённого неверного черновика; одна попытка исправления и повторная проверка. Токены и apiCalls учитывают только новые вызовы, исходный черновик не генерируется. Baseline — исторический ответ из входного отчёта."
                        else "Регрессия: повторная проверка двух известных неверных черновиков; новые ответы не генерируются, по одному вызову проверяющей модели.")))
                check(audited.all { it.improved?.evidence?.status == if (repair) "answered" else "unknown" }) { "Регрессионная проверка не прошла: $output" }
                return@runBlocking
            }
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
                    filtered?.let { gradeRagEvidenceAnswer(control, it) }, plainError, filteredError)
                save(json.encodeToString(RagPipelineReport.serializer(), RagPipelineReport(createdAt = started, model = model,
                    baselineSettings = baseline, improvedSettings = improved, cases = cases.toList(),
                    evaluation = "День 24: прежний RAG и ответ с точными цитатами. Отдельно проверены наличие источников, подлинность цитат и соответствие смысла. Все ожидания изолированы от модели; для каждого ответа новая история.")))
                println("  Источники: ${plain?.sources?.size} → ${filtered?.sources?.size}; ошибки: ${plainError ?: "нет"} / ${filteredError ?: "нет"}")
                if (plain == null && filtered == null) break
            }
            check(cases.size == controls.size && cases.all { it.baseline != null && it.improved != null }) { "Сравнение неполное: ошибки сохранены в $output" }
            println("Сохранено ${cases.size} сравнений: $output")
        } finally { llm.close() }
    } finally { http.close() }
}
