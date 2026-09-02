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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.ChatMessage
import com.mau89.talkloop.llm.RECAP_REQUEST
import kotlinx.coroutines.launch

/**
 * Экран диалога: то же, что делает CLI, только с текстовым вводом на телефоне.
 * История приходит снаружи (App.kt), чтобы пережить переключение вкладки;
 * профиль ученика и SRS появятся, когда формат подтвердится.
 */
@Composable
fun ChatScreen(
    apiKey: String,
    history: SnapshotStateList<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val client = remember(apiKey) { AnthropicLlmClient(apiKey) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Запрос разбора не показываем в ленте — это служебная реплика, а не часть разговора.
    val visible = history.filter { it.text != RECAP_REQUEST }

    fun send(text: String) {
        if (waiting || text.isBlank()) return
        error = null
        waiting = true
        history += ChatMessage(fromUser = true, text = text)
        scope.launch {
            try {
                val reply = client.reply(history.toList())
                history += ChatMessage(fromUser = false, text = reply)
            } catch (e: Exception) {
                // Реплику возвращаем в поле ввода, чтобы написанное не пропало.
                history.removeAt(history.lastIndex)
                if (text != RECAP_REQUEST) input = text
                error = e.message ?: "Не дошло до модели"
            } finally {
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
                text = "TalkLoop",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { send(RECAP_REQUEST) },
                enabled = !waiting && history.isNotEmpty(),
            ) {
                Text("Разбор")
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
                placeholder = { Text("Say something in English…") },
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
                Text("Send")
            }
        }
    }
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
