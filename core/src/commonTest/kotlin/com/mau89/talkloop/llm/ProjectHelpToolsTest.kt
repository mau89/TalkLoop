package com.mau89.talkloop.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProjectHelpToolsTest {
    private val hit = DocumentChunkHit("project-1", "docs/project-architecture.md", "Структура",
        "docs/project-architecture.md", "Модули", emptyList(), emptyList(), 15,
        "core содержит TalkLoopAgent; server — Ktor.", 0.87)

    private fun found(request: DocumentSearchRequest, hits: List<DocumentChunkHit> = listOf(hit)) =
        DocumentSearchResponse(request.query, results = listOf(DocumentStrategyResults(request.strategy, hits)), searchSeconds = 0.01)

    private fun branch(name: String) = AgentToolCall("git_branch", "branch", "{}", "{}",
        """{"branch":"$name","detached":false,"commit":"abc"}""", serverName = "talkloop-project")

    @Test fun projectRoutePrecedesCookbookAndFetchesFreshBranch() = runTest {
        var branchCalls = 0
        val help = ProjectHelpTools(DocumentRetriever { found(it) }, AgentToolProvider { branch("branch-${++branchCalls}") })
        val provider = AgentKnowledgeTools(AgentToolProvider { error("Weather must not handle /help") },
            DocumentRetriever { error("Cookbook must not handle /help") }, projectHelp = help) {
            AgentKnowledgeSettings(mcpEnabled = true, ragEnabled = true)
        }
        val first = provider.callFor("/help Где core?")!!
        val second = provider.callFor("/help Какая ветка?")!!
        assertTrue(first.projectHelp)
        assertEquals(listOf(hit), first.documentSources)
        assertTrue(first.result.contains("branch-1"))
        assertTrue(second.result.contains("branch-2"))
        assertEquals("git_branch", first.steps.single().toolName)
        val prompt = systemPromptWithToolCall("Общий помощник", first)
        assertTrue(prompt.contains(hit.text))
        assertTrue(prompt.contains(hit.file))
        assertTrue(prompt.contains("[1]"))
        assertFalse(prompt.contains(GROUNDED_RAG_SYSTEM.trimIndent()))
        assertFalse(first.evidenceEnabled)
    }

    @Test fun mcpDisabledStillRetrievesAndBareHelpUsesStructureQuestion() = runTest {
        var query = ""
        val help = ProjectHelpTools(DocumentRetriever { query = it.query; found(it, emptyList()) },
            AgentToolProvider { error("Disabled MCP must not run") }, mcpEnabled = { false })
        assertNull(help.callFor("/helpful"))
        assertNull(help.callFor("Как сварить суп?"))
        val call = help.callFor(" /help ")!!
        assertTrue(query.contains("модули core"))
        assertTrue(call.result.contains("\"git\":null"))
        assertTrue(call.result.contains("текущая ветка неизвестна"))
        assertEquals(emptyList(), call.documentSources)
        assertTrue(projectHelpResponse("Ответ", call).contains("текущая ветка неизвестна — MCP отключён"))
    }

    @Test fun failuresDoNotFallBackToUngroundedAnswer() = runTest {
        val help = ProjectHelpTools(DocumentRetriever { error("Индекс недоступен") }, AgentToolProvider { branch("main") })
        assertFailsWith<IllegalStateException> { help.callFor("/help Где core?") }
        assertFailsWith<IllegalArgumentException> { help.callFor("/help " + "a".repeat(2001)) }
    }

    @Test fun talkLoopAgentPassesProjectContextToSelectedLlmAndStoresAnswer() = runTest {
        var prompt = ""
        val llm = object : LlmClient {
            override suspend fun reply(history: List<ChatMessage>) = error("Use answer")
            override suspend fun countInputTokens(history: List<ChatMessage>, spec: ResponseSpec) = 100
            override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
                prompt = spec.system.orEmpty()
                return LlmAnswer("core содержит агента [1]. Источник: docs/project-architecture.md.", "end_turn", null, 100, 20)
            }
        }
        val agent = TalkLoopAgent(llm, toolProvider = ProjectHelpTools(DocumentRetriever { found(it) },
            AgentToolProvider { branch("test") }))
        val answer = agent.respond("/help Где агент?")
        assertFalse(prompt.contains("\"branch\":\"test\""))
        assertTrue(prompt.contains(hit.text))
        assertTrue(answer.contains("[1]"))
        assertTrue(answer.contains("Git · MCP: git_branch\nветка test\nCommit: abc"))
        assertTrue(answer.contains("[1] docs/project-architecture.md — Модули"))
        assertTrue(agent.lastToolCall.value!!.projectHelp)
        assertEquals(answer, agent.history.value.last().text)
    }

    @Test fun detachedHeadIsRenderedWithoutInventedBranchName() = runTest {
        val help = ProjectHelpTools(DocumentRetriever { found(it) }, AgentToolProvider {
            branch("unused").copy(result = """{"branch":null,"detached":true,"commit":"123abc"}""")
        })
        val answer = projectHelpResponse("Ответ [1].", help.callFor("/help")!!)
        assertTrue(answer.contains("detached HEAD\nCommit: 123abc"))
        assertFalse(answer.contains("ветка null"))
    }
}
