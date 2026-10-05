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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** A persistent Russian chat; evaluation exercises the exact same agent and disk store. */
fun main(args: Array<String>) = runBlocking {
    fun option(name: String, default: String): String {
        val index = args.indexOf(name)
        return if (index < 0) default else args.getOrNull(index + 1) ?: error("После $name нужно значение")
    }
    if ("--direct" in args) ProxySelector.setDefault(object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
    })
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    val model = option("--model", DEFAULT_MODEL)
    val settings = RagSettings(strategy = option("--strategy", "fixed"), limit = option("--after", "5").toInt(),
        rewriteEnabled = true, filterEnabled = true, candidateLimit = option("--before", "10").toInt(),
        minSimilarity = option("--threshold", "0.86").toDouble(), evidenceEnabled = true,
        evidenceVerifierModel = option("--verifier-model", "claude-sonnet-5"))
    settings.validate()
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 65_000; connectTimeoutMillis = 10_000 } }
    val address = option("--server", "http://127.0.0.1:8080").trimEnd('/')
    val llm = AnthropicLlmClient(readApiKey() ?: error(MISSING_KEY_HINT))
    val retriever = DocumentRetriever { request ->
        val response = http.post("$address/api/documents/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DocumentSearchRequest.serializer(), request))
        }
        check(response.status.value in 200..299) { "Локальный поиск недоступен (${response.status.value})." }
        json.decodeFromString(DocumentSearchResponse.serializer(), response.bodyAsText())
    }
    val tools = AgentKnowledgeTools(AgentToolProvider { null }, retriever, LlmRagTaskMemoryResolver(llm)) {
        AgentKnowledgeSettings(mcpEnabled = false, ragEnabled = true, rag = settings, taskMemoryEnabled = true)
    }
    fun agent(store: ChatHistoryStore) = TalkLoopAgent(llm, AgentConfig(model = model, systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
        contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10), contextCompression = ContextCompressionConfig(enabled = false)),
        historyStore = store, invariantStore = InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS), toolProvider = tools)
    try {
        if ("--evaluate" in args) {
            val created = Instant.now().toString()
            val runDirectory = Path.of("day25-data", "evaluation-sessions", Instant.now().toEpochMilli().toString())
            val output = Path.of(option("--output", "day25-data/scenarios.json"))
            val results = mutableListOf<RagChatScenarioResult>()
            fun save() = atomicWrite(output, json.encodeToString(RagChatReport.serializer(), RagChatReport(createdAt = created,
                model = model, settings = settings, scenarios = results.toList())))
            val selected = RAG_CHAT_SCENARIOS.filter { "--scenario" !in args || it.id == option("--scenario", "") }
            require(selected.isNotEmpty()) { "Не найден сценарий." }
            for (scenario in selected) {
                val session = runDirectory.resolve(scenario.id + ".json")
                fun store() = JsonChatHistoryStore(SessionStringStore(session), strictLoading = true)
                var chat = agent(store())
                val turns = mutableListOf<RagChatTurnRecord>()
                for ((index, control) in scenario.controls.withIndex()) {
                    val restarted = index + 1 == scenario.restartBeforeTurn
                    var restored: Boolean? = null
                    if (restarted) {
                        val previousMemory = chat.ragTaskMemory.value
                        val previousArchive = chat.dialogueArchive.value
                        chat = agent(store())
                        restored = chat.ragTaskMemory.value == previousMemory && chat.dialogueArchive.value == previousArchive
                        check(restored) { "Память не восстановлена точно из файла." }
                    }
                    println("${scenario.id} ${index + 1}/${scenario.controls.size}: ${control.question}")
                    val record = try {
                        val answer = chat.respond(control.question)
                        val call = checkNotNull(chat.lastToolCall.value)
                        val evidence = checkNotNull(call.evidence)
                        val context = checkNotNull(call.ragConversation)
                        val claimed = evidence.claims.joinToString("\n") { it.text }
                        val usage = chat.statistics.value.lastTurn
                        RagChatTurnRecord(index + 1, control, answer, context, call.documentSources.orEmpty(), evidence,
                            chat.history.value.size, chat.dialogueArchive.value.size, restarted, restored,
                            sourcesPresent = answer.contains("\nИсточники:"),
                            expectedSourceCited = evidence.usedSourceNumbers.any { call.documentSources?.getOrNull(it - 1)?.source == control.expectedSource },
                            answerChecksPassed = evidence.status == "answered" && control.answerPatterns.all { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(claimed) },
                            goalRetained = Regex(control.goalPattern, RegexOption.IGNORE_CASE).containsMatchIn(context.memory.goal?.text.orEmpty()),
                            constraintRetained = (context.memory.constraints + context.memory.clarifications).any {
                                Regex(control.constraintPattern, RegexOption.IGNORE_CASE).containsMatchIn(it.text)
                            },
                            inputTokens = usage?.inputTokens ?: 0, outputTokens = usage?.outputTokens ?: 0,
                            costUsd = usage?.costUsd ?: 0.0, apiCalls = evidence.apiCalls + context.memoryApiCalls)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        RagChatTurnRecord(index + 1, control, restoredBeforeTurn = restarted, memoryRestoredExactly = restored,
                            error = error.message ?: "Ошибка хода")
                    }
                    turns += record
                    val result = RagChatScenarioResult(scenario, turns.toList(), session.toString())
                    if (results.lastOrNull()?.scenario?.id == scenario.id) results[results.lastIndex] = result else results += result
                    save()
                    println("  Источники: ${record.expectedSourceCited}; цель: ${record.goalRetained}; ограничения: ${record.constraintRetained}; ${record.evidence?.reason ?: record.error}")
                    if (record.error != null) break
                }
            }
            check(results.size == selected.size && results.all { it.turns.size == it.scenario.controls.size && it.turns.none { turn -> turn.error != null } }) {
                "Прогон неполный; результаты сохранены в $output"
            }
            println("Сохранено: $output")
        } else {
            val session = Path.of(option("--session", "day25-data/chat.json"))
            Files.createDirectories(session.toAbsolutePath().parent)
            FileChannel.open(session.resolveSibling(session.fileName.toString() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = checkNotNull(channel.tryLock()) { "Этот диалог уже открыт другим процессом." }
                lock.use {
                    val chat = agent(JsonChatHistoryStore(SessionStringStore(session), strictLoading = true))
                    println("Кулинарный чат с RAG. Переписка: $session (${chat.dialogueArchive.value.size} сообщений).")
                    println("/state — память задачи; /history — переписка; /new — новая задача; /exit — выход.")
                    while (true) {
                        print("Вы> ")
                        val input = readlnOrNull()?.trim() ?: break
                        if (input.isBlank()) continue
                        when (input) {
                            "/exit" -> break
                            "/new" -> { chat.startNewTask(); println("Новая задача: переписка и память очищены.") }
                            "/state" -> println(json.encodeToString(RagTaskMemory.serializer(), chat.ragTaskMemory.value))
                            "/history" -> chat.dialogueArchive.value.forEach { println("${if (it.fromUser) "Вы" else "Агент"}> ${it.text}\n") }
                            else -> try { println("\nАгент> ${chat.respond(input)}\n") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { println("Ошибка: ${error.message}. Запрос можно повторить.") }
                        }
                    }
                }
            }
        }
    } finally { llm.close(); http.close() }
}

private fun atomicWrite(path: Path, value: String) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temporary, value)
    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private class SessionStringStore(private val path: Path) : StringStore {
    override fun read(key: String): String? = if (Files.exists(path)) Files.readString(path) else null
    override fun write(key: String, value: String) = atomicWrite(path, value)
    override fun remove(key: String) { Files.deleteIfExists(path) }
}
