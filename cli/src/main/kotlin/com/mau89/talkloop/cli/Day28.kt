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
import kotlinx.serialization.Serializable
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
import kotlin.time.TimeSource

@Serializable
private data class Day28Run(
    val questionId: String, val repeat: Int, val durationMs: Long,
    val answer: RagAnswer? = null, val grade: RagAnswerGrade? = null,
    val memory: RagConversationTurn? = null, val modelCalls: Int = 0,
    val error: String? = null,
    val failedSources: List<DocumentChunkHit> = emptyList(),
    val failedRetrieval: RagRetrievalTrace? = null,
    val memoryRestored: Boolean? = null, val goalRetained: Boolean? = null, val constraintRetained: Boolean? = null,
    val modelReplies: List<BenchmarkReply> = emptyList(),
)

@Serializable
private data class BenchmarkReply(val stage: String, val text: String, val stopReason: String?,
    val inputTokens: Int, val outputTokens: Int, val durationMs: Long)

@Serializable
private data class Day28Report(
    val createdAt: String, val provider: String, val model: String, val verifierModel: String,
    val server: String, val ollama: String?, val settings: RagSettings,
    val contextWindow: Int, val maxTokens: Int, val repeats: Int,
    val controls: List<RagControlQuestion>, val runs: List<Day28Run>,
    val tuning: OllamaTuning? = null,
    val dialogue: Boolean = false,
    val methodology: String = "Свежий TalkLoopAgent для каждой попытки; те же память RAG, пищевые инварианты, поиск и проверка цитат, что в приложении. MCP отключён. Индекс Дня 21, параметры поиска одинаковые. Ожидания не передаются модели. Разные модели могут по-разному восстановить поисковый запрос: трассы сохранены. Время включает весь запрос и служебные вызовы. Первый замер не исключён; возможна загрузка модели. Автопроверка признаков дополняется ручной оценкой.",
)

/** No cloud client or credential is created in the default, local mode. */
fun main(args: Array<String>) = runRagBenchmark(args)

/** Shared runner keeps Day 28's baseline and Day 29's candidates on the same pipeline. */
internal fun runRagBenchmark(args: Array<String>, tuning: OllamaTuning? = null) = runBlocking {
    fun option(name: String, default: String): String {
        val i = args.indexOf(name)
        return if (i < 0) default else args.getOrNull(i + 1) ?: error("После $name нужно значение")
    }
    // Benchmark traffic goes directly to the explicitly selected provider and local index.
    ProxySelector.setDefault(object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
    })
    val provider = option("--provider", "local")
    require(provider in listOf("local", "cloud")) { "Выберите --provider local или cloud" }
    val local = provider == "local"
    val model = option("--model", if (local) DEFAULT_OLLAMA_MODEL else DEFAULT_MODEL)
    val verifier = option("--verifier-model", if (local) model else "claude-sonnet-5")
    val ollama = option("--ollama", "http://127.0.0.1:11434")
    val address = option("--server", "http://127.0.0.1:8080").trimEnd('/')
    val repeats = option("--repeats", "3").toInt()
    require(repeats in 1..10)
    val dialogue = "--dialogue" in args
    require(!dialogue || repeats == 1) { "Для проверки диалога используйте --repeats 1" }
    val ids = option("--questions", "q04,q07,q10").split(',')
    val controls = if (dialogue) listOf(
        RAG_CONTROL_QUESTIONS.single { it.id == "q07" }.copy(id = "dialogue-1", question =
            "Цель — составить список продуктов по книге. Не пересчитывай порции. " + RAG_CONTROL_QUESTIONS.single { it.id == "q07" }.question),
        RAG_CONTROL_QUESTIONS.single { it.id == "q07" }.copy(id = "dialogue-2", question = "А майонеза сколько для него?",
            checks = listOf(RagCheck("Майонез 150 г", listOf("150.*г")))),
        RAG_CONTROL_QUESTIONS.single { it.id == "q04" }.copy(id = "dialogue-3"),
    ) else ids.map { id -> RAG_CONTROL_QUESTIONS.single { it.id == id } }
    val settings = RagSettings(strategy = "fixed", limit = 5, rewriteEnabled = true,
        filterEnabled = true, candidateLimit = 10, minSimilarity = .86,
        evidenceEnabled = true, evidenceVerifierModel = verifier)
    settings.validate()
    val contextWindow = if (local) tuning?.contextWindowTokens ?: OLLAMA_CONTEXT_WINDOW_TOKENS else contextWindowForModel(model)
    val maxTokens = DEFAULT_MAX_TOKENS
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    val output = Path.of(option("--output", "day28-data/$provider.json"))
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 65_000; connectTimeoutMillis = 10_000 } }
    val transport: LlmClient = if (local) OllamaLlmClient(ollama, model, tuning = tuning)
        else AnthropicLlmClient(readApiKey() ?: error(MISSING_KEY_HINT))
    val replies = mutableListOf<BenchmarkReply>()
    val llm = object : LlmClient by transport {
        override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
            val started = TimeSource.Monotonic.markNow()
            val answer = transport.answer(history, spec)
            val shape = spec.jsonSchema?.get("properties").toString()
            val stage = when { "resolvedQuestion" in shape -> "memory"; "unsupportedClaimNumbers" in shape -> "verdict"; else -> "answer" }
            replies += BenchmarkReply(stage, answer.text, answer.stopReason, answer.totalInputTokens,
                answer.outputTokens, started.elapsedNow().inWholeMilliseconds)
            return answer
        }
    }
    try {
        if (transport is OllamaLlmClient) println(transport.checkConnection())
        val retriever = DocumentRetriever { request ->
            val response = http.post("$address/api/documents/search") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
            }
            check(response.status.value in 200..299) { "Локальный поиск: HTTP ${response.status.value}" }
            json.decodeFromString(DocumentSearchResponse.serializer(), response.bodyAsText())
        }
        val tools = AgentKnowledgeTools(AgentToolProvider { null }, retriever, LlmRagTaskMemoryResolver(llm)) {
            AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true, rag = settings, taskMemoryEnabled = true)
        }
        val runs = mutableListOf<Day28Run>()
        val created = Instant.now().toString()
        fun save() {
            Files.createDirectories(output.toAbsolutePath().parent)
            val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
            Files.writeString(temporary, json.encodeToString(Day28Report.serializer(), Day28Report(
                created, provider, model, verifier, address, ollama.takeIf { local }, settings,
                contextWindow, maxTokens, repeats, controls, runs.toList(), tuning, dialogue,
                methodology = if (dialogue) "Три хода одной задачи; перед каждым ходом новый агент восстанавливает тот же InMemoryChatHistoryStore. Проверяем цель, ограничение, память и ответы по источникам. Облако и MCP отключены."
                    else "Свежий агент и пустая история на каждую попытку; одинаковые инварианты и настройки поиска; MCP отключён; эталоны не передаются модели. Полное время включает память, поиск, ответ, проверку и исправление. Автошаблоны требуют ручной сверки.")))
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val dialogueStore = InMemoryChatHistoryStore()
        var previousMemory: RagTaskMemory? = null
        repeat(repeats) { repeat ->
            for (control in controls) {
                val agent = TalkLoopAgent(llm, AgentConfig(model = model, systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
                    maxTokens = maxTokens, contextWindowTokens = contextWindow,
                    contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
                    contextCompression = ContextCompressionConfig(enabled = false)),
                    historyStore = if (dialogue) dialogueStore else InMemoryChatHistoryStore(),
                    invariantStore = InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS), toolProvider = tools)
                println("$provider ${control.id} ${repeat + 1}/$repeats: ${control.question}")
                val started = TimeSource.Monotonic.markNow()
                val firstReply = replies.size
                val record = try {
                    val restored = if (dialogue) previousMemory == null || agent.ragTaskMemory.value == previousMemory else null
                    check(restored != false) { "Память не восстановлена точно" }
                    val text = agent.respond(control.question)
                    val elapsed = started.elapsedNow().inWholeMilliseconds
                    val tool = checkNotNull(agent.lastToolCall.value)
                    val usage = agent.statistics.value
                    val sources = tool.documentSources.orEmpty()
                    val evidence = checkNotNull(tool.evidence)
                    val goalRetained = if (dialogue) agent.ragTaskMemory.value.goal?.text?.contains("список продуктов") == true else null
                    val constraintRetained = if (dialogue) agent.ragTaskMemory.value.constraints.any { it.text.contains("Не пересчитывай порции") } else null
                    check(goalRetained != false && constraintRetained != false) { "Цель или ограничение потеряны" }
                    if (dialogue) previousMemory = agent.ragTaskMemory.value
                    val answer = RagAnswer(RagMode.WITH_RAG, text, model, usage.allInputTokens, usage.allOutputTokens,
                        elapsed, usage.lastTurn?.stopReason ?: "local_refusal", sources, evidence.usedSourceNumbers,
                        strategy = settings.strategy, retrieval = tool.retrieval, evidence = evidence)
                    Day28Run(control.id, repeat + 1, elapsed, answer, gradeRagEvidenceAnswer(control, answer),
                        tool.ragConversation, evidence.apiCalls + (tool.ragConversation?.memoryApiCalls ?: 0),
                        memoryRestored = restored, goalRetained = goalRetained, constraintRetained = constraintRetained)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    Day28Run(control.id, repeat + 1, started.elapsedNow().inWholeMilliseconds,
                        memory = agent.lastToolCall.value?.ragConversation, error = failure.message ?: "Ошибка",
                        failedSources = agent.lastToolCall.value?.documentSources.orEmpty(),
                        failedRetrieval = agent.lastToolCall.value?.retrieval)
                }
                runs += record.copy(modelReplies = replies.drop(firstReply))
                save()
                println("  ${record.durationMs} мс · ${record.answer?.evidence?.reason ?: record.error} · признаки: ${record.grade?.allChecksMatched}")
            }
        }
        check(runs.none { it.error != null }) { "Прогон закончен с ошибками; сохранено: $output" }
        println("Сохранено: $output")
    } finally { llm.close(); http.close() }
}
