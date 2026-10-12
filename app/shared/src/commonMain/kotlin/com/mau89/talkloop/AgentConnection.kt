package com.mau89.talkloop

import com.mau89.talkloop.llm.COOKBOOK_OLLAMA_MODEL
import com.mau89.talkloop.llm.OllamaTuning
import kotlinx.serialization.json.Json

enum class AgentProvider { CLAUDE, OLLAMA }

data class AgentConnection(
    val provider: AgentProvider = AgentProvider.CLAUDE,
    val ollamaAddress: String = defaultOllamaAddress(),
    val ollamaModel: String = COOKBOOK_OLLAMA_MODEL,
    val tuning: OllamaTuning = OllamaTuning(),
)

internal expect fun defaultOllamaAddress(): String

interface AgentConnectionStore {
    fun load(): AgentConnection
    fun save(connection: AgentConnection)
}

class InMemoryAgentConnectionStore : AgentConnectionStore {
    private var connection = AgentConnection()
    override fun load() = connection
    override fun save(connection: AgentConnection) { this.connection = connection }
}

internal fun encodeOllamaTuning(tuning: OllamaTuning): String =
    Json { encodeDefaults = true }.encodeToString(OllamaTuning.serializer(), tuning)

internal fun decodeOllamaTuning(value: String?): OllamaTuning =
    value?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(OllamaTuning.serializer(), it) }.getOrNull() }
        ?: OllamaTuning()
