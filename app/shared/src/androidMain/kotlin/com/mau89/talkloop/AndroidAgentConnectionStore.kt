package com.mau89.talkloop

import android.content.Context
import com.mau89.talkloop.llm.DEFAULT_OLLAMA_MODEL
import com.mau89.talkloop.llm.isValidOllamaAddress

fun createPersistentAgentConnectionStore(context: Context): AgentConnectionStore =
    object : AgentConnectionStore {
        private val preferences = context.getSharedPreferences("agent-connection", Context.MODE_PRIVATE)
        override fun load(): AgentConnection {
            val provider = AgentProvider.entries.firstOrNull { it.name == preferences.getString("provider", null) }
                ?: AgentProvider.CLAUDE
            val address = preferences.getString("address", null)?.takeIf(::isValidOllamaAddress)
                ?: defaultOllamaAddress()
            val model = preferences.getString("model", null)?.takeIf {
                it.isNotBlank() && !it.endsWith(":cloud", ignoreCase = true)
            } ?: DEFAULT_OLLAMA_MODEL
            return AgentConnection(provider, address, model)
        }
        override fun save(connection: AgentConnection) {
            preferences.edit().putString("provider", connection.provider.name)
                .putString("address", connection.ollamaAddress)
                .putString("model", connection.ollamaModel).apply()
        }
    }
