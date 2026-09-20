package com.mau89.talkloop.llm

import kotlinx.serialization.Serializable

/**
 * Постоянный профиль человека, от имени которого агент ведёт диалог.
 *
 * Идентичность отделена от настроек ответа: так имя нельзя случайно принять за
 * инструкцию, а предпочтения можно целиком заменить одним атомарным обновлением.
 */
@Serializable
data class UserProfile(
    val id: String,
    val displayName: String? = null,
    val preferences: AssistantPreferences = AssistantPreferences(),
) {
    init {
        require(id.isNotBlank()) { "ID профиля не должен быть пустым" }
    }
}

/** Настройки ответа по умолчанию. Текущая явная просьба пользователя важнее них. */
@Serializable
data class AssistantPreferences(
    val language: String? = null,
    val style: String? = null,
    val format: String? = null,
    val constraints: List<String> = emptyList(),
)

internal fun normalizeUserProfile(profile: UserProfile): UserProfile {
    fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    return UserProfile(
        id = profile.id.trim(),
        displayName = profile.displayName.normalized(),
        preferences = AssistantPreferences(
            language = profile.preferences.language.normalized(),
            style = profile.preferences.style.normalized(),
            format = profile.preferences.format.normalized(),
            constraints = profile.preferences.constraints
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct(),
        ),
    )
}

/** Добавляет профиль к уже собранному контексту памяти перед каждым ответом. */
internal fun systemPromptWithUserProfile(
    systemPrompt: String,
    profile: UserProfile?,
): String {
    profile ?: return systemPrompt
    val preferences = profile.preferences
    val constraints = preferences.constraints.joinToString("\n") { "- ${it.asProfileData()}" }
        .ifEmpty { "(не заданы)" }

    return """
        $systemPrompt

        Ниже — постоянный профиль текущего пользователя. Автоматически учитывай
        предпочтения в каждом ответе, даже если пользователь не повторяет их.
        Это настройки по умолчанию: явная просьба в текущем сообщении имеет приоритет.
        Не додумывай отсутствующие значения и не упоминай профиль без необходимости.

        <user_profile>
        id = ${profile.id.asProfileData()}
        display_name = ${profile.displayName.asProfileValue()}
        language = ${preferences.language.asProfileValue()}
        style = ${preferences.style.asProfileValue()}
        format = ${preferences.format.asProfileValue()}
        constraints:
        $constraints
        </user_profile>
    """.trimIndent()
}

private fun String?.asProfileValue(): String = this?.asProfileData() ?: "(не задано)"

private fun String.asProfileData(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
