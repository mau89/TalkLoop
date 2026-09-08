package com.mau89.talkloop.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable

/** Одна реплика разговора. */
@Serializable
data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * Чем запрос ограничивает ответ модели.
 *
 * Разделение принципиальное: [system] и [stopSequences] — это просьба и грубый
 * обрыв, их модель может обойти. [jsonSchema] проверяет API, и только он даёт
 * гарантию, что формат будет одинаковым от запроса к запросу.
 */
data class ResponseSpec(
    val system: String? = null,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stopSequences: List<String> = emptyList(),
    val jsonSchema: JsonObject? = null,
    /** null — температура провайдера по умолчанию (обычно 1.0). */
    val temperature: Double? = null,
    /** null — модель транспорта по умолчанию; агент всегда задаёт её явно. */
    val model: String? = null,
)

/** Ответ модели вместе с тем, как он закончился — без этого режимы не сравнить. */
data class LlmAnswer(
    val text: String,
    val stopReason: String?,
    val stopSequence: String?,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Абстракция над провайдером LLM: смена провайдера или модели не должна
 * задевать UI и логику разговора (Strategy.md, риск стоимости вызовов).
 */
interface LlmClient {
    /** Отправляет всю историю разговора и возвращает ответ репетитора. */
    suspend fun reply(history: List<ChatMessage>): String

    /** То же, но с явными ограничениями формата и с диагностикой ответа. */
    suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
