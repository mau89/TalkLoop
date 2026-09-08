package com.mau89.talkloop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val agentHistoryStore = createPersistentChatHistoryStore(applicationContext)

        setContent {
            App(
                apiKey = BuildConfig.ANTHROPIC_API_KEY,
                agentHistoryStore = agentHistoryStore,
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(apiKey = "")
}
