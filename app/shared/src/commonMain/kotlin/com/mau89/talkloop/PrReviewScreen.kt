package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.PrReviewFinding
import com.mau89.talkloop.llm.PrReviewJob
import com.mau89.talkloop.llm.parseReviewPr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PrReviewScreen(address: String, onAddressChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val client = remember { PrReviewClient() }
    val scope = rememberCoroutineScope()
    var pr by remember { mutableStateOf("7") }
    var job by remember { mutableStateOf<PrReviewJob?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pollVersion by remember { mutableStateOf(0) }
    DisposableEffect(client) { onDispose { client.close() } }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            job = client.latest(address)
            pollVersion++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(address, job?.id, job?.isRunning, pollVersion) {
        val current = job ?: return@LaunchedEffect
        if (!current.isRunning) return@LaunchedEffect
        while (true) {
            delay(3_000)
            try {
                val updated = client.get(address, current.id)
                job = updated
                error = null
                if (!updated.isRunning) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message
                break
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ревью PR", style = MaterialTheme.typography.headlineSmall)
        Text("Проверка изменений TalkLoop по документации и коду. Анализ выполняет Ollama на вашем Mac; обычно это занимает несколько минут.")
        OutlinedTextField(value = address, onValueChange = { onAddressChange(it); job = null; error = null },
            label = { Text("Адрес локального сервера") }, singleLine = true,
            enabled = !loading && (job?.isRunning != true || error != null), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pr, onValueChange = { pr = it; error = null },
            label = { Text("Номер или ссылка PR") }, supportingText = { Text("Репозиторий mau89/TalkLoop") },
            singleLine = true, enabled = !loading && job?.isRunning != true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !loading && job?.isRunning != true && pr.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        job = client.start(address, parseReviewPr(pr))
                        pollVersion++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message
                    } finally {
                        loading = false
                    }
                }
            }) { Text(if (job?.isRunning == true) "Проверка выполняется…" else "Проверить PR") }
        TextButton(enabled = !loading, onClick = { scope.launch { refresh() } }) {
            Text("Обновить / открыть последнюю проверку")
        }
        if (loading || job?.isRunning == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (job == null && !loading && error == null) Text("Проверок пока нет. Укажите PR и нажмите «Проверить PR».")
        job?.let { ReviewResult(it) }
    }
}

@Composable
private fun ReviewResult(job: PrReviewJob) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(job.id) { mutableStateOf(false) }
    var details by remember(job.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PR #${job.prNumber}" + if (job.title.isBlank()) "" else " · ${job.title}", style = MaterialTheme.typography.titleMedium)
            Text(job.message, color = if (job.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (job.headSha.isNotBlank()) Text("Проверяемая версия: ${job.headSha.take(12)}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { linkError = runCatching { uriHandler.openUri(job.url) }.isFailure }) { Text("Открыть PR в GitHub") }
            if (linkError) Text(job.url, color = MaterialTheme.colorScheme.error)
        }
    }
    if (job.state != "completed") return
    job.coverage?.let { coverage ->
        Text("Файлов проверено: ${coverage.reviewedFiles} из ${coverage.totalFiles} · ${job.model}")
        if (coverage.partial) Text("Частичная проверка: исключённые файлы перечислены в подробностях.", color = MaterialTheme.colorScheme.error)
    }
    if (job.resultStatus == "reviewed") {
        SelectionContainer { Text(job.summary) }
        listOf("bugs" to "Потенциальные баги", "architecture" to "Архитектурные проблемы",
            "recommendations" to "Рекомендации").forEach { (category, title) ->
            val findings = job.findings.filter { it.category == category }
            Text("$title · ${findings.size}", style = MaterialTheme.typography.titleLarge)
            if (findings.isEmpty()) Text("В предоставленном контексте не выявлены.")
            findings.forEach { ReviewFindingCard(it) }
        }
        Text("Замечания модели требуют проверки человеком. Ревью не заменяет тесты.", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть подробности" else "Источники RAG и полный отчёт") }
    if (details) {
        Text("Источники RAG · ${job.sources.size}", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                job.sources.forEach { Text("${it.id} · ${if (it.kind == "docs") "документация" else "код"}\n${it.file}:${it.startLine}–${it.endLine}") }
                if (job.excluded.isNotEmpty()) Text("Исключённые файлы", style = MaterialTheme.typography.titleMedium)
                job.excluded.forEach { Text("${it.path}: ${it.reason}") }
                Text("Полный отчёт", style = MaterialTheme.typography.titleMedium)
                Text(job.markdown)
            }
        }
    }
}

@Composable
private fun ReviewFindingCard(finding: PrReviewFinding) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("[${finding.severity}] ${finding.title}", style = MaterialTheme.typography.titleMedium)
                Text("${finding.displayFile}:${finding.line} · ${if (finding.side == "old") "до изменения" else "после изменения"}",
                    style = MaterialTheme.typography.bodySmall)
                Text(finding.description)
                Text("Рекомендация: ${finding.suggestion}")
                if (finding.sourceIds.isNotEmpty()) Text("Контекст RAG: ${finding.sourceIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
