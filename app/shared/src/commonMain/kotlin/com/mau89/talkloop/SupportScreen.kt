package com.mau89.talkloop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class SupportTurn(val question: String, val reply: SupportReply)

@Composable
fun SupportScreen(address: String, onAddressChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val client = remember { SupportClient() }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<SupportCatalog?>(null) }
    var userId by remember { mutableStateOf("U1001") }
    var ticketId by remember { mutableStateOf("T1001") }
    var question by remember { mutableStateOf("Почему не работает авторизация?") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableStateListOf<SupportTurn>() }
    DisposableEffect(client) { onDispose { client.close() } }

    suspend fun connect() {
        busy = true; error = null
        try {
            val loaded = client.catalog(address)
            catalog = loaded
            if (loaded.users.none { it.id == userId }) userId = loaded.users.firstOrNull()?.id.orEmpty()
            if (loaded.tickets.none { it.id == ticketId && it.userId == userId }) ticketId = loaded.tickets.firstOrNull { it.userId == userId }?.id.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { connect() }

    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Поддержка", style = MaterialTheme.typography.headlineSmall)
        Text("FAQ и документация + контекст выбранного тикета через MCP. Отвечает локальная Ollama на Mac.")
        Text("Учебный режим: клиенты и обращения вымышлены; выбор клиента не является авторизацией.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = address, onValueChange = { onAddressChange(it); catalog = null; turns.clear(); error = null },
            label = { Text("Адрес сервера TalkLoop") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { scope.launch { connect() } }, enabled = !busy) { Text("Подключиться / обновить тикеты") }
        catalog?.let { data ->
            SupportChoice("Демо-клиент", data.users.map { it.id to "${it.name} · ${it.id} · ${it.plan}" }, userId, !busy) { id ->
                userId = id; ticketId = data.tickets.firstOrNull { it.userId == id }?.id.orEmpty(); turns.clear(); error = null
                question = "Почему не работает авторизация?"
            }
            SupportChoice("Тикет", data.tickets.filter { it.userId == userId }.map { it.id to "${it.id} · ${it.subject}" }, ticketId, !busy) { id ->
                ticketId = id; turns.clear(); error = null
                question = if (id == "T1003") "Почему приложение не видит сервер?" else "Почему не работает авторизация?"
            }
            OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text("Вопрос поддержки") },
                minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && question.isNotBlank() && ticketId.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = true; error = null
                    val asked = question.trim()
                    try {
                        val history = turns.takeLast(2).flatMap { listOf(ChatMessage(true, it.question.take(1000)), ChatMessage(false, it.reply.answer.take(1000))) }
                        val reply = client.ask(address, SupportRequest(userId, ticketId, asked, history))
                        check(reply.context.user.id == userId && reply.context.ticket.id == ticketId) { "Ответ относится к другому тикету." }
                        turns += SupportTurn(asked, reply)
                        question = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Готовим ответ…" else "Спросить поддержку") }
            if (turns.isNotEmpty()) TextButton(enabled = !busy, onClick = { turns.clear(); question = "Почему не работает авторизация?" }) { Text("Новый диалог по тикету") }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Получаем контекст MCP, ищем FAQ и готовим ответ. Это может занять до трёх минут.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        turns.forEach { turn ->
            SelectionContainer { Text("Вы: ${turn.question}", style = MaterialTheme.typography.titleMedium) }
            SupportAnswerCard(turn.reply)
        }
    }
}

@Composable
private fun SupportChoice(label: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled && options.isNotEmpty(), onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == selected }?.second ?: "Нет доступных"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun SupportAnswerCard(reply: SupportReply) {
    var details by remember(reply) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (reply.disposition) { "answered" -> "Ответ поддержки"; "needs_info" -> "Нужно уточнение"; else -> "Рекомендуется оператор" }, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(reply.answer) }
            Text("${reply.context.user.name} · ${reply.context.ticket.id} · ${reply.context.ticket.status}", style = MaterialTheme.typography.bodySmall)
            Text("MCP: ${reply.mcpTool} · ${reply.context.ticket.issueCode}", style = MaterialTheme.typography.bodySmall)
            reply.sources.filter { it.number in reply.usedSources }.forEach { Text("[${it.number}] ${it.file} — ${it.section}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть контекст" else "Контекст тикета и фрагменты FAQ") }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${reply.context.ticket.platform} · версия ${reply.context.ticket.appVersion}\n${reply.context.ticket.facts.entries.joinToString("\n") { "${it.key}: ${it.value}" }}\nЗаметки: ${reply.context.ticket.notes}")
                    reply.sources.forEach { Text("[${it.number}] ${it.file} / ${it.section}\n${it.text}") }
                }
            }
        }
    }
}
