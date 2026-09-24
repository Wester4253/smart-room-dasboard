package com.example.smartroomdashboard.domain

import java.util.UUID

data class Todo(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val completed: Boolean = false,
    val dueDate: String? = null,
)

data class AppSettings(
    val homeAssistantUrl: String = "",
    val todoEntityId: String = "todo.smart_room",
    val dashboardPath: String = "lovelace/0",
)

fun String.normalizedBaseUrl(): String {
    val trimmed = trim().removeSuffix("/")
    val withoutApi = trimmed.removeSuffix("/api")
    return if (withoutApi.isEmpty()) withoutApi else "$withoutApi/"
}
