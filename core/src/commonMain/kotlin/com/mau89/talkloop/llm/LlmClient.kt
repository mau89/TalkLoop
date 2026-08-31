package com.mau89.talkloop.llm

/** Одна реплика разговора. */
data class ChatMessage(val fromLearner: Boolean, val text: String)

/**
 * Абстракция над провайдером LLM: смена провайдера или модели не должна
 * задевать UI и логику разговора (Strategy.md, риск стоимости вызовов).
 */
interface LlmClient {
    /** Отправляет всю историю разговора и возвращает ответ репетитора. */
    suspend fun reply(history: List<ChatMessage>): String
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
