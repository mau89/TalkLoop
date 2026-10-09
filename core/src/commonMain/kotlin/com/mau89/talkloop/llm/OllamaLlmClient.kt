package com.mau89.talkloop.llm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val DEFAULT_OLLAMA_MODEL = "qwen3.5:9b"
const val OLLAMA_CONTEXT_WINDOW_TOKENS = 16_384

/** Native local API; never downloads models or falls back to a cloud provider. */
class OllamaLlmClient(
    baseUrl: String,
    private val model: String = DEFAULT_OLLAMA_MODEL,
    private val contextWindowTokens: Int = OLLAMA_CONTEXT_WINDOW_TOKENS,
    private val httpEngine: HttpClientEngine? = null,
) : LlmClient {
    private val endpoint = baseUrl.trim().trimEnd('/').also {
        require(isValidOllamaAddress(it)) { "Укажите адрес Ollama, например http://10.0.2.2:11434" }
    }
    private val http = if (httpEngine == null) HttpClient { configureLocalClient() }
        else HttpClient(httpEngine) { configureLocalClient() }

    private fun HttpClientConfig<*>.configureLocalClient() {
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
    }

    init {
        requireLocalModel(model)
        require(contextWindowTokens > 0)
    }

    override fun close() {
        http.close()
        httpEngine?.close()
    }

    /** Ollama has no standalone token-count API. UTF-8 bytes + template overhead
     * are a conservative preflight estimate, not measured tokens. Actual usage
     * below comes from prompt_eval_count/eval_count in the generation response. */
    override suspend fun countInputTokens(history: List<ChatMessage>, spec: ResponseSpec): Int =
        estimateOllamaInputTokens(history, spec)

    override suspend fun reply(history: List<ChatMessage>): String =
        answer(history, ResponseSpec(system = TUTOR_SYSTEM_PROMPT)).text

    override suspend fun answer(history: List<ChatMessage>, spec: ResponseSpec): LlmAnswer {
        val requestModel = spec.model ?: model
        requireLocalModel(requestModel)
        require(spec.maxTokens > 0)
        val inputEstimate = estimateOllamaInputTokens(history, spec)
        if (inputEstimate.toLong() + spec.maxTokens > contextWindowTokens) {
            throw ContextWindowExceededException(inputEstimate, contextWindowTokens,
                "Оценка контекста Ollama превышает лимит с учётом места для ответа. " +
                    "Сократите запрос или начните новый диалог.")
        }
        val messages = buildList {
            spec.system?.let { add(OllamaMessage("system", it)) }
            history.forEach { add(OllamaMessage(if (it.fromUser) "user" else "assistant", it.text)) }
        }
        val response = localRequest {
            http.post("$endpoint/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(OllamaChatRequest(requestModel, messages, format = spec.jsonSchema,
                    options = OllamaOptions(spec.maxTokens, contextWindowTokens, spec.temperature,
                        spec.stopSequences.ifEmpty { null })))
            }.body<OllamaChatResponse>()
        }
        if (!response.done || response.message.content.isBlank()) {
            throw LlmException("Ollama вернула незавершённый или пустой ответ. Повторите запрос.")
        }
        return LlmAnswer(
            text = response.message.content.trim(),
            stopReason = if (response.doneReason == "length") "max_tokens" else response.doneReason,
            stopSequence = null,
            inputTokens = response.promptEvalCount,
            outputTokens = response.evalCount,
        )
    }

    /** Check only the local registry; do not generate or download anything. */
    suspend fun checkConnection(): String = localRequest {
        val tags = http.get("$endpoint/api/tags").body<OllamaTags>()
        val name = if (':' in model) model else "$model:latest"
        if (tags.models.none { it.name == name || it.model == name }) {
            throw LlmException("Модель $model не скачана. На Mac выполните: ollama pull $model")
        }
        "Ollama доступна · $model скачана"
    }

    private suspend fun <T> localRequest(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        throw e
    } catch (e: ResponseException) {
        throw LlmException("Ollama: ${e.response.status.value}: ${e.response.bodyAsText()}", e)
    } catch (e: Exception) {
        throw LlmException("Не удалось обратиться к Ollama по адресу $endpoint. " +
            "Проверьте, что Ollama запущена на Mac и модель скачана.", e)
    }
}

fun isValidOllamaAddress(address: String): Boolean = runCatching {
    val url = Url(address.trim())
    (url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) &&
        url.host.isNotBlank() && url.user == null && url.password == null &&
        url.parameters.isEmpty() && url.fragment.isEmpty()
}.getOrDefault(false)

private fun requireLocalModel(model: String) {
    require(model.isNotBlank() && !model.trim().endsWith(":cloud", ignoreCase = true)) {
        "Выберите скачанную локальную модель без суффикса :cloud"
    }
}

internal fun estimateOllamaInputTokens(history: List<ChatMessage>, spec: ResponseSpec): Int =
    (32L + (spec.system?.encodeToByteArray()?.size ?: 0) +
        history.sumOf { it.text.encodeToByteArray().size.toLong() + 16 } +
        (spec.jsonSchema?.toString()?.encodeToByteArray()?.size ?: 0))
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

@Serializable
private data class OllamaMessage(val role: String, val content: String)

@Serializable
private data class OllamaChatRequest(
    val model: String, val messages: List<OllamaMessage>,
    val stream: Boolean = false, val think: Boolean = false,
    val format: JsonObject? = null, val options: OllamaOptions,
)

@Serializable
private data class OllamaOptions(
    @SerialName("num_predict") val numPredict: Int,
    @SerialName("num_ctx") val numCtx: Int,
    val temperature: Double? = null, val stop: List<String>? = null,
)

@Serializable
private data class OllamaChatResponse(
    val message: OllamaMessage, val done: Boolean,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0,
)

@Serializable
private data class OllamaTags(val models: List<OllamaTag>)

@Serializable
private data class OllamaTag(val name: String, val model: String = name)
