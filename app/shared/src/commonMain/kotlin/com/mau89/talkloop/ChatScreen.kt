package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.ChatMessage
import com.mau89.talkloop.llm.RECAP_REQUEST
import com.mau89.talkloop.llm.TalkLoopAgent
import com.mau89.talkloop.llm.TokenTurnOutcome
import com.mau89.talkloop.llm.formatUsd
import kotlinx.coroutines.launch

/**
 * Экран диалога: то же, что делает CLI, только с текстовым вводом на телефоне.
 * История приходит снаружи (App.kt), чтобы пережить переключение вкладки;
 * профиль ученика и SRS появятся, когда формат подтвердится.
 */
@Composable
fun ChatScreen(
    apiKey: String,
    agent: TalkLoopAgent,
    modifier: Modifier = Modifier,
    title: String = "TalkLoop",
    showRecap: Boolean = true,
    inputPlaceholder: String = "Say something in English…",
    sendButtonText: String = "Send",
    showStatistics: Boolean = false,
    agentCount: Int? = null,
) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val history by agent.history.collectAsState()
    val statistics by agent.statistics.collectAsState()
    var input by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Запрос разбора не показываем в ленте — это служебная реплика, а не часть разговора.
    val visible = history.filter { it.text != RECAP_REQUEST } + listOfNotNull(pendingMessage)

    fun send(text: String) {
        if (waiting || text.isBlank()) return
        error = null
        waiting = true
        pendingMessage = text.takeUnless { it == RECAP_REQUEST }
            ?.let { ChatMessage(fromUser = true, text = it) }
        scope.launch {
            try {
                agent.respond(text)
            } catch (e: Exception) {
                // Реплику возвращаем в поле ввода, чтобы написанное не пропало.
                if (text != RECAP_REQUEST) input = text
                error = e.message ?: "Не дошло до модели"
            } finally {
                pendingMessage = null
                waiting = false
            }
        }
    }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (showRecap) {
                TextButton(
                    onClick = { send(RECAP_REQUEST) },
                    enabled = !waiting && history.isNotEmpty(),
                ) {
                    Text("Разбор")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible) { message -> Bubble(message) }
        }

        if (waiting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        error?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !waiting,
                placeholder = { Text(inputPlaceholder) },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    input = ""
                    send(text)
                },
                enabled = !waiting && input.isNotBlank(),
            ) {
                Text(sendButtonText)
            }
        }

        if (showStatistics) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("Токены и стоимость", style = MaterialTheme.typography.titleSmall)
                    statistics.lastTurn?.let { turn ->
                        val status = when (turn.outcome) {
                            TokenTurnOutcome.COMPLETED -> "готово"
                            TokenTurnOutcome.REJECTED_BEFORE_SEND -> "переполнение до отправки"
                            TokenTurnOutcome.RESPONSE_REACHED_CONTEXT_LIMIT ->
                                "ответ оборван окном контекста"
                        }
                        Text(
                            "Ход ${turn.turn}: текущий запрос ${turn.requestTokens} · " +
                                "вся история ${turn.inputTokens}/${turn.contextWindowTokens} · " +
                                "ответ ${turn.outputTokens}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Контекст ${formatPercent(turn.contextUsage)} · " +
                                "стоимость хода ${formatUsd(turn.costUsd)} · " +
                                "stop: ${turn.stopReason ?: "до API"} · $status",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (turn.outcome == TokenTurnOutcome.COMPLETED) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    } ?: Text(
                        "Отправьте первую реплику — здесь появятся три отдельных счётчика.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "За диалог: ${statistics.requestCount} попыток · " +
                            "${statistics.inputTokens} вход / ${statistics.outputTokens} выход · " +
                            "${statistics.totalTokens} токенов · ${formatUsd(statistics.totalCostUsd)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (statistics.turns.size > 1) {
                        Text(
                            text = "Рост полного входа: " + statistics.turns.takeLast(6)
                                .joinToString(" → ") { turn ->
                                    "${turn.turn}: ${turn.inputTokens}"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Создано агентов: ${agentCount ?: 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatPercent(value: Double): String {
    val tenths = (value * 1_000.0).toInt().coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}%"
}

@Composable
private fun Bubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
internal fun MissingKeyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Нет ключа Anthropic API", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Положите ключ в secrets.properties в корне проекта " +
                "(строка anthropic.api.key=...) и пересоберите приложение.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
