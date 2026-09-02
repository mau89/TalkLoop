package com.mau89.talkloop.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Разбор ошибок в конце сессии — вход в SRS-очередь (Strategy.md).
 *
 * Схема и разбор лежат здесь готовыми, но с кнопкой «Разбор» на экране разговора
 * ещё не соединены: сейчас она возвращает свободный текст. Это следующий шаг,
 * а не часть лаборатории формата.
 */
data class Recap(val mistakes: List<RecapMistake>, val words: List<String>)

data class RecapMistake(val learner: String, val corrected: String, val rule: String)

val RECAP_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("mistakes") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("learner") { put("type", "string") }
                    putJsonObject("corrected") { put("type", "string") }
                    putJsonObject("rule") { put("type", "string") }
                }
                putJsonArray("required") { add("learner"); add("corrected"); add("rule") }
                put("additionalProperties", false)
            }
        }
        putJsonObject("words") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
    }
    putJsonArray("required") { add("mistakes"); add("words") }
    put("additionalProperties", false)
}

/** null — ответ не в том формате, в SRS такое не отправить. */
fun parseRecap(text: String): Recap? {
    val json = Json { ignoreUnknownKeys = true }
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val mistakes = runCatching {
        root.getValue("mistakes").jsonArray.map { item ->
            val fields = item.jsonObject
            RecapMistake(
                learner = fields.text("learner"),
                corrected = fields.text("corrected"),
                rule = fields.text("rule"),
            )
        }
    }.getOrNull() ?: return null
    val words = runCatching { root.getValue("words").jsonArray.map { it.jsonPrimitive.content } }
        .getOrNull() ?: return null
    return Recap(mistakes, words)
}

private fun JsonObject.text(key: String): String =
    getValue(key).jsonPrimitive.contentOrNull ?: error("$key не строка")
