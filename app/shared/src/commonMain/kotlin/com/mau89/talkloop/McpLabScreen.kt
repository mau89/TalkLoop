package com.mau89.talkloop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PUBLIC_MCP_URL = "https://mcp.deepwiki.com/mcp"

private data class McpToolInfo(
    val name: String,
    val description: String?,
)

/** День 16: живое MCP-подключение и discovery доступных инструментов. */
@Composable
fun McpLabScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(PUBLIC_MCP_URL) }
    var connectedUrl by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf(emptyList<McpToolInfo>()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (loading) return
        loading = true
        connected = false
        connectedUrl = null
        tools = emptyList()
        error = null

        scope.launch {
            try {
                val requestedUrl = serverUrl.trim()
                tools = requestMcpTools(requestedUrl)
                connected = true
                connectedUrl = requestedUrl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться к MCP"
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("День 16 · MCP", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Приложение подключается к публичному DeepWiki MCP и запрашивает только " +
                "список доступных инструментов. Сами инструменты не вызываются.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("MCP-сервер", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { value ->
                        serverUrl = value
                        connected = false
                        connectedUrl = null
                        tools = emptyList()
                        error = null
                    },
                    enabled = !loading,
                    label = { Text("URL сервера") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Нужен MCP-сервер со Streamable HTTP. Для DeepWiki авторизация не требуется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = ::connect,
            enabled = !loading && serverUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (connected) "Запросить список ещё раз" else "Подключиться и получить tools/list")
        }

        if (loading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Устанавливаем MCP-соединение…")
            }
        }

        error?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Ошибка подключения",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (connected) {
            Text(
                "Соединение установлено · инструментов: ${tools.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            connectedUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            tools.forEachIndexed { index, tool ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${index + 1}. ${tool.name}", style = MaterialTheme.typography.titleSmall)
                        tool.description?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun requestMcpTools(serverUrl: String): List<McpToolInfo> {
    val httpClient = HttpClient { install(SSE) }
    val client = Client(
        clientInfo = Implementation(
            name = "talkloop-day16-app",
            version = "1.0.0",
        ),
    )

    return try {
        client.connect(
            StreamableHttpClientTransport(
                client = httpClient,
                url = serverUrl,
            ),
        )
        client.listTools().tools.map { tool ->
            McpToolInfo(tool.name, tool.description)
        }
    } finally {
        client.close()
        httpClient.close()
    }
}
