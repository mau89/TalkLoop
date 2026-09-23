package com.mau89.talkloop.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking

private const val DEFAULT_MCP_URL = "https://mcp.deepwiki.com/mcp"

/**
 * Подключается к MCP по Streamable HTTP и выполняет только discovery-запрос tools/list.
 * URL другого MCP-сервера можно передать первым аргументом командной строки.
 */
fun main(args: Array<String>) = runBlocking {
    val serverUrl = args.firstOrNull() ?: DEFAULT_MCP_URL

    HttpClient(CIO) { install(SSE) }.use { httpClient ->
        val client = Client(
            clientInfo = Implementation(
                name = "talkloop-day16",
                version = "1.0.0",
            ),
        )

        try {
            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = serverUrl,
            )

            println("Подключение к MCP: $serverUrl")
            client.connect(transport)
            println("Соединение установлено")

            val tools = client.listTools().tools
            println("Получено инструментов: ${tools.size}")
            tools.forEachIndexed { index, tool ->
                println("${index + 1}. ${tool.name}")
                tool.description?.let { println("   $it") }
            }
        } finally {
            client.close()
        }
    }
}
