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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AssistantPreferences
import com.mau89.talkloop.llm.ContextStrategy
import com.mau89.talkloop.llm.LongTermMemoryKind
import com.mau89.talkloop.llm.MemoryLayer
import com.mau89.talkloop.llm.MemoryWrite
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.TaskActor
import com.mau89.talkloop.llm.TaskEvent
import com.mau89.talkloop.llm.TaskEventType
import com.mau89.talkloop.llm.TaskStage
import com.mau89.talkloop.llm.UserProfile
import com.mau89.talkloop.llm.allowedEvents
import kotlinx.coroutines.launch

/** Основной экран агента: задача, память, профиль и диалог. */
@Composable
fun AgentLabScreen(
    apiKey: String,
    agent: TalkLoopAgent,
    config: AgentConfig,
    mcpEnabled: Boolean,
    onMcpEnabledChange: (Boolean) -> Unit,
    onCreateAgent: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val strategy = config.contextStrategy as? ContextStrategy.MemoryLayers
    var settingsExpanded by remember { mutableStateOf(strategy == null) }
    var systemPrompt by remember(config) { mutableStateOf(config.systemPrompt) }
    var resetStatus by remember(agent) { mutableStateOf<String?>(null) }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Агент", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Задача, профиль, MCP-инструменты и диалог",
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("MCP-погода и планировщик", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (mcpEnabled) {
                                        "Фоновый сбор и автоматические сводки, пока приложение открыто"
                                    } else {
                                        "MCP отключён: агент не подключается к серверу"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = mcpEnabled,
                                onCheckedChange = onMcpEnabledChange,
                            )
                        }
                        Text(
                            "Проверка: /weather-report Екатеринбург или /weather-watch Екатеринбург 1",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text(
                            "Модель, лимиты и стоимость используют безопасные настройки " +
                                "приложения и здесь не показываются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                onCreateAgent(
                                    config.copy(
                                        systemPrompt = systemPrompt.trim(),
                                    )
                                )
                                settingsExpanded = false
                            },
                            enabled = systemPrompt.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Сохранить настройки")
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Новый диалог", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Очистит текущую переписку и состояние задачи, затем пересоздаст " +
                                "агента. Инварианты, профили и долговременные знания сохранятся.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { agent.startNewTask() }
                                        .onSuccess {
                                            onCreateAgent(config)
                                            settingsExpanded = false
                                        }
                                        .onFailure { resetStatus = it.message }
                                }
                            },
                            enabled = strategy != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Очистить диалог и пересоздать агента") }
                        resetStatus?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        } else if (strategy != null) {
            TaskStateCard(agent)
            MemoryLayersCard(agent)
            ChatScreen(
                apiKey = apiKey,
                agent = agent,
                modifier = Modifier.weight(1f),
                title = "Диалог текущей задачи",
                showRecap = false,
                inputPlaceholder = "Сообщение в краткосрочную память…",
                sendButtonText = "Отправить",
                showStatistics = false,
                showToolActivity = true,
            )
        }
    }
}


@Composable
private fun TaskStateCard(agent: TalkLoopAgent) {
    val scope = rememberCoroutineScope()
    val taskState by agent.taskState.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var taskName by remember(agent, taskState?.taskName) {
        mutableStateOf(taskState?.taskName.orEmpty())
    }
    var currentStep by remember(agent, taskState?.currentStep) {
        mutableStateOf(taskState?.currentStep ?: "Сформировать план")
    }
    var expectedAction by remember(agent, taskState?.expectedAction) {
        mutableStateOf(taskState?.expectedAction ?: "Определить шаги и критерии готовности")
    }
    var expectedActor by remember(agent, taskState?.expectedActor) {
        mutableStateOf(taskState?.expectedActor ?: TaskActor.AGENT)
    }
    var completionCriteria by remember(agent, taskState?.completionCriteria) {
        mutableStateOf(taskState?.completionCriteria?.joinToString("\n").orEmpty())
    }
    var status by remember(agent) { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth().heightIn(max = 420.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Состояние задачи", style = MaterialTheme.typography.titleSmall)
                    Text(
                        taskState?.let {
                            "${it.stage.title()} · ${if (it.paused) "пауза" else "активна"} · rev ${it.revision}"
                        } ?: "Активная задача ещё не задана",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Управлять")
                }
            }

            if (expanded) {
                taskState?.let { state ->
                    TaskStageProgress(state.stage)
                    if (state.stage == TaskStage.DONE) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Задача завершена · done",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text("Задача") },
                    enabled = taskState == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = currentStep,
                    onValueChange = { currentStep = it },
                    label = { Text("Текущий шаг") },
                    enabled = taskState?.paused != true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = expectedAction,
                    onValueChange = { expectedAction = it },
                    label = { Text("Ожидаемое действие") },
                    enabled = taskState?.paused != true,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Исполнитель следующего действия", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskActor.entries.forEach { actor ->
                        FilterChip(
                            selected = expectedActor == actor,
                            onClick = { expectedActor = actor },
                            enabled = taskState?.paused != true,
                            label = { Text(actor.title()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = completionCriteria,
                    onValueChange = { completionCriteria = it },
                    label = { Text("Критерии готовности · по одному на строку") },
                    enabled = taskState?.paused != true,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                val criteria = completionCriteria.lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)

                if (taskState == null) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    agent.beginTask(
                                        name = taskName,
                                        currentStep = currentStep,
                                        expectedAction = expectedAction,
                                        expectedActor = expectedActor,
                                        completionCriteria = criteria,
                                    )
                                }.onSuccess {
                                    status = "Задача начата на этапе planning"
                                }.onFailure { status = it.message }
                            }
                        },
                        enabled = taskName.isNotBlank() && currentStep.isNotBlank() &&
                            expectedAction.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Начать задачу") }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                val state = taskState!!
                                runCatching {
                                    agent.dispatchTaskEvent(
                                        TaskEvent(
                                            id = "ui-${state.revision + 1}-progress",
                                            type = TaskEventType.PROGRESS_UPDATED,
                                            currentStep = currentStep,
                                            expectedAction = expectedAction,
                                            expectedActor = expectedActor,
                                            completionCriteria = criteria,
                                            expectedRevision = state.revision,
                                        )
                                    )
                                }
                                    .onSuccess { status = "Текущая точка сохранена" }
                                    .onFailure { status = it.message }
                            }
                        },
                        enabled = !taskState!!.paused && currentStep.isNotBlank() &&
                            expectedAction.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить текущий шаг") }

                    taskState!!.stage.allowedEvents().forEach { eventType ->
                        Button(
                            onClick = {
                                scope.launch {
                                    val state = taskState!!
                                    runCatching {
                                        agent.dispatchTaskEvent(
                                            TaskEvent(
                                                id = "ui-${state.revision + 1}-${eventType.name}",
                                                type = eventType,
                                                currentStep = currentStep,
                                                expectedAction = expectedAction,
                                                expectedActor = expectedActor,
                                                completionCriteria = criteria,
                                                expectedRevision = state.revision,
                                            )
                                        )
                                    }.onSuccess {
                                        status = "Событие применено: ${eventType.title()}"
                                    }.onFailure { status = it.message }
                                }
                            },
                            enabled = !taskState!!.paused && currentStep.isNotBlank() &&
                                expectedAction.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val destination = eventType.destinationFrom(taskState!!.stage)
                            Text(
                                "${eventType.title()}: ${taskState!!.stage.title()} → " +
                                    destination.title()
                            )
                        }
                    }

                    if (!taskState!!.paused && taskState!!.stage != TaskStage.DONE &&
                        taskState!!.pendingProposal == null
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "Агент проверяет критерии…"
                                    runCatching { agent.requestTaskTransitionProposal() }
                                        .onSuccess { proposal ->
                                            status = if (proposal.event == null) {
                                                "Агент рекомендует остаться на текущем этапе"
                                            } else {
                                                "Предложение готово — подтвердите или отклоните"
                                            }
                                        }
                                        .onFailure { status = it.message }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Попросить агента оценить переход") }
                    }

                    taskState!!.pendingProposal?.let { proposal ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Предложение агента", style = MaterialTheme.typography.titleSmall)
                                Text(proposal.reason, style = MaterialTheme.typography.bodySmall)
                                proposal.event?.let { event ->
                                    Text(
                                        event.type.title(),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        "${taskState!!.stage.title()} → " +
                                            event.type.destinationFrom(taskState!!.stage).title(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        "Следующий шаг: ${event.currentStep}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                runCatching { agent.confirmTaskTransitionProposal() }
                                                    .onSuccess { status = "Предложение подтверждено" }
                                                    .onFailure { status = it.message }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Подтвердить этот переход") }
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { agent.dismissTaskTransitionProposal() }
                                                .onSuccess { status = "Предложение отклонено" }
                                                .onFailure { status = it.message }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Отклонить") }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val wasPaused = taskState!!.paused
                                val action = if (wasPaused) agent::resumeTask else agent::pauseTask
                                runCatching { action() }
                                    .onSuccess {
                                        status = if (wasPaused) {
                                            "Продолжено с сохранённого шага"
                                        } else {
                                            "Пауза: этап и шаг сохранены"
                                        }
                                    }
                                    .onFailure { status = it.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (taskState!!.paused) "Продолжить" else "Пауза") }

                    if (taskState!!.transitionHistory.isNotEmpty()) {
                        Text("Журнал событий", style = MaterialTheme.typography.titleSmall)
                        taskState!!.transitionHistory.takeLast(5).asReversed().forEach { record ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            record.event.type.title(),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(
                                            "rev ${record.revision}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${record.fromStage?.title() ?: "start"}  →  " +
                                            record.toStage.title(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (record.fromStage != record.toStage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    record.event.reason?.takeIf(String::isNotBlank)?.let { reason ->
                                        Text(reason, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                status?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TaskStageProgress(currentStage: TaskStage) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskStage.entries.forEachIndexed { index, stage ->
            if (index > 0) {
                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stage == currentStage) {
                Card {
                    Text(
                        stage.title(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    stage.title(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    var expanded by remember { mutableStateOf(false) }
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
                    Text(
                        "Память · ${shortTerm.size} / ${working.items.size} / " +
                            longTerm.items.size,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "краткая / рабочая / долговременная",
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

private fun TaskStage.title(): String = when (this) {
    TaskStage.PLANNING -> "planning"
    TaskStage.EXECUTION -> "execution"
    TaskStage.VALIDATION -> "validation"
    TaskStage.DONE -> "done"
}

private fun TaskActor.title(): String = when (this) {
    TaskActor.USER -> "Пользователь"
    TaskActor.AGENT -> "Агент"
    TaskActor.EXTERNAL_SYSTEM -> "Внешняя система"
}

private fun TaskEventType.title(): String = when (this) {
    TaskEventType.TASK_STARTED -> "Задача начата"
    TaskEventType.PROGRESS_UPDATED -> "Шаг обновлён"
    TaskEventType.PLAN_APPROVED -> "План утверждён"
    TaskEventType.EXECUTION_FINISHED -> "Исполнение завершено"
    TaskEventType.VALIDATION_PASSED -> "Проверка пройдена"
    TaskEventType.VALIDATION_FAILED -> "Проверка не пройдена"
    TaskEventType.PAUSED -> "Пауза"
    TaskEventType.RESUMED -> "Продолжение"
}

private fun TaskEventType.destinationFrom(currentStage: TaskStage): TaskStage = when (this) {
    TaskEventType.PLAN_APPROVED -> TaskStage.EXECUTION
    TaskEventType.EXECUTION_FINISHED -> TaskStage.VALIDATION
    TaskEventType.VALIDATION_PASSED -> TaskStage.DONE
    TaskEventType.VALIDATION_FAILED -> TaskStage.EXECUTION
    TaskEventType.TASK_STARTED,
    TaskEventType.PROGRESS_UPDATED,
    TaskEventType.PAUSED,
    TaskEventType.RESUMED,
    -> currentStage
}
