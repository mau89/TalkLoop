package com.mau89.talkloop

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.DEFAULT_WORD_LIMIT
import com.mau89.talkloop.llm.FormatMode
import com.mau89.talkloop.llm.CarAnswer
import com.mau89.talkloop.llm.LlmAnswer
import com.mau89.talkloop.llm.MAX_TOKENS_RANGE
import com.mau89.talkloop.llm.MAX_TOKENS_STEP
import com.mau89.talkloop.llm.DEFAULT_STOP_WORD
import com.mau89.talkloop.llm.ShapeCheck
import com.mau89.talkloop.llm.DEFAULT_CAR_PROMPT
import com.mau89.talkloop.llm.EXAMPLE_PROMPTS
import com.mau89.talkloop.llm.WORD_LIMIT_RANGE
import com.mau89.talkloop.llm.WORD_LIMIT_STEP
import com.mau89.talkloop.llm.buildSpec
import com.mau89.talkloop.llm.checkAnswer
import com.mau89.talkloop.llm.parseStopWords
import com.mau89.talkloop.llm.formatLabRequest
import com.mau89.talkloop.llm.parseCarAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Лаборатория формата ответа.
 *
 * Один и тот же запрос уходит в модель несколько раз подряд с одними и теми же
 * ограничениями — так видно не «красивый ли ответ», а стабилен ли формат.
 * Запрос обычный, любой: рецепт, инструкция, разбор ошибок. Ради этого экран
 * и существует — структурированный ответ можно показывать и складывать,
 * свободный текст только читать.
 */

private val REPEAT_OPTIONS = listOf(1, 3, 5)

private data class LabRun(
    val index: Int,
    val answer: LlmAnswer,
    val shape: ShapeCheck,
    val car: CarAnswer?,
)

@Composable
fun FormatLabScreen(apiKey: String, modifier: Modifier = Modifier) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val client = remember(apiKey) { AnthropicLlmClient(apiKey) }
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<LabRun>() }

    var prompt by remember { mutableStateOf(DEFAULT_CAR_PROMPT) }
    var mode by remember { mutableStateOf(FormatMode.FREE) }
    var maxTokens by remember { mutableStateOf(MAX_TOKENS_RANGE.last) }
    var limitWords by remember { mutableStateOf(false) }
    var wordLimit by remember { mutableStateOf(DEFAULT_WORD_LIMIT) }
    var stopWordsText by remember { mutableStateOf(DEFAULT_STOP_WORD) }
    var askForStopWord by remember { mutableStateOf(false) }
    var repeats by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shownMode by remember { mutableStateOf(mode) }
    var requested by remember { mutableStateOf(0) }
    // Ссылку на запущенную корутину держим, иначе прогон нечем оборвать.
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (busy) return
        busy = true
        error = null
        runs.clear()
        shownMode = mode
        requested = repeats

        val checkedMode = mode
        val spec = buildSpec(
            mode = mode,
            maxTokens = maxTokens,
            wordLimit = wordLimit.takeIf { limitWords },
            stopWords = parseStopWords(stopWordsText),
            askForStopWord = askForStopWord,
        )
        val history = formatLabRequest(prompt.trim())
        job = scope.launch {
            try {
                repeat(repeats) { attempt ->
                    val answer = client.answer(history, spec)
                    runs += LabRun(
                        index = attempt + 1,
                        answer = answer,
                        shape = checkAnswer(checkedMode, answer),
                        car = parseCarAnswer(answer.text),
                    )
                }
            } catch (e: CancellationException) {
                // Остановка руками — это не ошибка, уже собранные прогоны оставляем.
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
        Text("Автомобильный эксперт", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Модель отвечает только про автомобили, на остальное отказывает. " +
                "Меняется лишь то, чем задан формат ответа. Прогон повторяется несколько " +
                "раз — так видно, стабилен формат или совпал случайно.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Вопрос") },
            enabled = true,
            // Без этого длинный вопрос на телефоне стирать нечем: экран для того
            // и нужен, чтобы быстро пробовать разные вопросы.
            trailingIcon = {
                if (prompt.isNotEmpty()) {
                    TextButton(onClick = { prompt = "" }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Примеры",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EXAMPLE_PROMPTS.forEach { example ->
            OutlinedButton(
                onClick = { prompt = example },
                enabled = prompt != example,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(example, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Режим", style = MaterialTheme.typography.titleSmall)
        FormatMode.entries.forEach { option ->
            ModeRow(
                option = option,
                selected = option == mode,
                enabled = true,
                onSelect = { mode = option },
            )
        }

        SliderRow(
            title = "max_tokens",
            value = "$maxTokens",
            hint = "жёсткий потолок: на нём ответ обрывается на полуслове",
            current = maxTokens,
            range = MAX_TOKENS_RANGE,
            step = MAX_TOKENS_STEP,
            enabled = true,
            onChange = { maxTokens = it },
        )
        ToggleRow(
            title = "Лимит длины в промпте",
            hint = "просьба, а не гарантия: модель округляет её в свою пользу",
            checked = limitWords,
            enabled = true,
            onChange = { limitWords = it },
        )
        if (limitWords) {
            SliderRow(
                title = "Слов в ответе",
                value = "не длиннее $wordLimit",
                hint = "уходит в системный промпт отдельной строкой",
                current = wordLimit,
                range = WORD_LIMIT_RANGE,
                step = WORD_LIMIT_STEP,
                enabled = true,
                onChange = { wordLimit = it },
            )
        }
        OutlinedTextField(
            value = stopWordsText,
            onValueChange = { stopWordsText = it },
            label = { Text("Стоп-слова") },
            enabled = true,
            trailingIcon = {
                if (stopWordsText.isNotEmpty()) {
                    TextButton(onClick = { stopWordsText = "" }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "через запятую. Генерация обрывается, как только модель напишет любое " +
                "из них — страховка от лишних токенов, если ответ поехал не туда. " +
                "Само слово в текст не попадает, срабатывание видно в stop_reason.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            title = "Просить дописать стоп-слово",
            hint = "тогда это маркер конца, а не страховка. Под схемой не просим: " +
                "она запрещает текст вокруг JSON",
            checked = askForStopWord,
            enabled = stopWordsText.isNotBlank(),
            onChange = { askForStopWord = it },
        )

        // Кнопка отдельной строкой: в одном Row с выбором прогонов её на узком
        // экране сплющивало в столбик из букв.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прогонов:", style = MaterialTheme.typography.bodyMedium)
            REPEAT_OPTIONS.forEach { count ->
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { repeats = count },
                    enabled = repeats != count,
                ) {
                    Text("$count")
                }
            }
        }
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
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Прогнать")
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
            Verdict(shownMode, requested, runs)
            runs.forEach { RunCard(it) }
        }
    }
}

@Composable
private fun ModeRow(
    option: FormatMode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            Text(option.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = option.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Слайдер с подписанным текущим значением; шаг задаётся доменом, а не пикселями. */
@Composable
private fun SliderRow(
    title: String,
    value: String,
    hint: String,
    current: Int,
    range: IntRange,
    step: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = current.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // steps — это промежуточные засечки, поэтому на одну меньше числа отрезков.
            steps = (range.last - range.first) / step - 1,
            enabled = enabled,
        )
    }
}

/** Главный ответ экрана: сколько прогонов из N дали ровно тот формат, что просили. */
@Composable
private fun Verdict(mode: FormatMode, requested: Int, runs: List<LabRun>) {
    val checked = runs.mapNotNull { it.shape.ok }
    val matched = checked.count { it }
    val parsed = runs.count { it.car != null }
    val truncated = runs.count { it.shape.truncated }
    val tokens = runs.sumOf { it.answer.outputTokens }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (checked.isEmpty()) {
                    "${mode.title}: формат не задан, проверять нечего"
                } else {
                    "${mode.title}: формат совпал $matched из ${checked.size}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    checked.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                    matched == checked.size -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = "разобралось в структуру: $parsed из ${runs.size} · " +
                    "токенов на выходе всего: $tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runs.size < requested) {
                Text(
                    text = "остановлено вручную: ${runs.size} из $requested",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (truncated > 0) {
                // Обрезку показываем отдельно: в свободном режиме её нельзя записать
                // в «формат не совпал», там формат и не задавали.
                Text(
                    text = "обрезано потолком max_tokens: $truncated из ${runs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RunCard(run: LabRun) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Прогон ${run.index}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (run.shape.ok) {
                        true -> "формат совпал"
                        false -> "формат другой"
                        null -> "без проверки"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (run.shape.ok) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = "${run.shape.note} · stop_reason: ${run.answer.stopReason ?: "—"}" +
                    (run.answer.stopSequence?.let { " (\"$it\")" } ?: "") +
                    " · токенов: ${run.answer.outputTokens}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.car?.let { CarView(it) }
            Text(
                text = run.answer.text.ifBlank { "(пусто)" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Ради этого всё и затевалось: ответ уже структура, а не текст. */
@Composable
private fun CarView(car: CarAnswer) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            text = if (car.answerable) car.car.ifBlank { "Машина" } else "Вне темы",
            style = MaterialTheme.typography.titleSmall,
            color = if (car.answerable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(car.message, style = MaterialTheme.typography.bodySmall)
        car.components.forEach { part ->
            Text(
                text = part.component.title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text("• ${part.summary}", style = MaterialTheme.typography.bodySmall)
            Text(
                text = part.details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
