package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.ContextCompressionConfig
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.contextWindowForModel
import com.mau89.talkloop.llm.displayName
import kotlinx.coroutines.launch

private enum class ContextMode(val title: String, val description: String) {
    SLIDING(
        "Sliding Window",
        "Только последние N сообщений; ранние детали удаляются безвозвратно.",
    ),
    FACTS(
        "Sticky Facts",
        "Key-value facts обновляются после каждой реплики + свежий хвост диалога.",
    ),
    BRANCHING(
        "Branching",
        "Checkpoint порождает независимые ветки, между которыми можно переключаться.",
    ),
}

/** День 10: три стратегии контекста без summary. */
@Composable
fun AgentLabScreen(
    apiKey: String,
    agentRuntime: AgentRuntime,
    agent: TalkLoopAgent,
    config: AgentConfig,
    onCreateAgent: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentCount by agentRuntime.agentCount.collectAsState()
    val facts by agent.facts.collectAsState()
    var settingsExpanded by remember { mutableStateOf(agent.history.value.isEmpty()) }
    var systemPrompt by remember(config) { mutableStateOf(config.systemPrompt) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) { mutableStateOf(config.temperature?.toString().orEmpty()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var contextWindowTokens by remember(config) {
        mutableStateOf(config.contextWindowTokens.toString())
    }
    var mode by remember(config) {
        mutableStateOf(
            when (config.contextStrategy) {
                is ContextStrategy.StickyFacts -> ContextMode.FACTS
                ContextStrategy.Branching -> ContextMode.BRANCHING
                else -> ContextMode.SLIDING
            }
        )
    }
    var keepLastMessages by remember(config) {
        mutableStateOf(
            when (val strategy = config.contextStrategy) {
                is ContextStrategy.SlidingWindow -> strategy.keepLastMessages
                is ContextStrategy.StickyFacts -> strategy.keepLastMessages
                else -> 10
            }.toString()
        )
    }
    var maxFacts by remember(config) {
        mutableStateOf(
            (config.contextStrategy as? ContextStrategy.StickyFacts)?.maxFacts?.toString() ?: "12"
        )
    }

    val parsedTemperature = temperature.trim().replace(',', '.')
        .takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val parsedMaxTokens = maxTokens.trim().toIntOrNull()
    val parsedContextWindowTokens = contextWindowTokens.trim().toIntOrNull()
    val parsedKeepLastMessages = keepLastMessages.trim().toIntOrNull()
    val parsedMaxFacts = maxFacts.trim().toIntOrNull()
    val temperatureValid = temperature.isBlank() ||
        (parsedTemperature != null && parsedTemperature in 0.0..1.0)
    val commonNumbersValid = parsedMaxTokens != null && parsedMaxTokens > 0 &&
        parsedContextWindowTokens != null && parsedContextWindowTokens > 0
    val recentMessagesValid = parsedKeepLastMessages != null &&
        parsedKeepLastMessages > 0 && parsedKeepLastMessages % 2 == 0
    val factsValid = parsedMaxFacts != null && parsedMaxFacts > 0
    val strategyNumbersValid = when (mode) {
        ContextMode.BRANCHING -> true
        ContextMode.SLIDING -> recentMessagesValid
        ContextMode.FACTS -> recentMessagesValid && factsValid
    }
    val canCreate = systemPrompt.isNotBlank() && model.isNotBlank() &&
        temperatureValid && commonNumbersValid && strategyNumbersValid

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("День 10 · Контекст без summary", style = MaterialTheme.typography.titleMedium)
                Text(
                    config.contextStrategy.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                Text(if (settingsExpanded) "Скрыть" else "Стратегия")
            }
        }

        if (settingsExpanded) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Стратегия", style = MaterialTheme.typography.titleSmall)
                        ContextMode.entries.forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = mode == option, onClick = { mode = option })
                                Column(Modifier.weight(1f)) {
                                    Text(option.title)
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (mode != ContextMode.BRANCHING) {
                            OutlinedTextField(
                                value = keepLastMessages,
                                onValueChange = { keepLastMessages = it },
                                label = { Text("Последние N сообщений") },
                                supportingText = { Text("Положительное чётное число") },
                                isError = !recentMessagesValid,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (mode == ContextMode.FACTS) {
                            OutlinedTextField(
                                value = maxFacts,
                                onValueChange = { maxFacts = it },
                                label = { Text("Максимум facts") },
                                supportingText = { Text("Цели, ограничения, решения, предпочтения") },
                                isError = !factsValid,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text("Системный промпт") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("Модель") },
                            isError = model.isBlank(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = temperature,
                                onValueChange = { temperature = it },
                                label = { Text("Temperature") },
                                supportingText = { Text("Пусто или 0–1") },
                                isError = !temperatureValid,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = maxTokens,
                                onValueChange = { maxTokens = it },
                                label = { Text("Max tokens") },
                                isError = parsedMaxTokens == null || parsedMaxTokens <= 0,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = contextWindowTokens,
                            onValueChange = { contextWindowTokens = it },
                            label = { Text("Лимит окна контекста") },
                            supportingText = {
                                Text("Для ${model.ifBlank { "модели" }}: ${contextWindowForModel(model)}")
                            },
                            isError = parsedContextWindowTokens == null ||
                                parsedContextWindowTokens <= 0,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Новый агент начнёт с пустой истории. Для честного сравнения " +
                                "повторяйте один сценарий из 10–15 реплик.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                val strategy = when (mode) {
                                    ContextMode.SLIDING -> ContextStrategy.SlidingWindow(
                                        parsedKeepLastMessages!!,
                                    )
                                    ContextMode.FACTS -> ContextStrategy.StickyFacts(
                                        keepLastMessages = parsedKeepLastMessages!!,
                                        maxFacts = parsedMaxFacts!!,
                                    )
                                    ContextMode.BRANCHING -> ContextStrategy.Branching
                                }
                                onCreateAgent(
                                    config.copy(
                                        systemPrompt = systemPrompt.trim(),
                                        model = model.trim(),
                                        temperature = parsedTemperature,
                                        maxTokens = parsedMaxTokens!!,
                                        contextWindowTokens = parsedContextWindowTokens!!,
                                        contextStrategy = strategy,
                                        contextCompression = ContextCompressionConfig(enabled = false),
                                    )
                                )
                                settingsExpanded = false
                            },
                            enabled = canCreate,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Создать агента с этой стратегией")
                        }
                    }
                }
            }
        } else {
            if (config.contextStrategy is ContextStrategy.StickyFacts) FactsCard(facts)
            if (config.contextStrategy == ContextStrategy.Branching) BranchingControls(agent)
            ChatScreen(
                apiKey = apiKey,
                agent = agent,
                modifier = Modifier.weight(1f),
                title = "Сбор требований",
                showRecap = false,
                inputPlaceholder = "Добавьте требование…",
                sendButtonText = "Отправить",
                showStatistics = true,
                agentCount = agentCount,
                strategyLabel = config.contextStrategy.displayName(),
                factsEnabled = config.contextStrategy is ContextStrategy.StickyFacts,
            )
        }
    }
}

@Composable
private fun FactsCard(facts: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Facts (${facts.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Скрыть" else "Показать")
                }
            }
            if (expanded) {
                if (facts.isEmpty()) {
                    Text(
                        "Память заполнится после первой реплики.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    facts.forEach { (key, value) ->
                        Text("$key = $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchingControls(agent: TalkLoopAgent) {
    val scope = rememberCoroutineScope()
    val branches by agent.branches.collectAsState()
    val activeBranchId by agent.activeBranchId.collectAsState()
    var checkpointName by remember { mutableStateOf("Развилка ТЗ") }
    var branchAName by remember { mutableStateOf("Вариант A") }
    var branchBName by remember { mutableStateOf("Вариант B") }
    var error by remember { mutableStateOf<String?>(null) }
    var setupExpanded by remember(branches.size) { mutableStateOf(branches.size <= 1) }
    val activeBranch = branches.firstOrNull { it.id == activeBranchId }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ветки диалога", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Сейчас: ${activeBranch?.name ?: "—"} · " +
                            "${activeBranch?.messages?.size ?: 0} сообщ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { setupExpanded = !setupExpanded }) {
                    Text(if (setupExpanded) "Свернуть" else "Новая развилка")
                }
            }

            Text(
                "Нажмите название, чтобы переключить активную ветку:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                branches.forEach { branch ->
                    FilterChip(
                        selected = branch.id == activeBranchId,
                        onClick = {
                            if (branch.id != activeBranchId) {
                                scope.launch {
                                    error = null
                                    runCatching { agent.switchBranch(branch.id) }
                                        .onFailure { error = it.message }
                                }
                            }
                        },
                        label = {
                            Text((if (branch.id == activeBranchId) "✓ " else "") + branch.name)
                        },
                    )
                }
            }

            if (setupExpanded) {
                OutlinedTextField(
                    value = checkpointName,
                    onValueChange = { checkpointName = it },
                    label = { Text("Checkpoint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = branchAName,
                        onValueChange = { branchAName = it },
                        label = { Text("Ветка A") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = branchBName,
                        onValueChange = { branchBName = it },
                        label = { Text("Ветка B") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            error = null
                            runCatching {
                                val checkpoint = agent.createCheckpoint(checkpointName)
                                val first = agent.createBranch(branchAName, checkpoint.id)
                                agent.createBranch(branchBName, checkpoint.id)
                                agent.switchBranch(first.id)
                            }.onSuccess {
                                setupExpanded = false
                            }.onFailure { error = it.message }
                        }
                    },
                    enabled = branchAName.isNotBlank() && branchBName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Checkpoint → создать две ветки")
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
