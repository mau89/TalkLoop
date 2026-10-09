package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.OllamaLlmClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy

internal actual fun createAgentLlmClient(apiKey: String, directConnection: Boolean) = AnthropicLlmClient(
    apiKey = apiKey,
    httpEngine = OkHttp.create {
        if (directConnection) config { proxy(Proxy.NO_PROXY) }
    },
)

internal actual fun defaultOllamaAddress() = "http://10.0.2.2:11434"

internal actual fun createOllamaLlmClient(address: String, model: String) = OllamaLlmClient(
    baseUrl = address,
    model = model,
    httpEngine = OkHttp.create { config { proxy(Proxy.NO_PROXY) } },
)
