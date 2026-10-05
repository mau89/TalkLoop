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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.io.IOException

fun main(args: Array<String>) = runBlocking {
    // Opt-in, scoped to this process; never rewrite the computer's proxy settings.
    if ("--direct" in args) ProxySelector.setDefault(object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
    })
    fun option(name: String, default: String): String {
        val index = args.indexOf(name)
        return if (index < 0) default else args.getOrNull(index + 1)
            ?: error("После $name требуется значение")
    }
    val settings = RagSettings(option("--strategy", "fixed"), option("--limit", "5").toInt())
    settings.validate()
    val output = Path.of(option("--output", "day22-data/comparison.json"))
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    fun save(report: RagEvaluationReport) {
        output.toAbsolutePath().parent.let { Files.createDirectories(it) }
        val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
        Files.writeString(temporary, json.encodeToString(RagEvaluationReport.serializer(), report))
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    if ("--regrade" in args) {
        val report = json.decodeFromString(RagEvaluationReport.serializer(), Files.readString(output))
        require(report.cases.map { it.control.id } == RAG_CONTROL_QUESTIONS.map { it.id }) { "Нужны все 10 контрольных вопросов по порядку." }
        val cases = report.cases.zip(RAG_CONTROL_QUESTIONS).map { (recorded, control) ->
            require(recorded.control.question == control.question && recorded.control.expectation == control.expectation &&
                recorded.control.expectedSources == control.expectedSources && recorded.comparison.question == control.question) {
                "Вопрос, ожидание или источник изменились: ${control.id}"
            }
            evaluateRagComparison(control, recorded.comparison)
        }
        save(report.copy(cases = cases))
        println("Пересчитаны проверки 10 вопросов без вызовов модели; исходные ответы сохранены: $output")
        return@runBlocking
    }
    val apiKey = readApiKey() ?: error(MISSING_KEY_HINT)
    val model = option("--model", DEFAULT_MODEL)
    val mainAgent = "--main-agent" in args
    val address = option("--server", "http://127.0.0.1:8080").trimEnd('/')
    val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 65_000; connectTimeoutMillis = 10_000 }
    }
    val llm = AnthropicLlmClient(apiKey)
    try {
        val agent = RagAgent(AgentRuntime(llm), DocumentRetriever { request ->
            val response = http.post("$address/api/documents/search") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
            }
            val body = response.bodyAsText()
            check(response.status.value in 200..299) { "Локальный поиск: ${response.status.value}: $body" }
            json.decodeFromString(DocumentSearchResponse.serializer(), body)
        }, model, mainAgentConfig = if (mainAgent) AgentConfig(
            model = model,
            systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
            contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
            contextCompression = ContextCompressionConfig(enabled = false),
        ) else null)
        if ("--evaluate" !in args) {
            val question = option("--question", RAG_CONTROL_QUESTIONS.first().question)
            when (option("--mode", "both")) {
                "both" -> println(json.encodeToString(RagComparison.serializer(), agent.compare(question, settings)))
                "plain" -> println(json.encodeToString(RagAnswer.serializer(), agent.answer(question, RagMode.WITHOUT_RAG, settings)))
                "rag" -> println(json.encodeToString(RagAnswer.serializer(), agent.answer(question, RagMode.WITH_RAG, settings)))
                else -> error("--mode: plain, rag или both")
            }
            return@runBlocking
        }
        val cases = mutableListOf<RagEvaluationCase>()
        val started = Instant.now().toString()
        for (control in RAG_CONTROL_QUESTIONS) {
            println("${control.id}: ${control.question}")
            val result = evaluateRagComparison(control, agent.compare(control.question, settings))
            cases += result
            val report = RagEvaluationReport(createdAt = started, model = model, settings = settings, cases = cases.toList(),
                agentProfile = if (mainAgent) "first_tab_agent" else "isolated_cookbook")
            save(report)
            println("  Без RAG: ${result.withoutRagGrade?.matchedChecks?.size ?: 0}/${control.checks.size}; " +
                "с RAG: ${result.withRagGrade?.matchedChecks?.size ?: 0}/${control.checks.size}; " +
                "ошибки: ${result.comparison.withoutRagError ?: "нет"} / ${result.comparison.withRagError ?: "нет"}")
            if (result.comparison.withoutRag == null && result.comparison.withRag == null) break
        }
        println("Сохранено ${cases.size} сравнений: $output. Проверка признаков требует ручной оценки качества.")
        check(cases.size == RAG_CONTROL_QUESTIONS.size && cases.all {
            it.comparison.withoutRag != null && it.comparison.withRag != null
        }) { "Сравнение неполное: ошибки сохранены в $output." }
    } finally {
        http.close()
        llm.close()
    }
}
