package com.mau89.talkloop.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryStoreTest {

    @Test
    fun `история сохраняется в JSON и загружается новым экземпляром`() {
        val storage = MapStringStore()
        val firstLaunch = JsonChatHistoryStore(storage)
        val expected = listOf(
            ChatMessage(fromUser = true, text = "Меня зовут Маша"),
            ChatMessage(fromUser = false, text = "Приятно познакомиться, Маша!"),
        )

        firstLaunch.save(expected)
        val afterRestart = JsonChatHistoryStore(storage)

        assertEquals(expected, afterRestart.load())
        assertTrue(storage.values.values.single().startsWith("{"))
        assertTrue(storage.values.values.single().contains("\"version\":2"))
        assertTrue(storage.values.values.single().contains("\"messages\""))
    }

    @Test
    fun `summary хранится отдельно от последних сообщений`() {
        val storage = MapStringStore()
        val store = JsonChatHistoryStore(storage)
        val recent = listOf(
            ChatMessage(fromUser = true, text = "Как меня зовут?"),
            ChatMessage(fromUser = false, text = "Маша"),
        )

        store.save(recent, summary = "Пользователя зовут Маша.")
        val afterRestart = JsonChatHistoryStore(storage)

        assertEquals(recent, afterRestart.load())
        assertEquals("Пользователя зовут Маша.", afterRestart.loadSummary())
        assertTrue(storage.values.values.single().contains("\"summary\""))
    }

    @Test
    fun `формат первого дня сохранения остаётся читаемым`() {
        val storage = MapStringStore(
            mutableMapOf(
                "talkloop.agent.history" to
                    """{"version":1,"messages":[{"fromUser":true,"text":"Привет"}]}"""
            )
        )

        val store = JsonChatHistoryStore(storage)

        assertEquals(listOf(ChatMessage(true, "Привет")), store.load())
        assertEquals(null, store.loadSummary())
    }

    @Test
    fun `повреждённый JSON не мешает запуску с пустой историей`() {
        val storage = MapStringStore(
            mutableMapOf("talkloop.agent.history" to "not-json")
        )

        assertEquals(emptyList(), JsonChatHistoryStore(storage).load())
    }

    @Test
    fun `очистка удаляет сохранённый диалог`() {
        val storage = MapStringStore()
        val store = JsonChatHistoryStore(storage)
        store.save(listOf(ChatMessage(true, "Привет")))

        store.clear()

        assertEquals(emptyList(), store.load())
    }
}

internal class MapStringStore(
    val values: MutableMap<String, String> = mutableMapOf(),
) : StringStore {
    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
