package com.mau89.talkloop

import androidx.compose.ui.window.ComposeUIViewController

// Ключ подставляет Gradle в Secrets.kt из secrets.properties, как и на Android.
fun MainViewController() = ComposeUIViewController { App(apiKey = IOS_ANTHROPIC_API_KEY) }
