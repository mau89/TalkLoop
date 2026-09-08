package com.mau89.talkloop

import com.mau89.talkloop.llm.ChatHistoryStore
import com.mau89.talkloop.llm.JsonChatHistoryStore
import com.mau89.talkloop.llm.StringStore
import platform.Foundation.NSUserDefaults

fun createPersistentChatHistoryStore(): ChatHistoryStore {
    val defaults = NSUserDefaults.standardUserDefaults
    return JsonChatHistoryStore(
        object : StringStore {
            override fun read(key: String): String? = defaults.stringForKey(key)

            override fun write(key: String, value: String) {
                defaults.setObject(value, forKey = key)
            }

            override fun remove(key: String) {
                defaults.removeObjectForKey(key)
            }
        }
    )
}
