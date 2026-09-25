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
)

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
