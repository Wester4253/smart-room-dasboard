package com.example.smartroomdashboard.data.local

import android.content.SharedPreferences
import com.example.smartroomdashboard.domain.Todo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PendingOperationType {
    ADD,
    UPDATE,
    DELETE,
}

data class PendingTodoOperation(
    val type: PendingOperationType,
    val todo: Todo,
    /**
     * The title Home Assistant knew before an update. Local todos use a UUID
     * until their first refresh, but HA can always resolve the old summary.
     */
    val previousTitle: String? = null,
)

interface TodoLocalStore {
    suspend fun read(): List<Todo>
    suspend fun write(todos: List<Todo>)
    suspend fun readPendingOperations(): List<PendingTodoOperation> = emptyList()
    suspend fun writePendingOperations(operations: List<PendingTodoOperation>) = Unit
}

class SharedPreferencesTodoLocalStore(
    private val preferences: SharedPreferences,
    private val gson: Gson = Gson(),
) : TodoLocalStore {
    private val listType = object : TypeToken<List<Todo>>() {}.type
    private val pendingOperationsType = object : TypeToken<List<PendingTodoOperation>>() {}.type

    override suspend fun read(): List<Todo> = withContext(Dispatchers.IO) {
        preferences.getString(KEY_TODOS, null)?.let { json ->
            runCatching { gson.fromJson<List<Todo>>(json, listType) }.getOrNull()
        }.orEmpty()
    }

    override suspend fun write(todos: List<Todo>) = withContext(Dispatchers.IO) {
        preferences.edit().putString(KEY_TODOS, gson.toJson(todos)).apply()
    }

    override suspend fun readPendingOperations(): List<PendingTodoOperation> = withContext(Dispatchers.IO) {
        preferences.getString(KEY_PENDING_OPERATIONS, null)?.let { json ->
            runCatching { gson.fromJson<List<PendingTodoOperation>>(json, pendingOperationsType) }.getOrNull()
        }.orEmpty()
    }

    override suspend fun writePendingOperations(operations: List<PendingTodoOperation>) =
        withContext(Dispatchers.IO) {
            check(
                preferences.edit()
                    .putString(KEY_PENDING_OPERATIONS, gson.toJson(operations))
                    .commit(),
            ) { "Unable to persist pending todo operations" }
        }

    private companion object {
        const val KEY_TODOS = "cached_todos"
        const val KEY_PENDING_OPERATIONS = "pending_todo_operations"
    }
}
