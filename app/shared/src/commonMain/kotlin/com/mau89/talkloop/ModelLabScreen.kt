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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.LlmAnswer
import com.mau89.talkloop.llm.MODEL_LAB_EXPECTED_HINT
import com.mau89.talkloop.llm.MODEL_LAB_PROMPT
import com.mau89.talkloop.llm.MODEL_PRESETS
import com.mau89.talkloop.llm.ModelPreset
import com.mau89.talkloop.llm.estimateCostUsd
import com.mau89.talkloop.llm.formatDuration
import com.mau89.talkloop.llm.formatThroughput
import com.mau89.talkloop.llm.formatUsd
import com.mau89.talkloop.llm.modelLabRequest
import com.mau89.talkloop.llm.modelLabSpec
import com.mau89.talkloop.llm.scoreModelAnswer
import com.mau89.talkloop.llm.tokensPerSecond
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Лаборатория версий моделей.
 *
 * Один запрос уходит в слабую, среднюю и сильную модель. Сравниваются скорость
 * и стоимость. Качество — на глаз; эталон можно вписать, тогда появится сверка.
 */

private val REPEAT_OPTIONS = listOf(1, 3, 5)

private data class ModelRun(
    val preset: ModelPreset,
    val index: Int,
    val answer: LlmAnswer,
    val correct: Boolean?,
    val durationMs: Long,
    val costUsd: Double,
)

@Composable
fun ModelLabScreen(apiKey: String, modifier: Modifier = Modifier) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val clients = remember(apiKey) {
        MODEL_PRESETS.associate { preset ->
            preset.id to AnthropicLlmClient(apiKey, model = preset.id)
        }
    }
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<ModelRun>() }

    var prompt by remember { mutableStateOf(MODEL_LAB_PROMPT) }
    var expected by remember { mutableStateOf("") }
    var repeats by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(0) }
    var checkedAgainst by remember { mutableStateOf(expected) }
    var stoppedManually by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (busy) return
        busy = true
        error = null
        stoppedManually = false
        runs.clear()
        requested = repeats * MODEL_PRESETS.size
        checkedAgainst = expected.trim()

        val history = modelLabRequest(prompt)
        val spec = modelLabSpec()
        val target = expected.trim()
        job = scope.launch {
            try {
                MODEL_PRESETS.forEach { preset ->
                    val client = clients.getValue(preset.id)
                    repeat(repeats) { attempt ->
                        val started = TimeSource.Monotonic.markNow()
                        val answer = client.answer(history, spec)
                        val durationMs = started.elapsedNow().inWholeMilliseconds
                        runs += ModelRun(
                            preset = preset,
                            index = attempt + 1,
                            answer = answer,
                            correct = scoreModelAnswer(target, answer.text),
                            durationMs = durationMs,
                            costUsd = estimateCostUsd(
                                preset,
                                answer.inputTokens,
                                answer.outputTokens,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                stoppedManually = true
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
        Text("Модели", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Один запрос уходит в слабую, среднюю и сильную модель. " +
                "Сравниваются скорость и стоимость. Качество — на глаз; " +
                "эталон можно вписать, если нужна сверка.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Запрос") },
            minLines = 4,
            trailingIcon = {
                if (prompt != MODEL_LAB_PROMPT) {
                    TextButton(onClick = { prompt = MODEL_LAB_PROMPT }) {
                        Text("↺")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = expected,
            onValueChange = { expected = it },
            label = { Text("Верный ответ") },
            placeholder = { Text(MODEL_LAB_EXPECTED_HINT) },
            supportingText = {
                Text("Необязательно. Пусто — качество на глаз, без сверки.")
            },
            trailingIcon = {
                if (expected.isNotEmpty()) {
                    TextButton(onClick = { expected = "" }) {
                        Text("↺")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Линейка", style = MaterialTheme.typography.titleSmall)
        MODEL_PRESETS.forEach { preset ->
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = "${preset.title}: ${preset.id}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${preset.hint}  ${formatUsd(preset.inputUsdPerMTok)} / " +
                        "${formatUsd(preset.outputUsdPerMTok)} за 1M ток.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прогонов на модель:", style = MaterialTheme.typography.bodyMedium)
            REPEAT_OPTIONS.forEach { count ->
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { repeats = count }, enabled = repeats != count) {
                    Text("$count")
                }
            }
        }
        Text(
            text = "запросов к API: ${repeats * MODEL_PRESETS.size}",
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
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Прогнать три модели")
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
            Verdict(requested, runs, checkedAgainst, stoppedManually = stoppedManually)
            runs.forEach { RunCard(it) }
        }
    }
}

@Composable
private fun Verdict(
    requested: Int,
    runs: List<ModelRun>,
    expected: String,
    stoppedManually: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Сводка", style = MaterialTheme.typography.titleSmall)
            MODEL_PRESETS.forEach { preset ->
                val batch = runs.filter { it.preset.id == preset.id }
                if (batch.isEmpty()) return@forEach
                val correct = batch.count { it.correct == true }
                val scored = expected.isNotBlank()
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${preset.title} · ${preset.id}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "время: ${formatDuration(batch.sumOf { it.durationMs })}  ·  " +
                                "токены: ${batch.sumOf { it.answer.inputTokens }}/" +
                                "${batch.sumOf { it.answer.outputTokens }}  ·  " +
                                formatUsd(batch.sumOf { it.costUsd }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (scored) "$correct/${batch.size} верно" else "на глаз",
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            !scored -> MaterialTheme.colorScheme.onSurfaceVariant
                            correct == batch.size -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            if (runs.size < requested) {
                Text(
                    text = if (stoppedManually) {
                        "остановлено вручную: ${runs.size} из $requested"
                    } else {
                        "не досчитано: ${runs.size} из $requested"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RunCard(run: ModelRun) {
    var expanded by remember { mutableStateOf(false) }
    val text = run.answer.text.ifEmpty { "(пусто)" }
    val preview = text.lineSequence().take(3).joinToString("\n")
    val quality = when (run.correct) {
        true -> "верно"
        false -> "неверно"
        null -> "на глаз"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "${run.preset.title} · ${run.preset.id} · прогон ${run.index}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "$quality  ·  ${formatDuration(run.durationMs)}  ·  " +
                    "${run.answer.inputTokens}/${run.answer.outputTokens} ток.  ·  " +
                    formatThroughput(tokensPerSecond(run.answer.outputTokens, run.durationMs)) +
                    "  ·  ${formatUsd(run.costUsd)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (expanded) text else preview,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (text.lines().size > 3 || text.length > preview.length) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Развернуть")
                }
            }
        }
    }
}
