package com.mau89.talkloop

import com.mau89.talkloop.llm.AnthropicLlmClient

/** Proxy selection belongs to the owned main-agent transport, not to global app settings. */
internal expect fun createAgentLlmClient(apiKey: String, directConnection: Boolean): AnthropicLlmClient
