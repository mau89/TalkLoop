package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.ChatMessage
import com.mau89.talkloop.llm.TalkLoopAgent

/**
 * День 6. Отдельный интерфейс первого агента.
 *
 * Экран переиспользует чат, но работает со своей историей и без функций
 * предыдущего эксперимента вроде итогового разбора разговора.
 */
@Composable
fun AgentLabScreen(
    apiKey: String,
    agentRuntime: AgentRuntime,
    agent: TalkLoopAgent,
    config: AgentConfig,
    history: SnapshotStateList<ChatMessage>,
    onCreateAgent: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentCount by agentRuntime.agentCount.collectAsState()
    var settingsExpanded by remember { mutableStateOf(true) }
    var systemPrompt by remember(config) { mutableStateOf(config.systemPrompt) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) {
        mutableStateOf(config.temperature?.toString().orEmpty())
    }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }

    val parsedTemperature = temperature.trim()
        .replace(',', '.')
        .takeIf(String::isNotEmpty)
        ?.toDoubleOrNull()
    val parsedMaxTokens = maxTokens.trim().toIntOrNull()
    val temperatureValid = temperature.isBlank() ||
        (parsedTemperature != null && parsedTemperature in 0.0..1.0)
    val maxTokensValid = parsedMaxTokens != null && parsedMaxTokens > 0
    val canCreate = systemPrompt.isNotBlank() && model.isNotBlank() &&
        temperatureValid && maxTokensValid

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Настройка агента", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                Text(if (settingsExpanded) "Скрыть" else "Изменить")
            }
        }
        Text(
            text = "${config.model} · temperature: ${config.temperature ?: "по умолчанию"} · " +
                "max tokens: ${config.maxTokens}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (settingsExpanded) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
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
                            supportingText = { Text("Например: claude-haiku-4-5") },
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
                                placeholder = { Text("по умолчанию") },
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
                            supportingText = { Text("Больше нуля") },
                            isError = !maxTokensValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        }
                        Text(
                            text = "При создании начнётся новая независимая история диалога.",
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
                                    )
                                )
                                settingsExpanded = false
                            },
                            enabled = canCreate,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Создать нового агента")
                        }
                    }
                }
            }
        } else {
            ChatScreen(
                apiKey = apiKey,
                agent = agent,
                history = history,
                modifier = Modifier.weight(1f),
                title = "День 6 · Первый агент",
                showRecap = false,
                inputPlaceholder = "Введите сообщение…",
                sendButtonText = "Отправить",
                showStatistics = true,
                agentCount = agentCount,
            )
        }
    }
}
