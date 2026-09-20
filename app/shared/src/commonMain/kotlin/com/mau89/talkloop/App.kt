package com.mau89.talkloop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ChatHistoryStore
import com.mau89.talkloop.llm.ContextCompressionConfig
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.GENERAL_AGENT_SYSTEM_PROMPT
import com.mau89.talkloop.llm.FOOD_ASSISTANT_INVARIANTS
import com.mau89.talkloop.llm.InMemoryChatHistoryStore
import com.mau89.talkloop.llm.InMemoryInvariantStore
import com.mau89.talkloop.llm.InvariantStore
import com.mau89.talkloop.llm.TUTOR_SYSTEM_PROMPT

private val TABS = listOf(
    "Инварианты",
    "Агент",
    "Разговор",
    "Формат",
    "Мышление",
    "Температура",
    "Модели",
)

@Composable
fun App(
    apiKey: String,
    agentHistoryStore: ChatHistoryStore = InMemoryChatHistoryStore(),
    agentInvariantStore: InvariantStore =
        InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS),
) {
    MaterialTheme {
        val agentRuntime = remember(apiKey) {
            AgentRuntime(AnthropicLlmClient(apiKey))
        }
        val conversationAgent = remember(agentRuntime) {
            agentRuntime.spawn(AgentConfig(systemPrompt = TUTOR_SYSTEM_PROMPT))
        }
        var generalAgentConfig by remember(agentRuntime) {
            mutableStateOf(
                AgentConfig(
                    systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
                    contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
                    contextCompression = ContextCompressionConfig(enabled = false),
                )
            )
        }
        var generalAgent by remember(agentRuntime, agentHistoryStore, agentInvariantStore) {
            mutableStateOf(
                agentRuntime.spawn(
                    config = generalAgentConfig,
                    historyStore = agentHistoryStore,
                    invariantStore = agentInvariantStore,
                )
            )
        }
        var tab by remember { mutableStateOf(0) }

        Column(Modifier.fillMaxSize().safeContentPadding()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> InvariantLabScreen(
                    agent = generalAgent,
                    modifier = Modifier.fillMaxSize(),
                )
                1 -> AgentLabScreen(
                    apiKey = apiKey,
                    agent = generalAgent,
                    config = generalAgentConfig,
                    onCreateAgent = { config ->
                        generalAgentConfig = config
                        generalAgent = agentRuntime.spawn(
                            config = config,
                            historyStore = agentHistoryStore,
                            invariantStore = agentInvariantStore,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> ChatScreen(apiKey, conversationAgent, Modifier.fillMaxSize())
                3 -> FormatLabScreen(apiKey, Modifier.fillMaxSize())
                4 -> ReasoningLabScreen(apiKey, Modifier.fillMaxSize())
                5 -> TemperatureLabScreen(apiKey, Modifier.fillMaxSize())
                else -> ModelLabScreen(apiKey, Modifier.fillMaxSize())
            }
        }
    }
}
