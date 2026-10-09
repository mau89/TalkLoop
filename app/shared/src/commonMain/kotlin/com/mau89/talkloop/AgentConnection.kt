package com.mau89.talkloop

import com.mau89.talkloop.llm.DEFAULT_OLLAMA_MODEL

enum class AgentProvider { CLAUDE, OLLAMA }

data class AgentConnection(
    val provider: AgentProvider = AgentProvider.CLAUDE,
    val ollamaAddress: String = defaultOllamaAddress(),
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
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
