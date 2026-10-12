package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.OllamaLlmClient
import com.mau89.talkloop.llm.OllamaTuning
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun createAgentLlmClient(apiKey: String, directConnection: Boolean) = AnthropicLlmClient(
    apiKey = apiKey,
    httpEngine = Darwin.create {
        if (directConnection) configureSession { connectionProxyDictionary = emptyMap<Any?, Any>() }
    },
)

internal actual fun defaultOllamaAddress() = "http://127.0.0.1:11434"

@OptIn(ExperimentalForeignApi::class)
internal actual fun createOllamaLlmClient(address: String, model: String, tuning: OllamaTuning) = OllamaLlmClient(
    baseUrl = address,
    model = model,
    tuning = tuning,
    httpEngine = Darwin.create {
        configureSession { connectionProxyDictionary = emptyMap<Any?, Any>() }
    },
)
