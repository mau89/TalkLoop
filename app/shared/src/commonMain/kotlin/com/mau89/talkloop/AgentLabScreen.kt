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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentConfig
import com.mau89.talkloop.llm.AgentRuntime
import com.mau89.talkloop.llm.ContextCompressionConfig
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.contextWindowForModel

/**
 * День 9. Интерфейс агента с долговременной памятью и сжатием контекста.
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
    onCreateAgent: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentCount by agentRuntime.agentCount.collectAsState()
    var settingsExpanded by remember { mutableStateOf(agent.history.value.isEmpty()) }
    var systemPrompt by remember(config) { mutableStateOf(config.systemPrompt) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) {
        mutableStateOf(config.temperature?.toString().orEmpty())
    }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var contextWindowTokens by remember(config) {
        mutableStateOf(config.contextWindowTokens.toString())
    }
    var compressionEnabled by remember(config) {
        mutableStateOf(config.contextCompression.enabled)
    }
    var keepLastMessages by remember(config) {
        mutableStateOf(config.contextCompression.keepLastMessages.toString())
    }
    var summaryMaxTokens by remember(config) {
        mutableStateOf(config.contextCompression.summaryMaxTokens.toString())
    }

    val parsedTemperature = temperature.trim()
        .replace(',', '.')
        .takeIf(String::isNotEmpty)
        ?.toDoubleOrNull()
    val parsedMaxTokens = maxTokens.trim().toIntOrNull()
    val parsedContextWindowTokens = contextWindowTokens.trim().toIntOrNull()
    val parsedKeepLastMessages = keepLastMessages.trim().toIntOrNull()
    val parsedSummaryMaxTokens = summaryMaxTokens.trim().toIntOrNull()
    val temperatureValid = temperature.isBlank() ||
        (parsedTemperature != null && parsedTemperature in 0.0..1.0)
    val maxTokensValid = parsedMaxTokens != null && parsedMaxTokens > 0
    val contextWindowValid = parsedContextWindowTokens != null &&
        parsedContextWindowTokens > 0
    val compressionNumbersValid = parsedKeepLastMessages != null &&
        parsedKeepLastMessages > 0 && parsedKeepLastMessages % 2 == 0 &&
        parsedSummaryMaxTokens != null && parsedSummaryMaxTokens > 0
    val canCreate = systemPrompt.isNotBlank() && model.isNotBlank() &&
        temperatureValid && maxTokensValid && contextWindowValid && compressionNumbersValid

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
                "max tokens: ${config.maxTokens} · окно: ${config.contextWindowTokens} · " +
                if (config.contextCompression.enabled) {
                    "сжатие: последние ${config.contextCompression.keepLastMessages}"
                } else {
                    "сжатие: выкл"
                },
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
                        OutlinedTextField(
                            value = contextWindowTokens,
                            onValueChange = { contextWindowTokens = it },
                            label = { Text("Лимит окна контекста") },
                            supportingText = {
                                Text(
                                    "Для ${model.ifBlank { "модели" }} обычно: " +
                                        contextWindowForModel(model) +
                                        ". Поставьте 200–500 для безопасного теста переполнения."
                                )
                            },
                            isError = !contextWindowValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Сжимать историю", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Summary отдельно + свежие сообщения дословно",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = compressionEnabled,
                                onCheckedChange = { compressionEnabled = it },
                            )
                        }
                        OutlinedTextField(
                            value = keepLastMessages,
                            onValueChange = { keepLastMessages = it },
                            label = { Text("Последние N сообщений") },
                            supportingText = {
                                Text("Чётное число; всё более старое сразу уйдёт в summary")
                            },
                            isError = parsedKeepLastMessages == null ||
                                parsedKeepLastMessages <= 0 || parsedKeepLastMessages % 2 != 0,
                            enabled = compressionEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = summaryMaxTokens,
                            onValueChange = { summaryMaxTokens = it },
                            label = { Text("Max tokens для summary") },
                            supportingText = { Text("Обычно 256–512") },
                            isError = parsedSummaryMaxTokens == null || parsedSummaryMaxTokens <= 0,
                            enabled = compressionEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Для сравнения сначала пройдите один сценарий без сжатия, " +
                                "затем создайте агента со сжатием и повторите его. После первого " +
                                "summary карточка покажет точный контекст до и после; качество " +
                                "проверьте вопросом о ранних фактах.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Новый агент начнёт с пустой истории и заменит сохранённый диалог.",
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
                                        contextCompression = ContextCompressionConfig(
                                            enabled = compressionEnabled,
                                            keepLastMessages = parsedKeepLastMessages!!,
                                            summaryMaxTokens = parsedSummaryMaxTokens!!,
                                        ),
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
                modifier = Modifier.weight(1f),
                title = "День 9 · Сжатие истории",
                showRecap = false,
                inputPlaceholder = "Введите сообщение…",
                sendButtonText = "Отправить",
                showStatistics = true,
                agentCount = agentCount,
                compressionEnabled = config.contextCompression.enabled,
            )
        }
    }
}
