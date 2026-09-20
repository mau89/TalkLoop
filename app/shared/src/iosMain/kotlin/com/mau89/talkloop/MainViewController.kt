package com.mau89.talkloop

import androidx.compose.ui.window.ComposeUIViewController

// Ключ подставляет Gradle в Secrets.kt из secrets.properties, как и на Android.
fun MainViewController() = createPersistentChatHistoryStore().let { agentHistoryStore ->
    val agentInvariantStore = createPersistentInvariantStore()
    ComposeUIViewController {
        App(
            apiKey = IOS_ANTHROPIC_API_KEY,
            agentHistoryStore = agentHistoryStore,
            agentInvariantStore = agentInvariantStore,
        )
    }
}
