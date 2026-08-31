package com.mau89.talkloop

import androidx.compose.ui.window.ComposeUIViewController

// Ключ на iOS пока не прокинут — экран покажет подсказку. Android идёт первым (Strategy.md).
fun MainViewController() = ComposeUIViewController { App(apiKey = "") }
