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
import com.mau89.talkloop.llm.TEMPERATURE_LAB_PROMPT
import com.mau89.talkloop.llm.TEMPERATURE_LEVELS
import com.mau89.talkloop.llm.TEMPERATURE_PRESETS
import com.mau89.talkloop.llm.distinctCount
import com.mau89.talkloop.llm.diversityLabel
import com.mau89.talkloop.llm.effectiveTemperature
import com.mau89.talkloop.llm.temperatureLabRequest
import com.mau89.talkloop.llm.temperatureSpec
import com.mau89.talkloop.llm.temperatureWasClamped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Лаборатория температуры.
 *
 * Один запрос уходит в модель с temperature = 0, 0.7 и 1.2, каждый — по нескольку
 * раз. Одного прогона мало: при 0 ответы часто совпадают, при 1.2 расходятся —
 * разнообразие видно только на повторах.
 */

private val REPEAT_OPTIONS = listOf(1, 3, 5)

private data class TemperatureRun(
    val temperature: Double,
    val index: Int,
    val answer: LlmAnswer,
)

@Composable
fun TemperatureLabScreen(apiKey: String, modifier: Modifier = Modifier) {
    if (apiKey.isBlank()) {
        MissingKeyHint(modifier)
        return
    }

    val client = remember(apiKey) { AnthropicLlmClient(apiKey) }
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<TemperatureRun>() }

    var prompt by remember { mutableStateOf(TEMPERATURE_LAB_PROMPT) }
    var repeats by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (busy) return
        busy = true
        error = null
        runs.clear()
        requested = repeats * TEMPERATURE_PRESETS.size

        val text = prompt.trim()
        val history = temperatureLabRequest(text)
        job = scope.launch {
            try {
                TEMPERATURE_PRESETS.forEach { temperature ->
                    repeat(repeats) { attempt ->
                        val answer = client.answer(history, temperatureSpec(temperature))
                        runs += TemperatureRun(
                            temperature = temperature,
                            index = attempt + 1,
                            answer = answer,
                        )
                    }
                }
            } catch (e: CancellationException) {
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
        Text("Температура", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Один запрос уходит в модель с temperature = 0, 0.7 и 1.2. " +
                "Сравнивайте ответы сами: точность, креативность и разнообразие " +
                "здесь не оцениваются автоматически.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Запрос") },
            trailingIcon = {
                if (prompt != TEMPERATURE_LAB_PROMPT) {
                    TextButton(onClick = { prompt = TEMPERATURE_LAB_PROMPT }) {
                        Text("↺")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Температуры", style = MaterialTheme.typography.titleSmall)
        TEMPERATURE_LEVELS.forEach { level ->
            Column(Modifier.padding(bottom = 4.dp)) {
                val sent = effectiveTemperature(level.value)
                val clampNote = if (temperatureWasClamped(level.value)) " (в API: $sent)" else ""
                Text(
                    text = "temperature = ${level.title}$clampNote",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = level.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прогонов на температуру:", style = MaterialTheme.typography.bodyMedium)
            REPEAT_OPTIONS.forEach { count ->
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { repeats = count }, enabled = repeats != count) {
                    Text("$count")
                }
            }
        }
        Text(
            text = "запросов к API: ${repeats * TEMPERATURE_PRESETS.size}",
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
                Text("Прогнать три температуры")
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
            Verdict(requested, runs)
            runs.forEach { RunCard(it) }
        }
    }
}

@Composable
private fun Verdict(requested: Int, runs: List<TemperatureRun>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Сводка", style = MaterialTheme.typography.titleSmall)
            TEMPERATURE_PRESETS.forEach { temperature ->
                val batch = runs.filter { it.temperature == temperature }
                if (batch.isEmpty()) return@forEach
                val texts = batch.map { it.answer.text }
                val unique = distinctCount(texts)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("temperature = $temperature", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "токенов: ${batch.sumOf { it.answer.outputTokens }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = diversityLabel(unique, batch.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (unique == 1 && batch.size > 1) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
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
private fun RunCard(run: TemperatureRun) {
    var expanded by remember { mutableStateOf(false) }
    val text = run.answer.text.ifEmpty { "(пусто)" }
    val preview = text.lineSequence().take(3).joinToString("\n")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "temperature = ${run.temperature} · прогон ${run.index}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (expanded) text else preview,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (text.lines().size > 3 || text.length > preview.length) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Развернуть")
                }
            }
        }
    }
}
