package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient
import com.mau89.talkloop.llm.OllamaLlmClient
import com.mau89.talkloop.llm.OllamaTuning

/** Proxy selection belongs to the owned main-agent transport, not to global app settings. */
internal expect fun createAgentLlmClient(apiKey: String, directConnection: Boolean): AnthropicLlmClient

internal expect fun createOllamaLlmClient(address: String, model: String, tuning: OllamaTuning, serviceToken: String = ""): OllamaLlmClient
