package com.example.smartroomdashboard.domain

import java.util.UUID

data class Todo(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val completed: Boolean = false,
    val dueDate: String? = null,
    val listEntityId: String = "",
)

data class AppSettings(
    val homeAssistantUrl: String = "",
    val todoEntityId: String = "todo.smart_room",
    val dashboardPath: String = "lovelace/0",
    val boardEntityIds: List<String> = emptyList(),
    /**
     * The local address to fall back to when [homeAssistantUrl] is a cloud host.
     *
     * Set automatically by the post-setup cloud upgrade so the user can get back on
     * the LAN address without retyping it. Empty when not applicable.
     */
    val cloudFallbackUrl: String = "",
    // --- Presentation and setup preferences, added after the connection fields.
    // New fields must be appended with defaults: the unit tests construct
    // AppSettings positionally.
    /** Multiplier applied to every typography slot, for e-ink legibility. */
    val textScale: Float = DEFAULT_TEXT_SCALE,
    /** Hold FLAG_KEEP_SCREEN_ON while the app is in the foreground. */
    val keepScreenOn: Boolean = true,
    /** Set once onboarding has stored a working URL *and* token. */
    val setupCompleted: Boolean = false,
) {
    val isConfigured: Boolean
        get() = setupCompleted &&
            homeAssistantUrl.isNotBlank() &&
            todoEntityId.isNotBlank()
}

/** Smallest and largest text multipliers offered in Settings. */
const val MIN_TEXT_SCALE = 0.85f
const val MAX_TEXT_SCALE = 1.75f
const val DEFAULT_TEXT_SCALE = 1.0f

/** The steps offered by the text-size control. */
val TEXT_SCALE_STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f)

fun AppSettings.clampedTextScale(): Float =
    textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)

fun AppSettings.syncedEntityIds(): List<String> =
    (listOf(todoEntityId) + boardEntityIds)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

fun AppSettings.deckEntityIds(): List<String> {
    val decks = boardEntityIds.map { it.trim() }.filter { it.isNotBlank() }
    return decks.ifEmpty { listOfNotNull(todoEntityId.trim().takeIf { it.isNotBlank() }) }
}

fun parseBoardEntityIds(raw: String): List<String> =
    raw.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

fun String.normalizedBaseUrl(): String {
    val trimmed = trim().removeSuffix("/")
    val withoutApi = trimmed.removeSuffix("/api")
    return if (withoutApi.isEmpty()) withoutApi else "$withoutApi/"
}
