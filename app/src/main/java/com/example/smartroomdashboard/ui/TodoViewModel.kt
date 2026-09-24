package com.example.smartroomdashboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartroomdashboard.data.local.SettingsStore
import com.example.smartroomdashboard.data.remote.TodoRepository
import com.example.smartroomdashboard.data.security.SecureStorage
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.Todo
import com.example.smartroomdashboard.domain.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TodoUiState(
    val todos: List<Todo> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isBusy: Boolean = false,
    val message: String? = null,
)

class TodoViewModel(
    private val repository: TodoRepository,
    private val settingsStore: SettingsStore,
    private val secureStorage: SecureStorage,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TodoUiState())
    val state: StateFlow<TodoUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                mutableState.value = mutableState.value.copy(settings = settings)
            }
        }
        viewModelScope.launch {
            repository.observeTodos().collect { todos ->
                mutableState.value = mutableState.value.copy(todos = todos)
            }
        }
        refresh()
    }

    fun refresh() = launchBusy {
        repository.refresh().onFailure { showError(it) }
    }

    fun discoverTodoEntities() = launchBusy {
        repository.discoverTodoEntities()
            .onSuccess { entities ->
                mutableState.value = mutableState.value.copy(
                    message = if (entities.isEmpty()) {
                        "No todo entities found. Add a Local Todo list in Home Assistant first."
                    } else {
                        "Todo lists: " + entities.joinToString { "${it.entityId} (${it.name})" }
                    },
                )
            }
            .onFailure { showError(it) }
    }

    fun add(title: String) = launchBusy {
        repository.add(title).onFailure { showError(it) }
    }

    fun update(todo: Todo) = launchBusy {
        repository.update(todo).onFailure { showError(it) }
    }

    fun delete(todo: Todo) = launchBusy {
        repository.delete(todo).onFailure { showError(it) }
    }

    fun toggle(todo: Todo) = launchBusy {
        repository.setCompleted(todo, !todo.completed).onFailure { showError(it) }
    }

    fun saveSettings(url: String, entityId: String, token: String, dashboardPath: String) {
        viewModelScope.launch {
            settingsStore.save(
                AppSettings(
                    homeAssistantUrl = url.trim(),
                    todoEntityId = entityId.trim(),
                    dashboardPath = dashboardPath.trim().trim('/').ifBlank { "lovelace/0" },
                ),
            )
            if (token.isNotBlank()) secureStorage.write(TOKEN_KEY, token.trim())
            mutableState.value = mutableState.value.copy(message = "Settings saved")
            refresh()
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    fun testConnectionWith(url: String, token: String) = launchBusy {
        val normalized = url.trim().normalizedBaseUrl()
        val testToken = token.trim().ifBlank { secureStorage.read(TOKEN_KEY).orEmpty() }
        repository.testConnection(normalized, testToken)
            .onSuccess { mutableState.value = mutableState.value.copy(message = it) }
            .onFailure { showError(it) }
    }

    suspend fun dashboardToken(): String =
        secureStorage.read(TOKEN_KEY).orEmpty()

    private fun launchBusy(action: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isBusy = true, message = null)
            action()
            mutableState.value = mutableState.value.copy(isBusy = false)
        }
    }

    private fun showError(error: Throwable) {
        mutableState.value = mutableState.value.copy(message = error.message ?: "Something went wrong")
    }

    private companion object {
        const val TOKEN_KEY = "home_assistant_token"
    }
}
