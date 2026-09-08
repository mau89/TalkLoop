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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ChatMessage
import com.mau89.talkloop.llm.GENERAL_AGENT_SYSTEM_PROMPT
import com.mau89.talkloop.llm.TUTOR_SYSTEM_PROMPT

private val TABS = listOf("Разговор", "Формат", "Мышление", "Температура", "Модели", "Агент")

@Composable
fun App(apiKey: String) {
    MaterialTheme {
        // История живёт здесь, а не в ChatScreen: иначе переключение вкладки
        // выбрасывает её из композиции вместе с разговором.
        val history = remember { mutableStateListOf<ChatMessage>() }
        val agentHistory = remember { mutableStateListOf<ChatMessage>() }
        val agentRuntime = remember(apiKey) {
            AgentRuntime(AnthropicLlmClient(apiKey))
        }
        val conversationAgent = remember(agentRuntime) {
            agentRuntime.spawn(AgentConfig(systemPrompt = TUTOR_SYSTEM_PROMPT))
        }
        var generalAgentConfig by remember(agentRuntime) {
            mutableStateOf(AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT))
        }
        var generalAgent by remember(agentRuntime) {
            mutableStateOf(agentRuntime.spawn(generalAgentConfig))
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
                0 -> ChatScreen(apiKey, conversationAgent, history, Modifier.fillMaxSize())
                1 -> FormatLabScreen(apiKey, Modifier.fillMaxSize())
                2 -> ReasoningLabScreen(apiKey, Modifier.fillMaxSize())
                3 -> TemperatureLabScreen(apiKey, Modifier.fillMaxSize())
                4 -> ModelLabScreen(apiKey, Modifier.fillMaxSize())
                else -> AgentLabScreen(
                    apiKey = apiKey,
                    agentRuntime = agentRuntime,
                    agent = generalAgent,
                    config = generalAgentConfig,
                    history = agentHistory,
                    onCreateAgent = { config ->
                        generalAgentConfig = config
                        generalAgent = agentRuntime.spawn(config)
                        agentHistory.clear()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
