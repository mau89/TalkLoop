package com.mau89.talkloop

import com.mau89.talkloop.llm.COOKBOOK_OLLAMA_MODEL
import com.mau89.talkloop.llm.OllamaTuning
import com.mau89.talkloop.llm.VPS_OLLAMA_MODEL
import com.mau89.talkloop.llm.VPS_OLLAMA_TUNING
import kotlinx.serialization.json.Json

enum class AgentProvider { CLAUDE, OLLAMA }

data class AgentConnection(
    val provider: AgentProvider = AgentProvider.CLAUDE,
    val ollamaAddress: String = defaultOllamaAddress(),
    val ollamaModel: String = COOKBOOK_OLLAMA_MODEL,
    val tuning: OllamaTuning = OllamaTuning(),
    val serviceToken: String = "",
) {
    override fun toString(): String = "AgentConnection(provider=$provider, ollamaAddress=$ollamaAddress, " +
        "ollamaModel=$ollamaModel, tuning=$tuning, serviceToken=<redacted>)"
}

internal expect fun defaultOllamaAddress(): String

internal fun vpsAgentConnection() = AgentConnection(
    provider = AgentProvider.OLLAMA,
    ollamaAddress = "https://89.40.70.118:18443",
    ollamaModel = VPS_OLLAMA_MODEL,
    tuning = VPS_OLLAMA_TUNING,
)

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
