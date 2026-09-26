package com.example.smartroomdashboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartroomdashboard.data.local.SettingsStore
import com.example.smartroomdashboard.data.remote.DiscoveredInstance
import com.example.smartroomdashboard.data.remote.HaDiscovery
import com.example.smartroomdashboard.data.remote.TodoEntity
import com.example.smartroomdashboard.data.remote.TodoRepository
import com.example.smartroomdashboard.data.security.SecureStorage
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.Todo
import com.example.smartroomdashboard.domain.choosePrimaryTodoEntity
import com.example.smartroomdashboard.domain.isNabuCasaHost
import com.example.smartroomdashboard.domain.normalizedBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TodoUiState(
    val todos: List<Todo> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val todoLists: List<TodoEntity> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    /** Instances seen on the local network during setup. */
    val discovered: List<DiscoveredInstance> = emptyList(),
)

class TodoViewModel(
    private val repository: TodoRepository,
    private val settingsStore: SettingsStore,
    private val secureStorage: SecureStorage,
    /**
     * Optional so the JVM tests can construct this without an Android `Context`;
     * `MainActivity` supplies the real one. Without it, discovery returns nothing
     * and the manual field remains the fallback.
     */
    private val discovery: HaDiscovery? = null,
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
        repository.discoverTodoEntities()
            .onSuccess { entities ->
                mutableState.value = mutableState.value.copy(todoLists = entities)
            }
    }

    /**
     * Persists a completed one-tap setup.
     *
     * The token is written to the Keystore-backed store *before* the URL is
     * published to [SettingsStore], because the settings flow is what the UI
     * observes to decide it is configured; a half-applied state would send the
     * app into `refresh()` with a token it cannot see yet.
     */
    fun completeOnboarding(
        baseUrl: String,
        token: String,
        todoEntityIds: List<Pair<String, String>>,
        current: AppSettings,
    ) {
        viewModelScope.launch {
            secureStorage.write(TOKEN_KEY, token.trim())
            val entityIds = todoEntityIds.map { it.first }.filter { it.isNotBlank() }
            val primary = choosePrimaryTodoEntity(todoEntityIds, current.todoEntityId)
            settingsStore.save(
                current.copy(
                    homeAssistantUrl = baseUrl.trim().normalizedBaseUrl(),
                    todoEntityId = primary.ifBlank { current.todoEntityId },
                    boardEntityIds = entityIds.filter { it != primary },
                    setupCompleted = true,
                ),
            )
            val where = todoEntityIds.size
            mutableState.value = mutableState.value.copy(
                message = if (where == 0) {
                    "Connected. No todo.* lists found yet - add a Local Todo list in Home Assistant."
                } else {
                    "Connected with $where todo list(s)."
                },
            )
            refresh()
            // Now that a token exists, find out whether this account has a cloud
            // address and move to it, so the tablet keeps working off-network.
            upgradeToCloudIfAvailable()
        }
    }

    fun discoverTodoEntities() = launchBusy {
        repository.discoverTodoEntities()
            .onSuccess { entities ->
                mutableState.value = mutableState.value.copy(
                    todoLists = entities,
                    message = if (entities.isEmpty()) {
                        "No todo entities found. Add a Local Todo list in Home Assistant first."
                    } else {
                        "Todo lists: " + entities.joinToString { "${it.entityId} (${it.name})" }
                    },
                )
            }
            .onFailure { showError(it) }
    }

    fun add(
        title: String,
        description: String = "",
        listEntityId: String = "",
        dueDate: String? = null,
    ) = launchBusy {
        repository.add(title, description, listEntityId, dueDate).onFailure { showError(it) }
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

    fun move(todo: Todo, destEntityId: String) = launchBusy {
        repository.move(todo, destEntityId).onFailure { showError(it) }
    }

    fun saveSettings(
        url: String,
        entityId: String,
        token: String,
        dashboardPath: String,
        boardEntityIds: List<String>,
    ) {
        viewModelScope.launch {
            val previous = settingsStore.settings.value
            // Token first, for the same reason as completeOnboarding: the
            // settings flow drives the UI and the token is read independently.
            if (token.isNotBlank()) secureStorage.write(TOKEN_KEY, token.trim())
            settingsStore.save(
                previous.copy(
                    homeAssistantUrl = url.trim(),
                    todoEntityId = entityId.trim(),
                    dashboardPath = dashboardPath.trim().trim('/').ifBlank { "lovelace/0" },
                    boardEntityIds = boardEntityIds,
                    // Saving a URL and a token by hand is a valid manual setup.
                    setupCompleted = previous.setupCompleted ||
                        (url.isNotBlank() && secureStorage.read(TOKEN_KEY)?.isNotBlank() == true),
                ),
            )
            mutableState.value = mutableState.value.copy(message = "Settings saved")
            refresh()
        }
    }

    /** Persists the presentation preferences, leaving connection fields alone. */
    fun savePreferences(textScale: Float, keepScreenOn: Boolean) {
        viewModelScope.launch {
            settingsStore.save(
                settingsStore.settings.value.copy(
                    textScale = textScale,
                    keepScreenOn = keepScreenOn,
                ),
            )
        }
    }

    /** Reopens the one-tap setup, keeping the current connection as the default. */
    fun beginSetup() {
        mutableState.value = mutableState.value.copy(message = null)
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

    /**
     * Looks for Home Assistant instances on the local network.
     *
     * The point is to remove the URL field from the critical path: the user taps a
     * discovered instance instead of reading an address off another screen.
     */
    fun discoverInstances() = launchBusy {
        discovery?.discover().orEmpty()
            .sortedBy { it.label }
            .let { mutableState.value = mutableState.value.copy(discovered = it) }
    }

    /**
     * Asks a connected instance for its own Nabu Casa address and, when remote
     * access is enabled, switches the app over to it.
     *
     * This is what removes the need to ever read or type the cloud URL. It runs
     * *after* setup because it needs a working token, which is why the first
     * connection still has to be a local one.
     */
    fun upgradeToCloudIfAvailable() {
        viewModelScope.launch {
            val current = settingsStore.settings.value
            val token = secureStorage.read(TOKEN_KEY).orEmpty()
            if (current.homeAssistantUrl.isBlank() || token.isBlank()) return@launch
            // Already on the cloud address; nothing to upgrade to.
            if (current.homeAssistantUrl.normalizedBaseUrl().isNabuCasaHost()) return@launch

            repository.cloudInfo(current.homeAssistantUrl, token)
                .onSuccess { info ->
                    val remote = info.remoteUrl
                    if (remote == null) {
                        mutableState.value = mutableState.value.copy(
                            message = "Connected locally. Remote access is not enabled on " +
                                "your Home Assistant Cloud account, so this tablet will " +
                                "only work on this network.",
                        )
                        return@onSuccess
                    }
                    settingsStore.save(
                        current.copy(
                            homeAssistantUrl = remote,
                            cloudFallbackUrl = current.homeAssistantUrl,
                        ),
                    )
                    val note = if (info.remoteConnected) {
                        "Connected, and switched to your Home Assistant Cloud address."
                    } else {
                        "Connected. Using your cloud address " + remote +
                            ", but your instance is not connected to the cloud right now."
                    }
                    mutableState.value = mutableState.value.copy(message = note)
                    refresh()
                }
                .onFailure {
                    // Not fatal: the local address still works, so stay on it and
                    // say why rather than leaving the user on a blank screen.
                    mutableState.value = mutableState.value.copy(
                        message = "Connected on this network. Could not check Home Assistant " +
                            "Cloud (${it.message}).",
                    )
                }
        }
    }

    /** Goes back to the local address after a cloud upgrade. */
    fun revertToLocal() {
        viewModelScope.launch {
            val current = settingsStore.settings.value
            val local = current.cloudFallbackUrl
            if (local.isBlank()) return@launch
            settingsStore.save(current.copy(homeAssistantUrl = local, cloudFallbackUrl = ""))
            mutableState.value = mutableState.value.copy(message = "Switched back to $local")
            refresh()
        }
    }

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
