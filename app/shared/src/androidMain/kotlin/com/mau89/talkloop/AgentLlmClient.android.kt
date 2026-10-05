package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy

internal actual fun createAgentLlmClient(apiKey: String, directConnection: Boolean) = AnthropicLlmClient(
    apiKey = apiKey,
    httpEngine = OkHttp.create {
        if (directConnection) config { proxy(Proxy.NO_PROXY) }
    },
)
