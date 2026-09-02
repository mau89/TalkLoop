package com.mau89.talkloop.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Лаборатория формата ответа: автомобильный эксперт.
 *
 * Роль модели одна во всех режимах — отвечать только про автомобили, на остальное
 * коротко отказывать. Меняется только то, чем задан формат ответа, и в этом смысл:
 * видно, что предсказуемость даёт не вежливая просьба в промпте, а схема на стороне API.
 */

/** Уровень контроля формата — от «никакого» до гарантии на стороне API. */
enum class FormatMode(val title: String, val hint: String) {
    FREE(
        title = "Без ограничений",
        hint = "Роль эксперта есть, формат не задан: модель отвечает как хочет, обычным текстом.",
    ),
    STRICT_TEXT(
        title = "Строгие блоки, текстом",
        hint = "Формат описан словами. Обычно соблюдается, но гарантии нет.",
    ),
    JSON_PROMPT(
        title = "JSON просьбой",
        hint = "Просим JSON словами. Модель может добавить ```json, преамбулу или лишнее поле.",
    ),
    JSON_SCHEMA(
        title = "JSON схемой (output_config)",
        hint = "Схему проверяет API. Ответ валиден и одинаков от запроса к запросу.",
    ),
}

/**
 * Главные узлы машины. Набор фиксированный и зашит в схему обязательными полями —
 * поэтому любая машина описывается одинаково, и две модели можно сравнивать построчно.
 */
enum class CarComponent(val key: String, val title: String) {
    ENGINE("engine", "Двигатель"),
    TRANSMISSION("transmission", "Трансмиссия"),
    CHASSIS("chassis", "Подвеска и рулевое"),
    BRAKES("brakes", "Тормоза"),
    BODY("body", "Кузов и салон"),
    ELECTRONICS("electronics", "Электроника"),
}

/**
 * Стоп-слово по умолчанию. Само поле редактируется: смысл параметра не в том,
 * чтобы модель вежливо доложила о конце, а в том, чтобы оборвать её, когда она
 * поехала не туда — «В заключение», «Надеюсь, это помогло» и прочий хвост,
 * за который платишь токенами.
 */
const val DEFAULT_STOP_WORD = "КОНЕЦ"

/** Разбирает поле ввода: слова через запятую, пустые отбрасываем. */
fun parseStopWords(raw: String): List<String> =
    raw.split(",").map(String::trim).filter(String::isNotEmpty)

/** Строка отказа в текстовых режимах — чтобы отказ тоже был распознаваемым форматом. */
const val REFUSAL_PREFIX = "ОТКАЗ:"

private val BLOCK_PREFIXES = listOf("КОМПОНЕНТ:", "КОРОТКО:", "ПОДРОБНО:")

/**
 * Пределы настроек длины.
 *
 * Рычага два, и они разной природы: max_tokens режет вслепую на полуслове,
 * лимит словами — просьба, которую модель округляет в свою пользу. Поэтому
 * они настраиваются независимо, а не одним тумблером.
 */
val MAX_TOKENS_RANGE = 100..2000
const val MAX_TOKENS_STEP = 100
val WORD_LIMIT_RANGE = 20..200
const val WORD_LIMIT_STEP = 10
const val DEFAULT_WORD_LIMIT = 60

const val DEFAULT_CAR_PROMPT = "Toyota Levin"

/**
 * Готовые вопросы: два про машины и один заведомо мимо темы — чтобы отказ можно
 * было проверить в один тап, не набирая текст на телефоне.
 */
val EXAMPLE_PROMPTS = listOf(
    DEFAULT_CAR_PROMPT,
    "Чем дизель отличается от бензинового двигателя?",
    "Какая сегодня погода?",
)

/** Разобранный ответ эксперта. [components] пуст, когда вопрос не про автомобили. */
data class CarAnswer(
    val answerable: Boolean,
    val message: String,
    val car: String,
    val components: List<CarPart>,
)

data class CarPart(val component: CarComponent, val summary: String, val details: String)

/**
 * Схема ответа для structured outputs.
 *
 * Ограничения API: без рекурсии, без minItems/maxItems и прочих числовых границ,
 * additionalProperties у каждого объекта обязан быть false. Обязательные поля —
 * только три верхнего уровня: components намеренно необязателен, иначе при отказе
 * модель была бы вынуждена придумывать узлы несуществующей машины.
 */
val CAR_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("answerable") { put("type", "boolean") }
        putJsonObject("message") { put("type", "string") }
        putJsonObject("car") { put("type", "string") }
        putJsonObject("components") {
            put("type", "object")
            putJsonObject("properties") {
                CarComponent.entries.forEach { component ->
                    putJsonObject(component.key) {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("summary") { put("type", "string") }
                            putJsonObject("details") { put("type", "string") }
                        }
                        putJsonArray("required") { add("summary"); add("details") }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { CarComponent.entries.forEach { add(it.key) } }
            put("additionalProperties", false)
        }
    }
    putJsonArray("required") { add("answerable"); add("message"); add("car") }
    put("additionalProperties", false)
}

/** Роль модели. Одна и та же во всех режимах — меняется только формат вокруг неё. */
val EXPERT_PROMPT = """
    Ты — автомобильный эксперт. Отвечаешь только про автомобили: модели, устройство,
    узлы, эксплуатацию. На вопрос не про автомобили по существу не отвечай — коротко
    скажи, что это вне твоей темы. Отвечай по-русски.
""".trimIndent()

private val COMPONENT_LIST = CarComponent.entries.joinToString(", ") { "${it.key} (${it.title})" }

/** Формат ответа поверх роли. null — формат не задаём вовсе. */
fun formatPromptFor(mode: FormatMode): String? = when (mode) {
    FormatMode.FREE -> null
    FormatMode.STRICT_TEXT -> """
        Формат ответа — только такие блоки, по одному на узел машины:
        КОМПОНЕНТ: <название узла>
        КОРОТКО: <одна строка>
        ПОДРОБНО: <два-три предложения именно про эту машину>

        Узлы ровно эти и в этом порядке: $COMPONENT_LIST.
        Между блоками — пустая строка. Никаких вступлений, выводов и markdown.
        Если вопрос не про автомобили — вместо блоков выведи одну строку:
        $REFUSAL_PREFIX <причина>
    """.trimIndent()
    FormatMode.JSON_PROMPT -> """
        Ответ — только JSON, без markdown-обёртки и без текста вокруг:
        {"answerable":true,"message":"...","car":"...","components":{"engine":{"summary":"...","details":"..."}, ...}}

        В components ровно эти ключи: ${CarComponent.entries.joinToString(", ") { it.key }}.
        Если вопрос не про автомобили — answerable:false, car:"", поле components не выводи вовсе.
    """.trimIndent()
    FormatMode.JSON_SCHEMA -> """
        answerable — про автомобили ли вопрос. message — одной строкой: что за машина,
        либо причина отказа. car — модель, о которой речь, иначе пустая строка.
        components заполняй только когда answerable=true, и тогда все шесть узлов:
        summary одной строкой, details в двух-трёх предложениях именно про эту машину.
        При отказе поле components не выводи вовсе.
    """.trimIndent()
}

/** Собирает запрос из настроек экрана. [wordLimit] == null — длину словами не ограничиваем. */
fun buildSpec(
    mode: FormatMode,
    maxTokens: Int,
    wordLimit: Int? = null,
    stopWords: List<String> = emptyList(),
    askForStopWord: Boolean = false,
): ResponseSpec {
    // Просить модель дописать стоп-слово имеет смысл не всегда: под схемой она
    // запрещает любой текст вокруг JSON, так что инструкцию там не даём — иначе
    // просим невозможное. Сами стоп-слова всё равно отправляем: видно, что не сработали.
    val markerLine = if (askForStopWord && stopWords.isNotEmpty() && mode != FormatMode.JSON_SCHEMA) {
        "Закончив ответ, выведи на отдельной строке ${stopWords.first()} и остановись."
    } else {
        null
    }
    val lengthLine = wordLimit?.let { "Весь ответ — не длиннее $it слов." }

    return ResponseSpec(
        system = listOfNotNull(EXPERT_PROMPT, formatPromptFor(mode), lengthLine, markerLine)
            .joinToString("\n\n"),
        maxTokens = maxTokens,
        stopSequences = stopWords,
        jsonSchema = if (mode == FormatMode.JSON_SCHEMA) CAR_SCHEMA else null,
    )
}

/** Запрос уходит как есть — иначе «тот же самый запрос» было бы неправдой. */
fun formatLabRequest(prompt: String): List<ChatMessage> =
    listOf(ChatMessage(fromUser = true, text = prompt))

/**
 * Что удалось сказать про формат одного ответа.
 * [ok] == null — режим формат не задаёт, сравнивать не с чем.
 */
data class ShapeCheck(val ok: Boolean?, val note: String, val truncated: Boolean = false)

/**
 * Пригоден ли ответ дальше.
 *
 * Потолок max_tokens проверяем раньше формата: обрезанный ответ бесполезен, даже
 * если начало успело совпасть со структурой. В свободном режиме при этом не пишем
 * «не совпал» — там формат и не задавали, обрезку показываем отдельно.
 */
fun checkAnswer(mode: FormatMode, answer: LlmAnswer): ShapeCheck {
    if (answer.stopReason == "max_tokens") {
        val ok = if (mode == FormatMode.FREE) null else false
        return ShapeCheck(ok, "обрезан потолком max_tokens", truncated = true)
    }
    return checkShape(mode, answer.text)
}

/** Совпал ли текст с форматом, который просили. */
fun checkShape(mode: FormatMode, text: String): ShapeCheck = when (mode) {
    FormatMode.FREE -> ShapeCheck(null, "формат не задан — проверять нечего")
    FormatMode.STRICT_TEXT -> checkBlocks(text)
    FormatMode.JSON_PROMPT, FormatMode.JSON_SCHEMA -> checkJson(text)
}

/** Разбирает ответ в структуру. null — формат не совпал, показать как структуру нечего. */
fun parseCarAnswer(text: String): CarAnswer? = runCatching {
    val root = lenient.parseToJsonElement(text).jsonObject
    val answerable = root.getValue("answerable").jsonPrimitive.booleanOrNull ?: error("answerable")
    val parts = root["components"]?.jsonObject?.let { components ->
        CarComponent.entries.map { component ->
            val fields = components.getValue(component.key).jsonObject
            CarPart(component, fields.string("summary"), fields.string("details"))
        }
    }
    CarAnswer(
        answerable = answerable,
        message = root.string("message"),
        car = root.string("car"),
        components = parts.orEmpty(),
    )
}.getOrNull()

private val lenient = Json { ignoreUnknownKeys = true }

private val TOP_LEVEL_REQUIRED = setOf("answerable", "message", "car")

private fun JsonObject.string(key: String): String =
    getValue(key).jsonPrimitive.contentOrNull ?: error("$key не строка")

private fun checkBlocks(text: String): ShapeCheck {
    val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
    if (lines.isEmpty()) return ShapeCheck(false, "пустой ответ")

    // Отказ — тоже валидный формат: одна строка «ОТКАЗ: ...» и ничего больше.
    if (lines.size == 1 && lines.single().startsWith(REFUSAL_PREFIX)) {
        return ShapeCheck(true, "отказ по теме, одной строкой")
    }

    val stray = lines.count { line -> BLOCK_PREFIXES.none(line::startsWith) }
    if (stray > 0) return ShapeCheck(false, "строк вне блоков: $stray")

    val counts = BLOCK_PREFIXES.map { prefix -> lines.count { it.startsWith(prefix) } }
    if (counts.toSet().size != 1 || counts.first() == 0) {
        return ShapeCheck(false, "блоки неполные: ${counts.joinToString("/")}")
    }
    val expected = CarComponent.entries.size
    if (counts.first() != expected) {
        return ShapeCheck(false, "узлов ${counts.first()} вместо $expected")
    }
    return ShapeCheck(true, "$expected полных блока")
}

private fun checkJson(text: String): ShapeCheck {
    val root = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return ShapeCheck(false, "не разобрался как JSON")

    val extra = root.keys - TOP_LEVEL_REQUIRED - "components"
    if (extra.isNotEmpty()) return ShapeCheck(false, "лишние поля: ${extra.joinToString()}")
    val missing = TOP_LEVEL_REQUIRED - root.keys
    if (missing.isNotEmpty()) return ShapeCheck(false, "нет полей: ${missing.joinToString()}")

    val answerable = root.getValue("answerable").jsonPrimitive.booleanOrNull
        ?: return ShapeCheck(false, "answerable не булево")

    val components = root["components"]?.jsonObject
    if (!answerable) {
        return if (components == null) {
            ShapeCheck(true, "отказ по теме, узлов нет")
        } else {
            ShapeCheck(false, "отказ, но узлы всё равно заполнены")
        }
    }

    if (components == null) return ShapeCheck(false, "нет узлов при answerable=true")
    val expected = CarComponent.entries.map { it.key }.toSet()
    if (components.keys != expected) {
        return ShapeCheck(false, "набор узлов: ${components.keys.joinToString()}")
    }
    val broken = components.values.count { value ->
        runCatching { value.jsonObject.keys }.getOrNull() != setOf("summary", "details")
    }
    if (broken > 0) return ShapeCheck(false, "узлов с другим набором полей: $broken")

    return ShapeCheck(true, "${expected.size} узлов, поля на месте")
}
