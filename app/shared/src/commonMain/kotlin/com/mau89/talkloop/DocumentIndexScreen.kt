package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.DocumentChunkHit
import com.mau89.talkloop.llm.DocumentIndexCatalog
import com.mau89.talkloop.llm.DocumentSearchRequest
import com.mau89.talkloop.llm.DocumentSearchResponse
import com.mau89.talkloop.llm.DocumentStrategyResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DocumentIndexScreen(modifier: Modifier = Modifier) {
    val client = remember { DocumentIndexClient() }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(defaultDocumentServerAddress()) }
    var catalog by remember { mutableStateOf<DocumentIndexCatalog?>(null) }
    var printer by remember { mutableStateOf<String?>(null) }
    var strategy by remember { mutableStateOf("both") }
    var query by remember { mutableStateOf("") }
    var response by remember { mutableStateOf<DocumentSearchResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    suspend fun connect() {
        busy = true
        error = null
        response = null
        catalog = null
        try {
            val loaded = client.catalog(address)
            catalog = loaded
            if (printer != null && printer !in loaded.models) printer = null
            if (query.isBlank()) query = loaded.exampleQueries.firstOrNull().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(client) { connect() }
    DisposableEffect(client) { onDispose { client.close() } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("День 21 · ${catalog?.title ?: "Кулинарная книга"}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "В режиме «Оба» один вопрос ищется в двух индексах. " +
                "Результат — найденные фрагменты, без генерации ответа.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; catalog = null; response = null; error = null },
            label = { Text("Адрес локального сервера") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { scope.launch { connect() } }, enabled = !busy) {
            Text(if (catalog == null) "Подключиться" else "Обновить состояние")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        catalog?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Индекс готов · ${info.documents} документов", style = MaterialTheme.typography.titleMedium)
                    Text("${info.words} слов · ≈ ${info.textPages.roundToInt()} страниц" +
                        if (info.language == "ru") " · русский язык" else "")
                    Text("Локальная E5 · ${info.dimension} координаты", style = MaterialTheme.typography.bodySmall)
                    info.strategies.forEach { item ->
                        Text(
                            "${strategyLabel(item.strategy)}: ${item.chunks} чанков, " +
                                "в среднем ${item.meanTokens} токена; ${item.crossSectionChunks} пересекают разделы.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        item.hitAt5?.let { rate ->
                            Text("На контрольных вопросах: ${(rate * 10000).roundToInt() / 100.0}% в топ-5.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (info.attribution.isNotBlank()) Text(info.attribution, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (info.models.isNotEmpty()) Box {
                TextButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Text("Принтер: ${printer ?: "Все модели"}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    (listOf<String?>(null) + info.models).forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model ?: "Все модели") },
                            onClick = { printer = model; menuOpen = false; response = null },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fixed", "structural", "both").forEach { value ->
                    FilterChip(
                        selected = strategy == value,
                        onClick = { strategy = value; response = null }, enabled = !busy,
                        label = { Text(strategyLabel(value)) },
                    )
                }
            }
            Text(
                when (strategy) {
                    "fixed" -> "Фиксированный: окна до 320 токенов, которые могут захватить ингредиенты и приготовление вместе."
                    "structural" -> "По разделам: ингредиенты и приготовление разделяются по заголовкам; длинные разделы делятся дальше."
                    else -> "Фиксированный — окна до 320 токенов. По разделам — отдельные фрагменты ингредиентов и приготовления. Ниже сравните тексты одного вопроса."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it; response = null; error = null },
                label = { Text("Вопрос по документам") },
                minLines = 2, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            info.exampleQueries.take(3).forEach { example ->
                TextButton(onClick = { query = example; response = null }, enabled = !busy) {
                    Text("Пример: $example")
                }
            }
            Button(
                enabled = !busy && query.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        response = null
                        try {
                            response = client.search(address, DocumentSearchRequest(query.trim(), printer, strategy))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Поиск…" else if (strategy == "both") "Сравнить результаты" else "Найти") }
        }
        response?.let { found ->
            Text("Результаты · ${found.searchSeconds} с", style = MaterialTheme.typography.titleMedium)
            Text(found.query + (found.model?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Text("Оценка сходства помогает упорядочить фрагменты и не означает вероятность правильного ответа.",
                style = MaterialTheme.typography.bodySmall)
            found.results.forEach { result ->
                DocumentStrategyGroup(result)
            }
        }
    }
}

@Composable
private fun DocumentStrategyGroup(result: DocumentStrategyResults) {
    var showAll by remember(result) { mutableStateOf(false) }
    Text(strategyLabel(result.strategy), style = MaterialTheme.typography.titleLarge)
    if (result.hits.isEmpty()) Text("Фрагменты не найдены.")
    val visible = if (showAll) result.hits else result.hits.take(1)
    visible.forEachIndexed { index, hit -> DocumentChunkCard(index + 1, hit) }
    if (result.hits.size > 1) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Только первый результат" else "Показать ещё ${result.hits.size - 1} результата")
        }
    }
}

internal fun strategyLabel(value: String): String = when (value) {
    "fixed" -> "Фиксированный"
    "structural" -> "По разделам"
    else -> "Оба"
}

@Composable
private fun DocumentChunkCard(rank: Int, hit: DocumentChunkHit) {
    var expanded by remember(hit.chunkId) { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$rank. ${hit.title}", style = MaterialTheme.typography.titleMedium)
            Text("Сходство: ${(hit.score * 1000).roundToInt() / 1000.0} · ${hit.tokenCount} токенов",
                style = MaterialTheme.typography.labelMedium)
            Text(hit.section, style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            SelectionContainer {
                Text(hit.text, maxLines = if (expanded) Int.MAX_VALUE else 6)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть" else "Весь фрагмент и метаданные")
            }
            if (expanded) {
                SelectionContainer {
                    Text("ID: ${hit.chunkId}\nФайл: ${hit.file}" +
                        (if (hit.models.isNotEmpty()) "\nМодели: ${hit.models.joinToString()}" else "") +
                        "\nИсточник: ${hit.source}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = {
                val anchor = hit.anchors.firstOrNull { it.isNotBlank() }
                linkError = runCatching {
                    uriHandler.openUri(hit.source + (anchor?.let { "#$it" } ?: ""))
                }.isFailure
            }) { Text("Открыть источник") }
            if (linkError) Text("Не удалось открыть ссылку. Адрес доступен в метаданных.", color = MaterialTheme.colorScheme.error)
        }
    }
}
