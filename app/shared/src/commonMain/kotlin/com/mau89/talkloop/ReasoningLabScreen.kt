package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.DEFAULT_EXPERTS
import com.mau89.talkloop.llm.Expert
import com.mau89.talkloop.llm.ExpertOpinion
import com.mau89.talkloop.llm.LlmAnswer
import com.mau89.talkloop.llm.PROMPT_WRITER_SPEC
import com.mau89.talkloop.llm.REASONING_TASKS
import com.mau89.talkloop.llm.ReasoningMode
import com.mau89.talkloop.llm.expertsAgree
import com.mau89.talkloop.llm.extractAnswer
import com.mau89.talkloop.llm.isRefusal
import com.mau89.talkloop.llm.normalizeAnswer
import com.mau89.talkloop.llm.matchesExpected
import com.mau89.talkloop.llm.parseExperts
import com.mau89.talkloop.llm.promptWriterRequest
import com.mau89.talkloop.llm.reasoningRequest
import com.mau89.talkloop.llm.reasoningSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Лаборатория рассуждения.
 *
 * Одна задача уходит в модель четырьмя способами подряд, каждый — по нескольку раз.
 * Повторы здесь не для красоты: модель недетерминирована, и по одному прогону
 * «этот способ точнее» сказать нельзя — можно только «в этот раз повезло».
 * Сравнивается доля верных ответов, поэтому у задачи должен быть верный ответ.
 */

private val REPEAT_OPTIONS = listOf(1, 3, 5)

private data class ModeRun(
    val mode: ReasoningMode,
    val index: Int,
    val answer: LlmAnswer,
    val extracted: String?,
    val correct: Boolean?,
    val experts: List<ExpertOpinion>,
    val generatedPrompt: String?,
    val outputTokens: Int,
)

@Composable
fun ReasoningLabScreen(apiKey: String, modifier: Modifier = Modifier) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val client = remember(apiKey) { AnthropicLlmClient(apiKey) }
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<ModeRun>() }
    // Состав совета — часть способа, а не константа: его хочется менять под задачу.
    val experts = remember { DEFAULT_EXPERTS.toMutableStateList() }

    var task by remember { mutableStateOf(REASONING_TASKS.first().text) }
    var expected by remember { mutableStateOf(REASONING_TASKS.first().expected) }
    var repeats by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkedAgainst by remember { mutableStateOf(expected) }
    var requested by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (busy) return
        busy = true
        error = null
        runs.clear()
        requested = repeats * ReasoningMode.entries.size
        checkedAgainst = expected.trim()

        val text = task.trim()
        val target = expected.trim()
        val panel = experts.map { Expert(it.title.trim(), it.duty.trim()) }
            .filter { it.title.isNotBlank() }
        job = scope.launch {
            try {
                ReasoningMode.entries.forEach { mode ->
                    repeat(repeats) { attempt ->
                        // Единственный способ с двумя запросами: промпт сначала надо получить.
                        val written = if (mode == ReasoningMode.SELF_PROMPT) {
                            client.answer(promptWriterRequest(text), PROMPT_WRITER_SPEC)
                        } else {
                            null
                        }
                        val answer = client.answer(
                            reasoningRequest(text),
                            reasoningSpec(mode, written?.text, panel),
                        )
                        val extracted = extractAnswer(answer.text)
                        runs += ModeRun(
                            mode = mode,
                            index = attempt + 1,
                            answer = answer,
                            extracted = extracted,
                            correct = matchesExpected(target, extracted),
                            experts = if (mode == ReasoningMode.EXPERTS) {
                                parseExperts(answer.text, panel)
                            } else {
                                emptyList()
                            },
                            generatedPrompt = written?.text,
                            outputTokens = (written?.outputTokens ?: 0) + answer.outputTokens,
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Остановка руками — не ошибка, собранные прогоны оставляем.
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не дошло до модели"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Способы рассуждения", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Одна задача уходит в модель четырьмя способами: прямым вопросом, " +
                "пошагово, по промпту от самой модели и советом экспертов. Задача — " +
                "с одним верным ответом: иначе «какой способ точнее» решалось бы на глаз.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            label = { Text("Задача") },
            trailingIcon = {
                if (task.isNotEmpty()) {
                    TextButton(onClick = { task = "" }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = expected,
            onValueChange = { expected = it },
            label = { Text("Верный ответ") },
            // Ответ знать необязательно, поэтому подставленный из готовой задачи
            // должно быть чем стереть — иначе «не знаю ответа» недоступно с телефона.
            trailingIcon = {
                if (expected.isNotEmpty()) {
                    TextButton(onClick = { expected = "" }) { Text("✕") }
                }
            },
            supportingText = { Text("пусто — вердикта не будет, ответы сравним между собой") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Готовые задачи",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        REASONING_TASKS.forEach { example ->
            OutlinedButton(
                onClick = {
                    task = example.text
                    expected = example.expected
                },
                enabled = task != example.text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(example.title, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Способы", style = MaterialTheme.typography.titleSmall)
        ReasoningMode.entries.forEach { mode ->
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = mode.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Состав совета", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Имена и роли переписываются: совет из трёх — только заготовка. " +
                "Порядок важен, каждый следующий видит сказанное до него.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        experts.forEachIndexed { index, expert ->
            ExpertRow(
                expert = expert,
                // Пустой совет решать некому, поэтому последнего не удаляем.
                canRemove = experts.size > 1,
                onRemove = { experts.removeAt(index) },
                onChange = { experts[index] = it },
            )
        }
        OutlinedButton(
            onClick = { experts += Expert("", "") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Добавить эксперта", style = MaterialTheme.typography.bodySmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прогонов на способ:", style = MaterialTheme.typography.bodyMedium)
            REPEAT_OPTIONS.forEach { count ->
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { repeats = count }, enabled = repeats != count) {
                    Text("$count")
                }
            }
        }
        // Считаем запросы заранее: у «промпта от модели» их два на прогон,
        // и на счёт это влияет заметнее, чем кажется.
        Text(
            text = "запросов к API: ${repeats * ReasoningMode.entries.sumOf { it.calls }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busy) {
            Button(
                onClick = { job?.cancel() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Стоп")
            }
            Text(
                text = "прогон ${runs.size + 1} из $requested",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Button(
                onClick = ::start,
                enabled = task.isNotBlank() && experts.any { it.title.isNotBlank() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Прогнать четыре способа")
            }
        }

        error?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (runs.isNotEmpty()) {
            Verdict(checkedAgainst, requested, runs)
            runs.forEach { RunCard(it) }
        }
    }
}

/** Строка редактора совета: имя эксперта и чем он занят. */
@Composable
private fun ExpertRow(
    expert: Expert,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onChange: (Expert) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = expert.title,
                onValueChange = { onChange(expert.copy(title = it)) },
                label = { Text("Имя") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = expert.duty,
                onValueChange = { onChange(expert.copy(duty = it)) },
                label = { Text("Чем занят") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        TextButton(onClick = onRemove, enabled = canRemove) { Text("✕") }
    }
}

/**
 * Главный ответ экрана: сколько прогонов каждого способа дали верный ответ.
 *
 * Без эталона вердикта не будет — вместо него способы сравниваются между собой:
 * сошлись ли они на одном ответе. Это слабее проверки, но именно это и видно,
 * когда верного ответа не знаешь.
 */
@Composable
private fun Verdict(expected: String, requested: Int, runs: List<ModeRun>) {
    val byMode = ReasoningMode.entries
        .map { mode -> mode to runs.filter { it.mode == mode } }
        .filter { (_, modeRuns) -> modeRuns.isNotEmpty() }
    val refusals = runs.count { isRefusal(it.extracted) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (expected.isBlank()) {
                    "Верный ответ не задан — сравниваем ответы между собой"
                } else {
                    "Верный ответ: $expected"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (expected.isBlank()) {
                val counts = runs.mapNotNull { it.extracted }
                    .map(::normalizeAnswer)
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                Text(
                    text = counts.take(4).joinToString(" · ") { "${it.key} ×${it.value}" }
                        .ifEmpty { "ответов не нашлось" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (counts.size > 1) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            byMode.forEach { (mode, modeRuns) ->
                val checked = modeRuns.mapNotNull { it.correct }
                val correct = checked.count { it }
                val distinct = modeRuns.mapNotNull { it.extracted }.map(::normalizeAnswer).distinct()
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "токенов: ${modeRuns.sumOf { it.outputTokens }} · " +
                                "запросов: ${modeRuns.size * mode.calls}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = when {
                            checked.isNotEmpty() -> "верно $correct из ${checked.size}"
                            distinct.isEmpty() -> "ответа нет"
                            distinct.size == 1 -> distinct.single()
                            else -> "ответы разошлись"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            checked.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            correct == checked.size -> MaterialTheme.colorScheme.primary
                            correct == 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
            }
            if (refusals > 0) {
                Text(
                    text = "отказов: $refusals из ${runs.size} — задача не того типа",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (runs.size < requested) {
                Text(
                    text = "остановлено вручную: ${runs.size} из $requested",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RunCard(run: ModeRun) {
    // Рассуждение длинное, а сравниваются ответы: по умолчанию показываем итог,
    // текст целиком — по требованию, иначе на телефоне до вердикта не долистать.
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${run.mode.title} · прогон ${run.index}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        run.correct == true -> "верно"
                        isRefusal(run.extracted) -> "отказ"
                        run.correct == false -> "неверно"
                        else -> "без проверки"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        run.correct == true -> MaterialTheme.colorScheme.primary
                        isRefusal(run.extracted) -> MaterialTheme.colorScheme.onSurfaceVariant
                        run.correct == false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = "ответ: ${run.extracted ?: "не найден"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "токенов: ${run.outputTokens} · stop_reason: ${run.answer.stopReason ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (run.experts.isNotEmpty()) ExpertsView(run.experts)

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Свернуть" else "Показать рассуждение")
            }
            if (expanded) {
                run.generatedPrompt?.let { prompt ->
                    Text(
                        text = "Промпт, который модель написала себе сама:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = run.answer.text.ifBlank { "(пусто)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Ради этого совет и собирали: видно не только итог, но и разошлись ли эксперты. */
@Composable
private fun ExpertsView(opinions: List<ExpertOpinion>) {
    Column(Modifier.padding(top = 4.dp)) {
        opinions.forEach { opinion ->
            Text(
                text = "${opinion.expert.title}: ${opinion.answer ?: "своего вывода не назвал"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = when (expertsAgree(opinions)) {
                true -> "эксперты сошлись"
                false -> "эксперты разошлись"
                null -> "вывод назвал только один — сравнивать нечего"
            },
            style = MaterialTheme.typography.labelMedium,
            // Расхождение — не ошибка, а как раз то, ради чего совет и собран:
            // видно, что одному из экспертов верить нельзя. Красным не кричим.
            color = if (expertsAgree(opinions) == false) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
