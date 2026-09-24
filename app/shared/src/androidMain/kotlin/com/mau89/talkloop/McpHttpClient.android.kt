package com.mau89.talkloop

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import java.net.Proxy

/** 10.0.2.2 — локальный хост эмулятора; системный HTTP-прокси не должен его перехватывать. */
internal actual fun createMcpHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(SSE)
    engine {
        config {
            proxy(Proxy.NO_PROXY)
        }
    }
}
