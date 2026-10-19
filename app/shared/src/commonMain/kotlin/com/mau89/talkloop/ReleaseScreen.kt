package com.mau89.talkloop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.ReleaseJob
import com.mau89.talkloop.llm.ReleaseRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReleaseScreen(address: String, onAddressChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val client = remember { ReleaseClient() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var base by remember { mutableStateOf("8d64764") }
    var version by remember { mutableStateOf("1.1.0") }
    var versionCode by remember { mutableStateOf("2") }
    var job by remember { mutableStateOf<ReleaseJob?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var showReport by remember(job?.id) { mutableStateOf(false) }
    var showLog by remember(job?.id) { mutableStateOf(false) }
    var pollKey by remember { mutableStateOf(0) }
    DisposableEffect(client) { onDispose { client.close() } }

    suspend fun refresh(restoreForm: Boolean = false) {
        busy = true; error = null
        try {
            job = client.latest(address); pollKey++
            if (restoreForm) job?.let { base = it.base; version = it.version; versionCode = it.versionCode.toString() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh(restoreForm = true) }
    LaunchedEffect(address, job?.id, job?.isRunning, pollKey) {
        val id = job?.takeIf { it.isRunning }?.id ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            try {
                val updated = client.get(address, id); job = updated; error = null
                if (!updated.isRunning) break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message; break }
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Подготовка релиза", style = MaterialTheme.typography.headlineSmall)
        Text("Mac проверит проект, соберёт APK и подготовит описание изменений с помощью локальной AI. Собирается последний коммит Git; незакоммиченные изменения не включаются.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Версия") },
                singleLine = true, enabled = !busy && job?.isRunning != true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = versionCode, onValueChange = { versionCode = it }, label = { Text("Код версии") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                enabled = !busy && job?.isRunning != true, modifier = Modifier.weight(1f))
        }
        Button(enabled = !busy && job?.isRunning != true, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                busy = true; error = null
                try {
                    val code = versionCode.toIntOrNull() ?: throw IllegalArgumentException("Введите целый код версии, например 2.")
                    job = client.start(address, ReleaseRequest(base.trim(), version.trim(), code)); pollKey++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message }
                finally { busy = false }
            }
        }) { Text(if (job?.isRunning == true) "Готовим релиз…" else "Подготовить релиз") }
        TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Обновить результат") }
        TextButton(onClick = { settings = !settings }) { Text(if (settings) "Скрыть параметры" else "Адрес Mac и предыдущий релиз") }
        if (settings) {
            OutlinedTextField(value = address, onValueChange = { onAddressChange(it); job = null; error = null }, label = { Text("Адрес сервера TalkLoop") },
                singleLine = true, enabled = !busy && (job?.isRunning != true || error != null), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Тег или коммит предыдущего релиза") },
                supportingText = { Text("От него считаются изменения для release notes.") }, singleLine = true,
                enabled = !busy && job?.isRunning != true, modifier = Modifier.fillMaxWidth())
        }
        if (busy || job?.isRunning == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (job == null && !busy && error == null) Text("Результатов пока нет. Укажите версию и нажмите «Подготовить релиз».")
        job?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TalkLoop ${result.version} · код ${result.versionCode}", style = MaterialTheme.typography.titleMedium)
                    Text(result.message, color = if (result.state == "blocked") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (result.head.isNotBlank()) Text("Коммит: ${result.head.take(12)}", style = MaterialTheme.typography.bodySmall)
                    result.gates.forEach { gate ->
                        val name = when (gate.name) { "gradle" -> "Kotlin-тесты и сборка APK"; "python-day32" -> "Проверки ревью"; "python-day34" -> "Проверки работы с файлами"; "python-day35" -> "Проверки релизного процесса"; else -> gate.name }
                        val state = when (gate.state) { "passed" -> "готово"; "failed" -> "ошибка"; else -> "выполняется…" }
                        Text("$name: $state", style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.kotlinTests + result.pythonTests > 0) Text("Тестов: ${result.kotlinTests} Kotlin · ${result.pythonTests} Python")
                }
            }
            result.artifacts.filter { it.name.endsWith(".zip") || it.name.endsWith(".apk") }.sortedBy { !it.name.endsWith(".zip") }.forEach { artifact ->
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    error = runCatching { uriHandler.openUri(client.downloadUrl(address, result.id, artifact.name)) }.exceptionOrNull()?.let { "Не удалось открыть скачивание: ${it.message}" }
                }) { Text("${artifact.label} · ${artifact.bytes / 1_000_000} МБ") }
                if (artifact.sha256.isNotBlank()) SelectionContainer { Text("SHA-256: ${artifact.sha256}", style = MaterialTheme.typography.bodySmall) }
            }
            if (result.artifacts.any { it.name.endsWith(".apk") }) Text("APK без подписи: перед установкой его нужно подписать своим ключом. Публикация не выполнялась.", style = MaterialTheme.typography.bodySmall)
            if (result.releaseNotes.isNotBlank()) {
                Text("Описание релиза от AI", style = MaterialTheme.typography.titleMedium)
                Text("Это черновик: проверьте утверждения и ссылки на коммиты перед публикацией.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(result.releaseNotes) }
            }
            if (result.report.isNotBlank()) {
                TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Скрыть отчёт" else "Показать полный отчёт") }
                if (showReport) SelectionContainer { Text(result.report, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.logTail.isNotBlank()) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Скрыть журнал" else "Показать журнал") }
                if (showLog) SelectionContainer { Text(result.logTail, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
