package com.mau89.talkloop

import io.ktor.client.HttpClient

/** Платформенный HTTP-клиент для локального долгоживущего MCP-соединения. */
internal expect fun createMcpHttpClient(): HttpClient
