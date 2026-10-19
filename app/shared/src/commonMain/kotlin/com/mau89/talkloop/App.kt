package com.mau89.talkloop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.AgentKnowledgeSettings
import com.mau89.talkloop.llm.AgentKnowledgeTools
import com.mau89.talkloop.llm.DocumentRetriever
import com.mau89.talkloop.llm.AgentToolProvider
import com.mau89.talkloop.llm.ProjectHelpTools
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.serialization.json.JsonObject
import com.mau89.talkloop.llm.RagSettings
import com.mau89.talkloop.llm.LlmRagTaskMemoryResolver
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
import com.mau89.talkloop.llm.DEFAULT_MODEL
import com.mau89.talkloop.llm.DEFAULT_MAX_TOKENS
import com.mau89.talkloop.llm.contextWindowForModel
import kotlinx.coroutines.delay

private val TABS = listOf(
    "Агент",
    "Файлы",
    "Поддержка",
    "Ревью PR",
    "Документы",
    "Инварианты",
    "Разговор",
    "Формат",
    "Мышление",
    "Температура",
    "Модели",
)

@Composable
fun App(
    apiKey: String,
    agentConnectionStore: AgentConnectionStore? = null,
    agentHistoryStore: ChatHistoryStore = InMemoryChatHistoryStore(),
    agentInvariantStore: InvariantStore =
        InMemoryInvariantStore(FOOD_ASSISTANT_INVARIANTS),
) {
    MaterialTheme {
        val connectionStore = agentConnectionStore ?: remember { InMemoryAgentConnectionStore() }
        val agentRuntime = remember(apiKey) {
            AgentRuntime(AnthropicLlmClient(apiKey))
        }
        val conversationAgent = remember(agentRuntime) {
            agentRuntime.spawn(AgentConfig(systemPrompt = TUTOR_SYSTEM_PROMPT))
        }
        val weatherToolProvider = remember { McpWeatherToolProvider() }
        var mcpEnabled by remember { mutableStateOf(true) }
        var ragEnabled by remember { mutableStateOf(false) }
        var ragSettings by remember { mutableStateOf(RagSettings(evidenceEnabled = true, filterEnabled = true, rewriteEnabled = true)) }
        var documentAddress by remember { mutableStateOf(defaultDocumentServerAddress()) }
        val documents = remember { DocumentIndexClient() }
        DisposableEffect(documents) { onDispose { documents.close() } }
        val projectDocuments = remember { DocumentIndexClient("/api/project/documents") }
        DisposableEffect(projectDocuments) { onDispose { projectDocuments.close() } }
        var agentConnection by remember(connectionStore) { mutableStateOf(connectionStore.load()) }
        val generalLlm = remember(apiKey, agentConnection) {
            when (agentConnection.provider) {
                AgentProvider.CLAUDE -> createAgentLlmClient(apiKey, directConnection = true)
                AgentProvider.OLLAMA -> createOllamaLlmClient(agentConnection.ollamaAddress, agentConnection.ollamaModel,
                    agentConnection.tuning, agentConnection.serviceToken)
            }
        }
        DisposableEffect(generalLlm) { onDispose { generalLlm.close() } }
        val generalRuntime = remember(generalLlm) { AgentRuntime(generalLlm) }
        val knowledgeTools = remember(weatherToolProvider, documents, projectDocuments, generalLlm) {
            AgentKnowledgeTools(
                mcp = weatherToolProvider,
                retriever = DocumentRetriever { documents.search(documentAddress, it) },
                memoryResolver = LlmRagTaskMemoryResolver(generalLlm),
                projectHelp = ProjectHelpTools(
                    retriever = DocumentRetriever { projectDocuments.search(documentAddress, it) },
                    branch = AgentToolProvider {
                        NetworkMcpToolExecutor().invoke(
                            McpEndpoint("talkloop-project", URLBuilder(documentAddress.trim()).apply {
                                port = 8082
                                encodedPath = "/mcp"
                            }.buildString()),
                            "git_branch", JsonObject(emptyMap()),
                        )
                    },
                    mcpEnabled = { mcpEnabled },
                ),
                settings = { AgentKnowledgeSettings(mcpEnabled, ragEnabled, ragSettings, taskMemoryEnabled = ragEnabled) },
            )
        }
        var generalAgentConfig by remember {
            mutableStateOf(
                AgentConfig(
                    model = if (agentConnection.provider == AgentProvider.OLLAMA) agentConnection.ollamaModel else DEFAULT_MODEL,
                    contextWindowTokens = if (agentConnection.provider == AgentProvider.OLLAMA) agentConnection.tuning.contextWindowTokens
                        else contextWindowForModel(DEFAULT_MODEL),
                    maxTokens = if (agentConnection.provider == AgentProvider.OLLAMA) agentConnection.tuning.answerMaxTokens else DEFAULT_MAX_TOKENS,
                    temperature = if (agentConnection.provider == AgentProvider.OLLAMA) agentConnection.tuning.temperature else null,
                    systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT,
                    contextStrategy = ContextStrategy.MemoryLayers(keepLastMessages = 10),
                    contextCompression = ContextCompressionConfig(enabled = false),
                )
            )
        }
        LaunchedEffect(agentConnection) {
            ragSettings = ragSettings.copy(evidenceVerifierModel = if (agentConnection.provider == AgentProvider.OLLAMA)
                agentConnection.ollamaModel else "claude-sonnet-5")
        }
        var generalAgent by remember(
            generalRuntime,
            agentHistoryStore,
            agentInvariantStore,
            knowledgeTools,
        ) {
            mutableStateOf(
                generalRuntime.spawn(
                    config = generalAgentConfig,
                    historyStore = agentHistoryStore,
                    invariantStore = agentInvariantStore,
                    toolProvider = knowledgeTools,
                )
            )
        }
        val weatherMonitoring by weatherToolProvider.monitoring.collectAsState()
        var tab by remember { mutableStateOf(0) }

        LaunchedEffect(weatherToolProvider, mcpEnabled) {
            if (!mcpEnabled) return@LaunchedEffect
            while (weatherToolProvider.monitoring.value == null) {
                val restored = runCatching {
                    weatherToolProvider.restoreMonitoring()
                }
                if (restored.isSuccess) return@LaunchedEffect
                // Сервер мог запускаться одновременно с приложением.
                delay(5_000L)
            }
        }

        LaunchedEffect(generalAgent, weatherMonitoring, mcpEnabled) {
            val monitoring = weatherMonitoring
            if (!mcpEnabled || monitoring == null) return@LaunchedEffect

            while (true) {
                delay(monitoring.intervalMinutes * 60_000L)
                runCatching {
                    weatherToolProvider.requestAutomaticSummary(monitoring.city)
                }.onSuccess { toolCall ->
                    generalAgent.appendBackgroundToolResult(toolCall)
                }
                // При временной недоступности сервера не засоряем диалог ошибками:
                // следующий запуск автоматически повторит попытку.
            }
        }

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
                0 -> AgentLabScreen(
                    apiKey = apiKey,
                    agent = generalAgent,
                    config = generalAgentConfig,
                    connection = agentConnection,
                    onConnectionChange = { connection ->
                        connectionStore.save(connection)
                        val model = if (connection.provider == AgentProvider.OLLAMA) connection.ollamaModel else DEFAULT_MODEL
                        generalAgentConfig = generalAgentConfig.copy(
                            model = model,
                            contextWindowTokens = if (connection.provider == AgentProvider.OLLAMA) connection.tuning.contextWindowTokens
                                else contextWindowForModel(model),
                            maxTokens = if (connection.provider == AgentProvider.OLLAMA) connection.tuning.answerMaxTokens else DEFAULT_MAX_TOKENS,
                            temperature = if (connection.provider == AgentProvider.OLLAMA) connection.tuning.temperature else null,
                        )
                        ragSettings = ragSettings.copy(evidenceVerifierModel =
                            if (connection.provider == AgentProvider.OLLAMA) model else "claude-sonnet-5")
                        agentConnection = connection
                    },
                    mcpEnabled = mcpEnabled,
                    onMcpEnabledChange = { mcpEnabled = it },
                    ragEnabled = ragEnabled,
                    onRagEnabledChange = { ragEnabled = it },
                    documentAddress = documentAddress,
                    onDocumentAddressChange = { documentAddress = it },
                    ragSettings = ragSettings,
                    onRagSettingsChange = { ragSettings = it },
                    onCreateAgent = { config ->
                        generalAgentConfig = config
                        generalAgent = generalRuntime.spawn(
                            config = config,
                            historyStore = agentHistoryStore,
                            invariantStore = agentInvariantStore,
                            toolProvider = knowledgeTools,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                1 -> FileTaskScreen(documentAddress, { documentAddress = it }, Modifier.fillMaxSize())
                2 -> SupportScreen(documentAddress, { documentAddress = it }, Modifier.fillMaxSize())
                3 -> PrReviewScreen(documentAddress, { documentAddress = it }, Modifier.fillMaxSize())
                4 -> DocumentIndexScreen(Modifier.fillMaxSize())
                5 -> InvariantLabScreen(
                    agent = generalAgent,
                    modifier = Modifier.fillMaxSize(),
                )
                6 -> ChatScreen(apiKey, conversationAgent, Modifier.fillMaxSize())
                7 -> FormatLabScreen(apiKey, Modifier.fillMaxSize())
                8 -> ReasoningLabScreen(apiKey, Modifier.fillMaxSize())
                9 -> TemperatureLabScreen(apiKey, Modifier.fillMaxSize())
                else -> ModelLabScreen(apiKey, Modifier.fillMaxSize())
            }
        }
    }
}
