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
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.mau89.talkloop.llm.OllamaTuning
import com.mau89.talkloop.llm.COOKBOOK_OLLAMA_MODEL
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
    var form by remember(connection) { mutableStateOf(OllamaTuningForm.from(connection.tuning)) }
    val tuning = form.parse()
    var checking by remember { mutableStateOf(false) }
    var status by remember(provider, address, model, form) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = provider == AgentProvider.CLAUDE || (isValidOllamaAddress(address) &&
        model.isNotBlank() && !model.trim().endsWith(":cloud", ignoreCase = true) && tuning != null)
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
                Text("Параметры локальной модели · День 29", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !checking, onClick = {
                        model = "qwen3.5:9b"
                        form = OllamaTuningForm.from(OllamaTuningForm.baseline)
                    }) { Text("До настройки · Q4") }
                    TextButton(enabled = !checking, onClick = {
                        model = COOKBOOK_OLLAMA_MODEL
                        form = OllamaTuningForm.from(OllamaTuning())
                    }) { Text("День 29 · Q8") }
                }
                Text("Кнопки заполняют поля. Для применения нажми «Сохранить и применить». Модель должна быть скачана на Mac.",
                    style = MaterialTheme.typography.bodySmall)
                TuningNumber("Температура · от 0 до 2", "Меньше значение — меньше случайных формулировок.", form.temperature, !checking, true) {
                    form = form.copy(temperature = it)
                }
                TuningNumber("Штраф за повторения · от −2 до 2", "Для дословных цитат используй 0.", form.presencePenalty, !checking, true) {
                    form = form.copy(presencePenalty = it)
                }
                TuningNumber("Контекст · токены", "Вмещает инструкции, историю, фрагменты книги и ответ.", form.context, !checking) {
                    form = form.copy(context = it)
                }
                TuningNumber("Лимит ответа · токены", "Максимальная длина ответа агента.", form.answer, !checking) {
                    form = form.copy(answer = it)
                }
                TuningNumber("Лимит памяти · токены", "Служебный ответ для цели, ограничений и уточнений.", form.memory, !checking) {
                    form = form.copy(memory = it)
                }
                TuningNumber("Лимит проверки · токены", "Служебный ответ для проверки фактов и цитат.", form.verdict, !checking) {
                    form = form.copy(verdict = it)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Инструкции для книги", modifier = Modifier.weight(1f))
                    Switch(checked = form.recipePrompts, enabled = !checking, onCheckedChange = { form = form.copy(recipePrompts = it) })
                }
                Text("Краткие факты, непрерывные цитаты и сохранение оговорок источника.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Размышления модели", modifier = Modifier.weight(1f))
                    Switch(checked = form.thinking, enabled = !checking, onCheckedChange = { form = form.copy(thinking = it) })
                }
                Text("Могут увеличить время ответа. Профиль Дня 29 отключает их.", style = MaterialTheme.typography.bodySmall)
                if (tuning == null) Text("Проверь числа: температура 0–2, штраф −2–2; контекст и лимиты — положительные целые, каждый лимит меньше контекста.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = valid && !checking, onClick = {
                    val selectedAddress = address.trim()
                    val selectedModel = model.trim()
                    checking = true
                    status = null
                    scope.launch {
                        val client = createOllamaLlmClient(selectedAddress, selectedModel, checkNotNull(tuning))
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
            }
            Button(enabled = valid && !checking, modifier = Modifier.fillMaxWidth(), onClick = {
                onApply(AgentConnection(provider, address.trim(), model.trim(), tuning ?: connection.tuning))
                status = "Настройки сохранены и применены"
            }) { Text("Сохранить и применить") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TuningNumber(label: String, hint: String, value: String, enabled: Boolean,
    decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(label) }, supportingText = { Text(hint) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
