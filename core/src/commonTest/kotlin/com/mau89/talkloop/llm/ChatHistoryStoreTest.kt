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
        assertTrue(storage.values.values.single().contains("\"version\":8"))
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
    fun `facts checkpoint и ветки сохраняются одним снимком`() {
        val storage = MapStringStore()
        val store = JsonChatHistoryStore(storage)
        val common = listOf(ChatMessage(true, "Общее требование"))
        val checkpoint = DialogueCheckpoint("checkpoint-1", "Развилка", common)
        val branch = DialogueBranch("branch-a", "Вариант A", checkpoint.id, common)
        val expected = AgentMemorySnapshot(
            messages = common,
            facts = mapOf("goal" to "собрать ТЗ"),
            activeBranchId = branch.id,
            branches = listOf(branch),
            checkpoints = listOf(checkpoint),
        )

        store.saveMemory(expected)

        assertEquals(expected, JsonChatHistoryStore(storage).loadMemory())
    }

    @Test
    fun `три слоя памяти сериализуются в отдельные секции`() {
        val storage = MapStringStore()
        val store = JsonChatHistoryStore(storage)
        val expected = MemoryLayersSnapshot(
            shortTerm = ShortTermMemory(listOf(ChatMessage(true, "Текущий диалог"))),
            working = WorkingMemory(
                taskName = "Подготовить релиз",
                items = listOf(MemoryItem("deadline", "15 ноября")),
            ),
            longTerm = LongTermMemory(
                listOf(
                    LongTermMemoryItem(
                        LongTermMemoryKind.PROFILE,
                        "language",
                        "русский",
                    )
                )
            ),
        )

        store.saveMemory(
            AgentMemorySnapshot(
                messages = expected.shortTerm.messages,
                layers = expected,
            )
        )

        val raw = storage.values.values.single()
        assertTrue(raw.contains("\"shortTerm\""))
        assertTrue(raw.contains("\"working\""))
        assertTrue(raw.contains("\"longTerm\""))
        assertEquals(expected, JsonChatHistoryStore(storage).loadMemory().layers)
    }

    @Test
    fun `каталог профилей и активный профиль сохраняются вместе с памятью`() {
        val storage = MapStringStore()
        val store = JsonChatHistoryStore(storage)
        val profile = UserProfile(
            id = "maria",
            displayName = "Мария",
            preferences = AssistantPreferences(
                language = "русский",
                style = "кратко и по делу",
                format = "маркированный список",
                constraints = listOf("не использовать эмодзи"),
            ),
        )

        val second = UserProfile(
            id = "ivan",
            displayName = "Иван",
            preferences = AssistantPreferences(style = "подробно"),
        )
        store.saveMemory(
            AgentMemorySnapshot(
                userProfile = profile,
                userProfiles = listOf(profile, second),
                activeUserProfileId = profile.id,
            )
        )

        val restored = JsonChatHistoryStore(storage).loadMemory()
        assertEquals(profile, restored.userProfile)
        assertEquals(listOf(profile, second), restored.userProfiles)
        assertEquals("maria", restored.activeUserProfileId)
        assertTrue(storage.values.values.single().contains("\"userProfiles\""))
    }

    @Test
    fun `состояние задачи сохраняется вместе с паузой и точкой продолжения`() {
        val storage = MapStringStore()
        val expected = TaskState(
            taskName = "Подготовить релиз",
            stage = TaskStage.EXECUTION,
            currentStep = "Собрать приложение",
            expectedAction = "Запустить smoke-тесты",
            paused = true,
        )

        JsonChatHistoryStore(storage).saveMemory(AgentMemorySnapshot(taskState = expected))

        assertEquals(expected, JsonChatHistoryStore(storage).loadMemory().taskState)
        assertTrue(storage.values.values.single().contains("\"taskState\""))
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
    fun `состояние задачи версии 7 получает новые поля по умолчанию`() {
        val storage = MapStringStore(
            mutableMapOf(
                "talkloop.agent.history" to """
                    {
                      "version":7,
                      "messages":[],
                      "taskState":{
                        "taskName":"Старое сохранение",
                        "stage":"EXECUTION",
                        "currentStep":"Продолжить работу",
                        "expectedAction":"Запустить тесты",
                        "paused":true
                      }
                    }
                """.trimIndent()
            )
        )

        val restored = JsonChatHistoryStore(storage).loadMemory().taskState!!

        assertEquals(TaskActor.AGENT, restored.expectedActor)
        assertEquals(emptyList(), restored.completionCriteria)
        assertEquals(0, restored.revision)
        assertTrue(restored.paused)
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
