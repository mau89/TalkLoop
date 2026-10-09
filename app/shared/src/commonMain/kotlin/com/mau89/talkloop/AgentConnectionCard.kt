package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.isValidOllamaAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AgentConnectionCard(connection: AgentConnection, onApply: (AgentConnection) -> Unit) {
    var provider by remember(connection) { mutableStateOf(connection.provider) }
    var address by remember(connection) { mutableStateOf(connection.ollamaAddress) }
    var model by remember(connection) { mutableStateOf(connection.ollamaModel) }
    var checking by remember { mutableStateOf(false) }
    var status by remember(provider, address, model) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = provider == AgentProvider.CLAUDE || (isValidOllamaAddress(address) &&
        model.isNotBlank() && !model.trim().endsWith(":cloud", ignoreCase = true))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Модель агента", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentProvider.entries.forEach { option ->
                    FilterChip(selected = provider == option, enabled = !checking,
                        onClick = { provider = option },
                        label = { Text(if (option == AgentProvider.CLAUDE) "Claude" else "Ollama · локально") })
                }
            }
            if (provider == AgentProvider.OLLAMA) {
                Text("Модель отвечает на твоём Mac. Регистрация и ключ API не нужны.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("Адрес Ollama") }, singleLine = true, enabled = !checking,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Скачанная модель") }, singleLine = true, enabled = !checking,
                    modifier = Modifier.fillMaxWidth())
                TextButton(enabled = valid && !checking, onClick = {
                    val selectedAddress = address.trim()
                    val selectedModel = model.trim()
                    checking = true
                    status = null
                    scope.launch {
                        val client = createOllamaLlmClient(selectedAddress, selectedModel)
                        try {
                            status = client.checkConnection()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = e.message ?: "Ollama недоступна"
                        } finally {
                            client.close()
                            checking = false
                        }
                    }
                }) { Text(if (checking) "Проверяем…" else "Проверить подключение") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Button(enabled = valid && !checking, modifier = Modifier.fillMaxWidth(), onClick = {
                onApply(AgentConnection(provider, address.trim(), model.trim()))
                status = "Подключение сохранено"
            }) { Text("Использовать выбранную модель") }
        }
    }
}
