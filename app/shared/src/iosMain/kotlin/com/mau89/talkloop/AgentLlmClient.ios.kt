package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun createAgentLlmClient(apiKey: String, directConnection: Boolean) = AnthropicLlmClient(
    apiKey = apiKey,
    httpEngine = Darwin.create {
        if (directConnection) configureSession { connectionProxyDictionary = emptyMap<Any?, Any>() }
    },
)
