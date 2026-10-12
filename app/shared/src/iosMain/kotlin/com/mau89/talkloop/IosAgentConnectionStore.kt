package com.mau89.talkloop

import com.mau89.talkloop.llm.COOKBOOK_OLLAMA_MODEL
import com.mau89.talkloop.llm.isValidOllamaAddress
import platform.Foundation.NSUserDefaults

fun createPersistentAgentConnectionStore(): AgentConnectionStore = object : AgentConnectionStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun load(): AgentConnection = AgentConnection(
        provider = AgentProvider.entries.firstOrNull { it.name == defaults.stringForKey("agent.provider") } ?: AgentProvider.CLAUDE,
        ollamaAddress = defaults.stringForKey("agent.address")?.takeIf(::isValidOllamaAddress) ?: defaultOllamaAddress(),
        ollamaModel = defaults.stringForKey("agent.model")?.takeIf { it.isNotBlank() && !it.endsWith(":cloud", true) } ?: COOKBOOK_OLLAMA_MODEL,
        tuning = decodeOllamaTuning(defaults.stringForKey("agent.tuning")),
    )
    override fun save(connection: AgentConnection) {
        defaults.setObject(connection.provider.name, forKey = "agent.provider")
        defaults.setObject(connection.ollamaAddress, forKey = "agent.address")
        defaults.setObject(connection.ollamaModel, forKey = "agent.model")
        defaults.setObject(encodeOllamaTuning(connection.tuning), forKey = "agent.tuning")
    }
}
