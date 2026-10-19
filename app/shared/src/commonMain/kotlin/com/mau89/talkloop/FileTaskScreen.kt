package com.mau89.talkloop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.FileTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val USAGE_GOAL = "Найди все места использования SupportAssistant, прочитай реализацию, вызовы и тесты, подготовь краткий отчёт с путями и строками."
private const val API_GOAL = "Актуализируй справку по HTTP API поддержки на основе реализации, типов данных и тестов. Подготовь отдельный документ проекта; существующий документ обнови."

@Composable
fun FileTaskScreen(address: String, onAddressChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val client = remember { FileTaskClient() }
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf(USAGE_GOAL) }
    var task by remember { mutableStateOf<FileTask?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var showDiff by remember(task?.id) { mutableStateOf(true) }
    var showEvents by remember(task?.id) { mutableStateOf(false) }
    DisposableEffect(client) { onDispose { client.close() } }

    suspend fun refresh() {
        busy = true; error = null
        try { task = client.latest(address); refreshKey++ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(address, task?.id, task?.isRunning, refreshKey) {
        val id = task?.takeIf { it.isRunning }?.id ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            try {
                val updated = client.get(address, id); task = updated; error = null
                if (!updated.isRunning) break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message; break }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Файлы проекта", style = MaterialTheme.typography.headlineSmall)
        Text("Опишите цель. Ассистент сам ищет и читает файлы, готовит документ и diff. Затем результат можно сохранить в docs/generated/day34.")
        OutlinedTextField(value = address, onValueChange = { onAddressChange(it); task = null; error = null }, label = { Text("Адрес сервера TalkLoop") },
            enabled = !busy && (task?.isRunning != true || error != null), singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && task?.isRunning != true, onClick = { goal = USAGE_GOAL }) { Text("Пример: найти использования компонента") }
        TextButton(enabled = !busy && task?.isRunning != true, onClick = { goal = API_GOAL }) { Text("Пример: обновить справку API по коду") }
        OutlinedTextField(value = goal, onValueChange = { goal = it }, label = { Text("Цель работы с проектом") }, minLines = 3, maxLines = 6,
            enabled = !busy && task?.isRunning != true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && task?.isRunning != true && goal.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                busy = true; error = null
                try { task = client.start(address, goal.trim()); refreshKey++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message }
                finally { busy = false }
            }
        }) { Text(if (task?.isRunning == true) "Ассистент работает…" else "Выполнить задачу") }
        TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Обновить / открыть последнюю задачу") }
        if (busy || task?.isRunning == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        task?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.message, color = if (result.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium)
                    Text("Цель: ${result.goal}", style = MaterialTheme.typography.bodySmall)
                    Text("Прочитано файлов: ${result.readFiles.size} · действий: ${result.events.size}")
                    if (result.summary.isNotBlank()) SelectionContainer { Text(result.summary) }
                    result.readFiles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    result.changes.forEach { Text("Документ: ${it.path}", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { showEvents = !showEvents }) { Text(if (showEvents) "Скрыть действия" else "Показать действия ассистента") }
                    if (showEvents) result.events.forEachIndexed { index, event ->
                        Text("${index + 1}. ${event.action} ${event.path.ifBlank { event.query }}\n${event.message}",
                            color = if (event.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (result.state == "prepared" || result.state == "no_changes") {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = true; error = null
                        try { task = client.apply(address, result.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                        finally { busy = false }
                    }
                }) { Text("Сохранить файлы") }
                Text("Перед сохранением проверяется, что исходники и документ не изменились после анализа.", style = MaterialTheme.typography.bodySmall)
            }
            if (result.state == "applied") Text("Изменения сохранены на Mac в проекте TalkLoop.")
            if (result.diff.isNotBlank()) {
                TextButton(onClick = { showDiff = !showDiff }) { Text(if (showDiff) "Скрыть diff" else "Показать diff") }
                if (showDiff) SelectionContainer { Text(result.diff, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
