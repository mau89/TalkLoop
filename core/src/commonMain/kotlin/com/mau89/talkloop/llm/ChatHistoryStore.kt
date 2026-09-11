package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Хранилище summary и подтверждённого дословного хвоста диалога. */
interface ChatHistoryStore {
    fun load(): List<ChatMessage>
    fun loadSummary(): String? = null
    fun save(messages: List<ChatMessage>)
    fun save(messages: List<ChatMessage>, summary: String?) = save(messages)
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

    override fun save(messages: List<ChatMessage>) {
        save(messages, summary = null)
    }

    override fun save(messages: List<ChatMessage>, summary: String?) {
        storage.write(
            key,
            json.encodeToString(
                StoredChatHistory(
                    summary = summary?.takeIf(String::isNotBlank),
                    messages = messages,
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
        const val CURRENT_VERSION = 2
    }
}

class InMemoryChatHistoryStore(
    initialHistory: List<ChatMessage> = emptyList(),
    initialSummary: String? = null,
) : ChatHistoryStore {
    private var messages = initialHistory.toList()
    private var summary = initialSummary

    override fun load(): List<ChatMessage> = messages.toList()

    override fun loadSummary(): String? = summary

    override fun save(messages: List<ChatMessage>) {
        save(messages, summary = null)
    }

    override fun save(messages: List<ChatMessage>, summary: String?) {
        this.messages = messages.toList()
        this.summary = summary
    }

    override fun clear() {
        messages = emptyList()
        summary = null
    }
}

@Serializable
private data class StoredChatHistory(
    val version: Int = 2,
    val summary: String? = null,
    val messages: List<ChatMessage>,
)
