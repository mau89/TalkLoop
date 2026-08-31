package com.mau89.talkloop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun App(apiKey: String) {
    MaterialTheme {
        ChatScreen(
            apiKey = apiKey,
            modifier = Modifier.fillMaxSize().safeContentPadding(),
        )
    }
}
