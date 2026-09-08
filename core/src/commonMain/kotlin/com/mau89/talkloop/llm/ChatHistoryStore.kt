package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Хранилище уже подтверждённых пар реплик агента. */
interface ChatHistoryStore {
    fun load(): List<ChatMessage>
    fun save(messages: List<ChatMessage>)
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
        val value = storage.read(key) ?: return emptyList()
        return runCatching {
            val stored = json.decodeFromString<StoredChatHistory>(value)
            stored.messages.takeIf { stored.version == CURRENT_VERSION }.orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun save(messages: List<ChatMessage>) {
        storage.write(key, json.encodeToString(StoredChatHistory(messages = messages)))
    }

    override fun clear() {
        storage.remove(key)
    }

    private companion object {
        const val DEFAULT_HISTORY_KEY = "talkloop.agent.history"
        const val CURRENT_VERSION = 1
    }
}

class InMemoryChatHistoryStore(
    initialHistory: List<ChatMessage> = emptyList(),
) : ChatHistoryStore {
    private var messages = initialHistory.toList()

    override fun load(): List<ChatMessage> = messages.toList()

    override fun save(messages: List<ChatMessage>) {
        this.messages = messages.toList()
    }

    override fun clear() {
        messages = emptyList()
    }
}

@Serializable
private data class StoredChatHistory(
    val version: Int = 1,
    val messages: List<ChatMessage>,
)
