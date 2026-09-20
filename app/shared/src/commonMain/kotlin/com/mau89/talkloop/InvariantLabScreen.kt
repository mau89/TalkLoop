package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mau89.talkloop.llm.AgentInvariant
import com.mau89.talkloop.llm.InvariantCheckStage
import com.mau89.talkloop.llm.TalkLoopAgent
import kotlinx.coroutines.launch

/** День 14: отдельный экран просмотра и редактирования инвариантов агента. */
@Composable
fun InvariantLabScreen(
    agent: TalkLoopAgent,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val check by agent.lastInvariantCheck.collectAsState()
    val rules by agent.invariantRules.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var id by remember { mutableStateOf("") }
    var statement by remember { mutableStateOf("") }
    var rationale by remember { mutableStateOf("") }
    var markers by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun clearEditor() {
        editingId = null
        id = ""
        statement = ""
        rationale = ""
        markers = ""
    }

    fun edit(invariant: AgentInvariant) {
        editingId = invariant.id
        id = invariant.id
        statement = invariant.statement
        rationale = invariant.rationale
        markers = invariant.requestConflictMarkers.joinToString("\n")
        status = null
    }

    val conflictMarkers = markers.lines().map(String::trim).filter(String::isNotEmpty)
    val canSave = id.isNotBlank() && statement.isNotBlank() && rationale.isNotBlank() &&
        conflictMarkers.isNotEmpty()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("День 14 · Инварианты", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Правила хранятся отдельно от диалога и проверяют запрос до LLM, " +
                "а готовый ответ — перед показом.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Активные правила · ${rules.size}", style = MaterialTheme.typography.titleMedium)
                val checkStatus = when {
                    check.stage == InvariantCheckStage.NOT_CHECKED ->
                        "Ожидают первого запроса агента."
                    check.allowed ->
                        "Последняя проверка: конфликтов нет."
                    else ->
                        "Последний отказ: ${check.violations.joinToString { it.invariantId }}."
                }
                Text(
                    checkStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.allowed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        rules.forEach { invariant ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "[${invariant.id}]",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(invariant.statement, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        invariant.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { edit(invariant) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Изменить") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { agent.deleteInvariant(invariant.id) }
                                        .onSuccess {
                                            if (editingId == invariant.id) clearEditor()
                                            status = "Инвариант удалён: ${invariant.id}"
                                        }
                                        .onFailure { status = it.message }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Удалить") }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (editingId == null) "Новый инвариант" else "Редактирование $editingId",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            if (editingId != null) {
                TextButton(onClick = { clearEditor() }) { Text("Новый") }
            }
        }
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("ID") },
            placeholder = { Text("например, BUSINESS-002") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = statement,
            onValueChange = { statement = it },
            label = { Text("Обязательное правило") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rationale,
            onValueChange = { rationale = it },
            label = { Text("Почему правило обязательно") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = markers,
            onValueChange = { markers = it },
            label = { Text("Конфликтные фразы · по одной на строку") },
            supportingText = { Text("Проверяются в запросе и готовом ответе") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val previousId = editingId
                scope.launch {
                    runCatching {
                        agent.saveInvariant(
                            invariant = AgentInvariant(
                                id = id.trim(),
                                statement = statement.trim(),
                                rationale = rationale.trim(),
                                requestConflictMarkers = conflictMarkers,
                                responseConflictMarkers = conflictMarkers,
                            ),
                            previousId = previousId,
                        )
                    }.onSuccess {
                        status = "Инвариант сохранён: ${id.trim()}"
                        clearEditor()
                    }.onFailure { status = it.message }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (editingId == null) "Добавить инвариант" else "Сохранить изменения")
        }
        TextButton(
            onClick = {
                scope.launch {
                    runCatching { agent.resetInvariants() }
                        .onSuccess {
                            clearEditor()
                            status = "Восстановлены инварианты по умолчанию"
                        }
                        .onFailure { status = it.message }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Восстановить правила по умолчанию") }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
