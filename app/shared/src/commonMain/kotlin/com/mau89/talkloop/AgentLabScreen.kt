package com.mau89.talkloop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.AssistantPreferences
import com.mau89.talkloop.llm.ContextCompressionConfig
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.LongTermMemoryKind
import com.mau89.talkloop.llm.MemoryLayer
import com.mau89.talkloop.llm.MemoryWrite
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.UserProfile
import com.mau89.talkloop.llm.contextWindowForModel
import com.mau89.talkloop.llm.displayName
import kotlinx.coroutines.launch

/** День 12: профиль пользователя поверх трёх независимых слоёв памяти агента. */
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
    val strategy = config.contextStrategy as? ContextStrategy.MemoryLayers
    var settingsExpanded by remember { mutableStateOf(strategy == null) }
    var systemPrompt by remember(config) { mutableStateOf(config.systemPrompt) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) { mutableStateOf(config.temperature?.toString().orEmpty()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var contextWindowTokens by remember(config) {
        mutableStateOf(config.contextWindowTokens.toString())
    }
    var keepLastMessages by remember(config) {
        mutableStateOf((strategy?.keepLastMessages ?: 10).toString())
    }

    val parsedTemperature = temperature.trim().replace(',', '.')
        .takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val parsedMaxTokens = maxTokens.trim().toIntOrNull()
    val parsedContextWindowTokens = contextWindowTokens.trim().toIntOrNull()
    val parsedKeepLastMessages = keepLastMessages.trim().toIntOrNull()
    val temperatureValid = temperature.isBlank() ||
        (parsedTemperature != null && parsedTemperature in 0.0..1.0)
    val recentMessagesValid = parsedKeepLastMessages != null &&
        parsedKeepLastMessages > 0 && parsedKeepLastMessages % 2 == 0
    val canApply = systemPrompt.isNotBlank() && model.isNotBlank() && temperatureValid &&
        parsedMaxTokens != null && parsedMaxTokens > 0 &&
        parsedContextWindowTokens != null && parsedContextWindowTokens > 0 &&
        recentMessagesValid

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("День 12 · Персонализация", style = MaterialTheme.typography.titleMedium)
                Text(
                    strategy?.displayName() ?: "Включите режим Memory Layers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                Text(if (settingsExpanded) "Скрыть" else "Настройки")
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
                        Text("Память и профиль включены всегда", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Краткосрочная: успешные реплики автоматически. Рабочая: данные " +
                                "задачи. Долговременная: решения и знания. Структурированный " +
                                "профиль автоматически применяется к каждому ответу.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = keepLastMessages,
                            onValueChange = { keepLastMessages = it },
                            label = { Text("Краткосрочная память, сообщений") },
                            supportingText = { Text("Положительное чётное число") },
                            isError = !recentMessagesValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = temperature,
                                onValueChange = { temperature = it },
                                label = { Text("Temperature") },
                                supportingText = { Text("Пусто или 0–1") },
                                isError = !temperatureValid,
                                singleLine = true,
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
                            "Применение настроек не удаляет сохранённые слои памяти.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                onCreateAgent(
                                    config.copy(
                                        systemPrompt = systemPrompt.trim(),
                                        model = model.trim(),
                                        temperature = parsedTemperature,
                                        maxTokens = parsedMaxTokens!!,
                                        contextWindowTokens = parsedContextWindowTokens!!,
                                        contextStrategy = ContextStrategy.MemoryLayers(
                                            keepLastMessages = parsedKeepLastMessages!!,
                                        ),
                                        contextCompression = ContextCompressionConfig(enabled = false),
                                    )
                                )
                                settingsExpanded = false
                            },
                            enabled = canApply,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Применить Memory Layers")
                        }
                    }
                }
            }
        } else if (strategy != null) {
            MemoryLayersCard(agent)
            ChatScreen(
                apiKey = apiKey,
                agent = agent,
                modifier = Modifier.weight(1f),
                title = "Диалог текущей задачи",
                showRecap = false,
                inputPlaceholder = "Сообщение в краткосрочную память…",
                sendButtonText = "Отправить",
                showStatistics = true,
                agentCount = agentCount,
                strategyLabel = strategy.displayName(),
            )
        }
    }
}

@Composable
private fun MemoryLayersCard(agent: TalkLoopAgent) {
    val scope = rememberCoroutineScope()
    val shortTerm by agent.history.collectAsState()
    val working by agent.workingMemory.collectAsState()
    val longTerm by agent.longTermMemory.collectAsState()
    val profiles by agent.userProfiles.collectAsState()
    val profile by agent.userProfile.collectAsState()
    var expanded by remember { mutableStateOf(true) }
    var selectedLayer by remember { mutableStateOf(MemoryLayer.WORKING) }
    var selectedKind by remember { mutableStateOf(LongTermMemoryKind.PROFILE) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var taskName by remember(agent) { mutableStateOf(working.taskName.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth().heightIn(max = 520.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Слои памяти", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Краткая ${shortTerm.size} · рабочая ${working.items.size} · " +
                            "долгая ${longTerm.items.size} · профили ${profiles.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    working.taskName?.let {
                        Text("Текущая задача: $it", style = MaterialTheme.typography.labelSmall)
                    }
                    profile?.let {
                        Text(
                            "Профиль: ${it.displayName ?: it.id}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Управлять")
                }
            }

            if (expanded) {
                UserProfileEditor(
                    agent = agent,
                    profiles = profiles,
                    currentProfile = profile,
                )

                Text(
                    "Краткосрочная память — лента ниже; сохраняется автоматически после " +
                        "успешного ответа и ограничена последними сообщениями.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemoryLayer.entries.forEach { layer ->
                        FilterChip(
                            selected = selectedLayer == layer,
                            onClick = { selectedLayer = layer },
                            label = {
                                Text(if (layer == MemoryLayer.WORKING) "Рабочая" else "Долговременная")
                            },
                        )
                    }
                }

                if (selectedLayer == MemoryLayer.LONG_TERM) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LongTermMemoryKind.entries.forEach { kind ->
                            FilterChip(
                                selected = selectedKind == kind,
                                onClick = { selectedKind = kind },
                                label = { Text(kind.title()) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Ключ") },
                    placeholder = { Text("например, язык_ответа") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Что запомнить") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            val write = if (selectedLayer == MemoryLayer.WORKING) {
                                MemoryWrite.Working(key, value)
                            } else {
                                MemoryWrite.LongTerm(selectedKind, key, value)
                            }
                            runCatching { agent.remember(write) }
                                .onSuccess {
                                    status = "Сохранено: ${selectedLayer.title()}"
                                    key = ""
                                    value = ""
                                }
                                .onFailure { status = it.message }
                        }
                    },
                    enabled = key.isNotBlank() && value.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Сохранить в выбранный слой")
                }

                MemoryItems(
                    agent = agent,
                    working = working.items.map { it.key to it.value },
                    longTerm = longTerm.items.map { Triple(it.kind, it.key, it.value) },
                )

                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text("Название следующей задачи") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { agent.startNewTask(taskName) }
                                .onSuccess {
                                    status = "Новая задача начата: диалог и рабочая память очищены"
                                    taskName = ""
                                }
                                .onFailure { status = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Начать новую задачу · сохранить long-term")
                }

                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserProfileEditor(
    agent: TalkLoopAgent,
    profiles: List<UserProfile>,
    currentProfile: UserProfile?,
) {
    val scope = rememberCoroutineScope()
    var profileId by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.id ?: "default")
    }
    var displayName by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.displayName.orEmpty())
    }
    var language by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.preferences?.language.orEmpty())
    }
    var style by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.preferences?.style.orEmpty())
    }
    var responseFormat by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.preferences?.format.orEmpty())
    }
    var constraints by remember(agent, currentProfile?.id) {
        mutableStateOf(currentProfile?.preferences?.constraints?.joinToString("\n").orEmpty())
    }
    var status by remember(agent) { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Профиль пользователя", style = MaterialTheme.typography.titleSmall)
            Text(
                "Сохраните несколько вариантов и выбирайте активный перед диалогом.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = currentProfile == null,
                    onClick = {
                        scope.launch {
                            agent.selectUserProfile(null)
                            status = "Персонализация отключена; профили сохранены"
                        }
                    },
                    label = { Text("Без профиля") },
                )
                profiles.forEach { savedProfile ->
                    FilterChip(
                        selected = currentProfile?.id == savedProfile.id,
                        onClick = {
                            scope.launch {
                                agent.selectUserProfile(savedProfile.id)
                                status = "Активен профиль: " +
                                    (savedProfile.displayName ?: savedProfile.id)
                            }
                        },
                        label = { Text(savedProfile.displayName ?: savedProfile.id) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profileId,
                    onValueChange = { profileId = it },
                    label = { Text("ID профиля") },
                    supportingText = {
                        if (currentProfile != null) Text("Чтобы создать новый, выберите «Без профиля»")
                    },
                    enabled = currentProfile == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Язык ответа") },
                placeholder = { Text("например, русский") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = style,
                onValueChange = { style = it },
                label = { Text("Стиль") },
                placeholder = { Text("например, кратко и по делу") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = responseFormat,
                onValueChange = { responseFormat = it },
                label = { Text("Формат") },
                placeholder = { Text("например, маркированный список") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = constraints,
                onValueChange = { constraints = it },
                label = { Text("Ограничения · по одному на строку") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            agent.setUserProfile(
                                UserProfile(
                                    id = profileId,
                                    displayName = displayName,
                                    preferences = AssistantPreferences(
                                        language = language,
                                        style = style,
                                        format = responseFormat,
                                        constraints = constraints.lines(),
                                    ),
                                )
                            )
                        }.onSuccess {
                            status = "Профиль сохранён и будет применён к следующему ответу"
                        }.onFailure { status = it.message }
                    }
                },
                enabled = profileId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (currentProfile == null) "Сохранить новый профиль"
                    else "Обновить активный профиль"
                )
            }
            if (currentProfile != null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            val deletedName = currentProfile.displayName ?: currentProfile.id
                            runCatching { agent.deleteUserProfile(currentProfile.id) }
                                .onSuccess { status = "Профиль удалён: $deletedName" }
                                .onFailure { status = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить активный профиль")
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MemoryItems(
    agent: TalkLoopAgent,
    working: List<Pair<String, String>>,
    longTerm: List<Triple<LongTermMemoryKind, String, String>>,
) {
    val scope = rememberCoroutineScope()
    if (working.isEmpty() && longTerm.isEmpty()) {
        Text("Явно сохранённых записей пока нет.", style = MaterialTheme.typography.bodySmall)
        return
    }
    working.forEach { (key, value) ->
        MemoryItemRow("Рабочая · $key = $value") {
            scope.launch { agent.forget(MemoryLayer.WORKING, key) }
        }
    }
    longTerm.forEach { (kind, key, value) ->
        MemoryItemRow("${kind.title()} · $key = $value") {
            scope.launch { agent.forget(MemoryLayer.LONG_TERM, key, kind) }
        }
    }
}

@Composable
private fun MemoryItemRow(text: String, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDelete) { Text("Удалить") }
    }
}

private fun MemoryLayer.title(): String = when (this) {
    MemoryLayer.WORKING -> "рабочая память"
    MemoryLayer.LONG_TERM -> "долговременная память"
}

private fun LongTermMemoryKind.title(): String = when (this) {
    LongTermMemoryKind.PROFILE -> "Профиль"
    LongTermMemoryKind.DECISION -> "Решение"
    LongTermMemoryKind.KNOWLEDGE -> "Знание"
}
