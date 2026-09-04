package com.mau89.talkloop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mau89.talkloop.llm.ChatMessage

private val TABS = listOf("Разговор", "Формат", "Мышление", "Температура", "Модели")

@Composable
fun App(apiKey: String) {
    MaterialTheme {
        // История живёт здесь, а не в ChatScreen: иначе переключение вкладки
        // выбрасывает её из композиции вместе с разговором.
        val history = remember { mutableStateListOf<ChatMessage>() }
        var tab by remember { mutableStateOf(0) }

        Column(Modifier.fillMaxSize().safeContentPadding()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> ChatScreen(apiKey, history, Modifier.fillMaxSize())
                1 -> FormatLabScreen(apiKey, Modifier.fillMaxSize())
                2 -> ReasoningLabScreen(apiKey, Modifier.fillMaxSize())
                3 -> TemperatureLabScreen(apiKey, Modifier.fillMaxSize())
                else -> ModelLabScreen(apiKey, Modifier.fillMaxSize())
            }
        }
    }
}
