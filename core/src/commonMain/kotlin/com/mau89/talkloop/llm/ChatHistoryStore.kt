package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Хранилище summary и подтверждённого дословного хвоста диалога. */
interface ChatHistoryStore {
    fun load(): List<ChatMessage>
    fun loadSummary(): String? = null
    fun loadMemory(): AgentMemorySnapshot = AgentMemorySnapshot(
        messages = load(),
        summary = loadSummary(),
    )
    fun save(messages: List<ChatMessage>)
    fun save(messages: List<ChatMessage>, summary: String?) = save(messages)
    fun saveMemory(memory: AgentMemorySnapshot) = save(memory.messages, memory.summary)
    fun clear()
}

/** Простое строковое хранилище, которое платформа связывает со своим локальным API. */
interface StringStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

/**
 * Хранит историю в JSON с версией формата, чтобы её можно было развивать без
 * привязки логики агента к Android, iOS или конкретной базе данных.
 */
class JsonChatHistoryStore(
    private val storage: StringStore,
    private val key: String = DEFAULT_HISTORY_KEY,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : ChatHistoryStore {
    override fun load(): List<ChatMessage> {
        return loadStored()?.messages.orEmpty()
    }

    override fun loadSummary(): String? {
        return loadStored()?.summary?.takeIf(String::isNotBlank)
    }

    override fun loadMemory(): AgentMemorySnapshot {
        val stored = loadStored() ?: return AgentMemorySnapshot()
        return AgentMemorySnapshot(
            messages = stored.messages,
            summary = stored.summary?.takeIf(String::isNotBlank),
            facts = stored.facts,
            activeBranchId = stored.activeBranchId,
            branches = stored.branches,
            checkpoints = stored.checkpoints,
            layers = stored.layers,
        )
    }

    override fun save(messages: List<ChatMessage>) {
        save(messages, summary = null)
    }

    override fun save(messages: List<ChatMessage>, summary: String?) {
        saveMemory(AgentMemorySnapshot(messages = messages, summary = summary))
    }

    override fun saveMemory(memory: AgentMemorySnapshot) {
        storage.write(
            key,
            json.encodeToString(
                StoredChatHistory(
                    summary = memory.summary?.takeIf(String::isNotBlank),
                    messages = memory.messages,
                    facts = memory.facts,
                    activeBranchId = memory.activeBranchId,
                    branches = memory.branches,
                    checkpoints = memory.checkpoints,
                    layers = memory.layers,
                )
            ),
        )
    }

    override fun clear() {
        storage.remove(key)
    }

    private fun loadStored(): StoredChatHistory? {
        val value = storage.read(key) ?: return null
        return runCatching {
            json.decodeFromString<StoredChatHistory>(value)
                .takeIf { it.version in 1..CURRENT_VERSION }
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_HISTORY_KEY = "talkloop.agent.history"
        const val CURRENT_VERSION = 4
    }
}

class InMemoryChatHistoryStore(
    initialHistory: List<ChatMessage> = emptyList(),
    initialSummary: String? = null,
) : ChatHistoryStore {
    private var messages = initialHistory.toList()
    private var summary = initialSummary
    private var memory = AgentMemorySnapshot(messages = messages, summary = summary)

    override fun load(): List<ChatMessage> = messages.toList()

    override fun loadSummary(): String? = summary

    override fun loadMemory(): AgentMemorySnapshot = memory.copy(
        messages = memory.messages.toList(),
        facts = memory.facts.toMap(),
        branches = memory.branches.map { it.copy(messages = it.messages.toList()) },
        checkpoints = memory.checkpoints.map { it.copy(messages = it.messages.toList()) },
        layers = memory.layers.deepCopy(),
    )

    override fun save(messages: List<ChatMessage>) {
        save(messages, summary = null)
    }

    override fun save(messages: List<ChatMessage>, summary: String?) {
        this.messages = messages.toList()
        this.summary = summary
        memory = AgentMemorySnapshot(messages = this.messages, summary = summary)
    }

    override fun saveMemory(memory: AgentMemorySnapshot) {
        this.memory = memory.copy(
            messages = memory.messages.toList(),
            facts = memory.facts.toMap(),
            branches = memory.branches.map { it.copy(messages = it.messages.toList()) },
            checkpoints = memory.checkpoints.map { it.copy(messages = it.messages.toList()) },
            layers = memory.layers.deepCopy(),
        )
        messages = this.memory.messages
        summary = this.memory.summary
    }

    override fun clear() {
        messages = emptyList()
        summary = null
        memory = AgentMemorySnapshot()
    }
}

@Serializable
private data class StoredChatHistory(
    val version: Int = 4,
    val summary: String? = null,
    val messages: List<ChatMessage>,
    val facts: Map<String, String> = emptyMap(),
    val activeBranchId: String? = null,
    val branches: List<DialogueBranch> = emptyList(),
    val checkpoints: List<DialogueCheckpoint> = emptyList(),
    val layers: MemoryLayersSnapshot = MemoryLayersSnapshot(),
)

private fun MemoryLayersSnapshot.deepCopy(): MemoryLayersSnapshot = copy(
    shortTerm = shortTerm.copy(messages = shortTerm.messages.toList()),
    working = working.copy(items = working.items.toList()),
    longTerm = longTerm.copy(items = longTerm.items.toList()),
)
