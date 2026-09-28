package com.mau89.talkloop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BookSearchItem(
    val workKey: String,
    val title: String,
    val authors: List<String>,
    val firstPublishYear: Int?,
    val coverId: Int?,
)

data class BookDetails(
    val workKey: String,
    val title: String,
    val description: String?,
    val subjects: List<String>,
)

interface BookApi {
    suspend fun searchBooks(query: String, language: String, limit: Int): List<BookSearchItem>
    suspend fun getBookDetails(workKey: String): BookDetails?
}

class OpenLibraryBookApi : BookApi, AutoCloseable {
    private val http = HttpClient(CIO)

    override suspend fun searchBooks(
        query: String,
        language: String,
        limit: Int,
    ): List<BookSearchItem> {
        val response = http.get("$BASE_URL/search.json") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            parameter("q", query)
            parameter("lang", language)
            parameter("limit", limit)
            parameter("fields", "key,title,author_name,first_publish_year,cover_i")
        }.bodyAsText()
        val root = JSON.parseToJsonElement(response) as? JsonObject ?: return emptyList()
        val documents = root["docs"] as? JsonArray ?: return emptyList()
        return documents.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val key = item.string("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.string("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            BookSearchItem(
                workKey = key,
                title = title,
                authors = (item["author_name"] as? JsonArray)
                    ?.mapNotNull { it.asText() }
                    .orEmpty(),
                firstPublishYear = item["first_publish_year"]?.jsonPrimitive?.intOrNull,
                coverId = item["cover_i"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    override suspend fun getBookDetails(workKey: String): BookDetails? {
        val normalized = workKey.trim().let { key ->
            when {
                key.matches(Regex("^/works/OL[0-9]+W$")) -> key
                key.matches(Regex("^OL[0-9]+W$")) -> "/works/$key"
                else -> return null
            }
        }
        val response = http.get("$BASE_URL$normalized.json") {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.bodyAsText()
        val root = JSON.parseToJsonElement(response) as? JsonObject ?: return null
        return BookDetails(
            workKey = normalized,
            title = root.string("title"),
            description = root["description"].asText(),
            subjects = (root["subjects"] as? JsonArray)
                ?.mapNotNull { it.asText() }
                ?.take(8)
                .orEmpty(),
        )
    }

    override fun close() = http.close()

    private companion object {
        const val BASE_URL = "https://openlibrary.org"
        const val USER_AGENT = "TalkLoop/1.0 (educational MCP project)"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

data class BookMood(
    val key: String,
    val title: String,
    val query: String,
    val reason: String,
)

internal data class BookWeatherInput(
    val city: String,
    val condition: String,
    val feelsLikeC: Double,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
)

internal fun chooseBookMood(weather: BookWeatherInput): BookMood {
    val condition = weather.condition.lowercase()
    fun has(vararg fragments: String): Boolean = fragments.any(condition::contains)
    return when {
        has("гроз", "шторм", "thunder", "storm") || weather.windSpeedKmh >= 35 -> BookMood(
            "thriller", "триллер", "thriller suspense",
            "Напряжённая погода подходит для динамичной истории.",
        )
        has("снег", "метел", "snow", "blizzard") -> BookMood(
            "fantasy", "фэнтези", "fantasy winter fiction",
            "Снег создаёт атмосферу сказочного зимнего мира.",
        )
        has("туман", "дымк", "fog", "mist") -> BookMood(
            "gothic", "готика и мистика", "gothic fiction mystery",
            "Туманная погода сочетается с загадочной историей.",
        )
        has("дожд", "лив", "морос", "rain", "drizzle") ||
            weather.precipitationMm >= 0.5 -> BookMood(
            "detective", "детектив", "detective fiction cozy mystery",
            "Дождливая погода подходит для спокойного детектива дома.",
        )
        weather.windSpeedKmh >= 25 -> BookMood(
            "sea_adventure", "морские приключения", "sea stories adventure fiction",
            "Сильный ветер напоминает о море и путешествиях.",
        )
        weather.feelsLikeC >= 28 -> BookMood(
            "humor", "юмор и короткие рассказы", "humor short stories",
            "В жару лучше подходит лёгкое и короткое чтение.",
        )
        weather.feelsLikeC <= -10 -> BookMood(
            "historical", "исторический роман", "historical fiction",
            "Морозный вечер располагает к длинной атмосферной истории.",
        )
        has("ясн", "солнеч", "clear", "sunny") && weather.feelsLikeC >= 15 -> BookMood(
            "adventure", "приключения и путешествия", "adventure travel fiction",
            "Хорошая погода создаёт настроение открытий и путешествий.",
        )
        has("ясн", "солнеч", "clear", "sunny") -> BookMood(
            "science_fiction", "научная фантастика", "science fiction",
            "Ясная прохладная погода подходит для сосредоточенного чтения.",
        )
        has("облач", "пасмур", "cloud", "overcast") -> BookMood(
            "classic", "классика и психологическая проза", "classic literature psychological fiction",
            "Пасмурная погода создаёт спокойное задумчивое настроение.",
        )
        else -> BookMood(
            "contemporary", "современная проза", "contemporary fiction",
            "Нейтральная погода подходит для современной прозы.",
        )
    }
}

fun createBookMcpServer(bookApi: BookApi): Server = Server(
    serverInfo = Implementation(
        name = "talkloop-books",
        version = "1.0.0",
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        ),
    ),
).apply {
    addTool(
        name = "choose_book_mood",
        description = "Подобрать жанр и поисковый запрос для книг по структурированным данным погоды.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("weather_data", bookWeatherInputSchema())
            },
            required = listOf("weather_data"),
        ),
        outputSchema = bookMoodOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Определить книжное настроение",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        try {
            val weatherData = request.arguments?.get("weather_data") as? JsonObject
                ?: error("Передайте weather_data из погодного MCP-сервера")
            val weather = weatherData.toBookWeatherInput()
            val mood = chooseBookMood(weather)
            CallToolResult(
                content = listOf(TextContent("Для погоды в ${weather.city}: ${mood.title}.")),
                structuredContent = mood.toJson(),
                isError = false,
            )
        } catch (e: Exception) {
            bookToolError(e.message ?: "Не удалось определить жанр")
        }
    }

    addTool(
        name = "search_books",
        description = "Найти реальные книги в каталоге Open Library по теме, жанру или названию.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Тема, жанр или название книги")
                    put("minLength", 2)
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Предпочтительный язык результатов, по умолчанию ru")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 5)
                })
            },
            required = listOf("query"),
        ),
        outputSchema = bookSearchOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Найти книги",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        try {
            val query = request.arguments?.get("query")?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.length >= 2) { "Укажите запрос минимум из двух символов" }
            val language = request.arguments?.get("language")?.jsonPrimitive?.content
                ?.takeIf(String::isNotBlank) ?: "ru"
            val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 3
            require(limit in 1..5) { "Количество книг должно быть от 1 до 5" }
            val books = bookApi.searchBooks(query, language, limit)
            require(books.isNotEmpty()) { "По запросу «$query» книги не найдены" }
            val result = buildJsonObject {
                put("query", query)
                put("language", language)
                put("count", books.size)
                put("books", buildJsonArray { books.forEach { add(it.toJson()) } })
            }
            CallToolResult(
                content = listOf(TextContent("Найдено книг: ${books.size}.")),
                structuredContent = result,
                isError = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bookToolError(e.message ?: "Не удалось найти книги")
        }
    }

    addTool(
        name = "get_book_details",
        description = "Получить описание и темы выбранной книги по ключу Open Library.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("work_key", buildJsonObject {
                    put("type", "string")
                    put("description", "Ключ работы из результата search_books")
                })
            },
            required = listOf("work_key"),
        ),
        outputSchema = bookDetailsOutputSchema(),
        toolAnnotations = ToolAnnotations(
            title = "Получить сведения о книге",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    ) { request ->
        try {
            val workKey = request.arguments?.get("work_key")?.jsonPrimitive?.content.orEmpty()
            val details = bookApi.getBookDetails(workKey)
                ?: error("Книга с ключом «$workKey» не найдена")
            CallToolResult(
                content = listOf(TextContent("Получены сведения о книге «${details.title}».")),
                structuredContent = details.toJson(),
                isError = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bookToolError(e.message ?: "Не удалось получить сведения о книге")
        }
    }
}

private fun JsonObject.toBookWeatherInput(): BookWeatherInput = BookWeatherInput(
    city = requiredText("city"),
    condition = requiredText("condition"),
    feelsLikeC = requiredNumber("feels_like_c"),
    precipitationMm = requiredNumber("precipitation_mm"),
    windSpeedKmh = requiredNumber("wind_speed_kmh"),
)

private fun BookMood.toJson(): JsonObject = buildJsonObject {
    put("mood_key", key)
    put("genre", title)
    put("query", query)
    put("reason", reason)
}

private fun BookSearchItem.toJson(): JsonObject = buildJsonObject {
    put("work_key", workKey)
    put("title", title)
    put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
    firstPublishYear?.let { put("first_publish_year", it) }
    put("open_library_url", "https://openlibrary.org$workKey")
    coverId?.let {
        put("cover_url", "https://covers.openlibrary.org/b/id/$it-M.jpg?default=false")
    }
}

private fun BookDetails.toJson(): JsonObject = buildJsonObject {
    put("work_key", workKey)
    put("title", title)
    description?.let { put("description", it) }
    put("subjects", buildJsonArray { subjects.forEach { add(JsonPrimitive(it)) } })
    put("open_library_url", "https://openlibrary.org$workKey")
}

private fun bookWeatherInputSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", "Результат get_current_weather другого MCP-сервера")
    put("properties", buildJsonObject {
        put("city", stringSchema())
        put("condition", stringSchema())
        put("feels_like_c", numberSchema())
        put("precipitation_mm", numberSchema())
        put("wind_speed_kmh", numberSchema())
    })
    put("required", buildJsonArray {
        listOf("city", "condition", "feels_like_c", "precipitation_mm", "wind_speed_kmh")
            .forEach { add(JsonPrimitive(it)) }
    })
}

private fun bookMoodOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        listOf("mood_key", "genre", "query", "reason").forEach { put(it, stringSchema()) }
    },
    required = listOf("mood_key", "genre", "query", "reason"),
)

private fun bookSearchOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("query", stringSchema())
        put("language", stringSchema())
        put("count", buildJsonObject { put("type", "integer") })
        put("books", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "object") })
        })
    },
    required = listOf("query", "language", "count", "books"),
)

private fun bookDetailsOutputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("work_key", stringSchema())
        put("title", stringSchema())
        put("description", stringSchema())
        put("subjects", buildJsonObject {
            put("type", "array")
            put("items", stringSchema())
        })
        put("open_library_url", stringSchema())
    },
    required = listOf("work_key", "title", "subjects", "open_library_url"),
)

private fun stringSchema(): JsonObject = buildJsonObject { put("type", "string") }
private fun numberSchema(): JsonObject = buildJsonObject { put("type", "number") }

private fun JsonObject.requiredText(name: String): String =
    string(name).takeIf(String::isNotBlank) ?: error("В weather_data отсутствует поле $name")

private fun JsonObject.requiredNumber(name: String): Double =
    get(name)?.jsonPrimitive?.doubleOrNull
        ?: error("В weather_data отсутствует числовое поле $name")

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.content.orEmpty()

private fun JsonElement?.asText(): String? = when (this) {
    is JsonPrimitive -> content.takeIf(String::isNotBlank)
    is JsonObject -> get("value")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
    else -> null
}

private fun bookToolError(message: String): CallToolResult = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)
