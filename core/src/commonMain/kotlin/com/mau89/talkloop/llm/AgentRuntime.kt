package com.mau89.talkloop.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Общая среда выполнения логических агентов.
 *
 * Runtime владеет единственным транспортом. Созданные через него агенты имеют
 * независимые конфигурации, историю и синхронизацию, но не создают новые
 * сетевые клиенты.
 */
class AgentRuntime(
    private val sharedLlmClient: LlmClient,
) {
    private val mutableAgentCount = MutableStateFlow(0)

    val agentCount: StateFlow<Int> = mutableAgentCount.asStateFlow()

    fun spawn(
        config: AgentConfig,
        initialHistory: List<ChatMessage> = emptyList(),
    ): TalkLoopAgent {
        val agent = TalkLoopAgent(
            llmClient = sharedLlmClient,
            config = config,
            initialHistory = initialHistory,
        )
        mutableAgentCount.update { it + 1 }
        return agent
    }

    fun spawn(configs: Iterable<AgentConfig>): List<TalkLoopAgent> =
        configs.map(::spawn)
}
