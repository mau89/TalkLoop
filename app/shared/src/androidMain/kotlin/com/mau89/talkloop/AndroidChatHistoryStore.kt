package com.mau89.talkloop

import android.content.Context
import com.mau89.talkloop.llm.ChatHistoryStore
import com.mau89.talkloop.llm.JsonChatHistoryStore
import com.mau89.talkloop.llm.StringStore

fun createPersistentChatHistoryStore(context: Context): ChatHistoryStore {
    val preferences = context.getSharedPreferences("talkloop_history", Context.MODE_PRIVATE)
    return JsonChatHistoryStore(
        object : StringStore {
            override fun read(key: String): String? = preferences.getString(key, null)

            override fun write(key: String, value: String) {
                check(preferences.edit().putString(key, value).commit()) {
                    "Не удалось сохранить историю диалога"
                }
            }

            override fun remove(key: String) {
                check(preferences.edit().remove(key).commit()) {
                    "Не удалось очистить историю диалога"
                }
            }
        }
    )
}
