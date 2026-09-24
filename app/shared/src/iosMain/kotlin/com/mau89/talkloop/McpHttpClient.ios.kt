package com.mau89.talkloop

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.sse.SSE

internal actual fun createMcpHttpClient(): HttpClient = HttpClient(Darwin) {
    install(SSE)
}
