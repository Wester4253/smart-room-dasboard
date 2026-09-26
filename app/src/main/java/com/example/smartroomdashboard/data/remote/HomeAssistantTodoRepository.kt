package com.example.smartroomdashboard.data.remote

import com.example.smartroomdashboard.data.local.PendingOperationType
import com.example.smartroomdashboard.data.local.PendingTodoOperation
import com.example.smartroomdashboard.data.local.SettingsStore
import com.example.smartroomdashboard.data.local.TodoLocalStore
import com.example.smartroomdashboard.data.security.SecureStorage
import com.example.smartroomdashboard.domain.Todo
import com.example.smartroomdashboard.domain.normalizedBaseUrl
import com.example.smartroomdashboard.domain.syncedEntityIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

interface TodoRepository {
    fun observeTodos(): Flow<List<Todo>>
    suspend fun refresh(): Result<List<Todo>>
    suspend fun discoverTodoEntities(): Result<List<TodoEntity>>
    suspend fun add(
        title: String,
        description: String = "",
        listEntityId: String = "",
        dueDate: String? = null,
    ): Result<Todo>
    suspend fun update(todo: Todo): Result<Todo>
    suspend fun delete(todo: Todo): Result<Unit>
    suspend fun setCompleted(todo: Todo, completed: Boolean): Result<Todo>
    suspend fun move(todo: Todo, destEntityId: String): Result<Todo>
    suspend fun testConnection(baseUrl: String, token: String): Result<String>

    /**
     * Ask the instance for its own Home Assistant Cloud remote address.
     *
     * This is how the app learns a Nabu Casa URL without the user reading it off a
     * settings page: once connected over the local network we can ask, and
     * `cloud/status` reports `remote_domain` (the `<id>.ui.nabu.casa` host).
     */
    suspend fun cloudInfo(baseUrl: String, token: String): Result<CloudInfo>
}

data class TodoEntity(
    val entityId: String,
    val name: String,
    val state: String,
)

typealias TodoItemLister = suspend (
    baseUrl: String,
    token: String,
    entityId: String,
    api: HomeAssistantApi,
) -> List<RemoteTodo>

typealias TodoItemMutator = suspend (
    connection: String,
    token: String,
    entityId: String,
    operation: String,
    item: String,
    rename: String?,
    status: String?,
    description: String?,
    due: String?,
) -> Unit

private data class HaConnection(
    val api: HomeAssistantApi,
    val baseUrl: String,
    val token: String,
    val entityId: String,
)

class HomeAssistantTodoRepository(
    private val local: TodoLocalStore,
    private val settings: SettingsStore,
    private val secureStorage: SecureStorage,
    private val apiFactory: (String, String) -> HomeAssistantApi = ::createDefaultApi,
    private val listItems: TodoItemLister = ::defaultListTodoItems,
    private val mutateItem: TodoItemMutator = ::defaultMutateTodoItem,
) : TodoRepository {
    private val cachedTodos = MutableStateFlow<List<Todo>>(emptyList())

    override fun observeTodos(): Flow<List<Todo>> = flow {
        cachedTodos.value = local.read()
        emitAll(cachedTodos)
    }

    override suspend fun refresh(): Result<List<Todo>> = runCatching {
        val connection = connection()
        syncPending(connection)
        val config = settings.settings.first()
        val all = config.syncedEntityIds().flatMap { entityId ->
            listItems(connection.baseUrl, connection.token, entityId, connection.api)
                .map { it.toDomain(entityId) }
        }
        local.write(all)
        cachedTodos.value = all
        all
    }

    override suspend fun discoverTodoEntities(): Result<List<TodoEntity>> = runCatching {
        val (api, _) = configuredApi()
        val response = api.getStates()
        check(response.isSuccessful) {
            val detail = response.errorBody()?.string()?.take(240).orEmpty()
            "Home Assistant states endpoint returned HTTP ${response.code()}." +
                if (detail.isBlank()) "" else " $detail"
        }
        response.body().orEmpty()
            .filter { it.entity_id.startsWith("todo.") }
            .map {
                TodoEntity(
                    entityId = it.entity_id,
                    name = (it.attributes["friendly_name"] as? String).orEmpty()
                        .ifBlank { it.entity_id },
                    state = it.state,
                )
            }
    }

    override suspend fun add(
        title: String,
        description: String,
        listEntityId: String,
        dueDate: String?,
    ): Result<Todo> {
        if (title.isBlank()) return Result.failure(IllegalArgumentException("A todo needs a title"))
        val entityId = listEntityId.ifBlank { settings.settings.first().todoEntityId }
        val newTodo = Todo(
            title = title.trim(),
            description = description.trim(),
            dueDate = dueDate?.trim()?.ifBlank { null },
            listEntityId = entityId,
        )
        return runCatching {
            val updated = local.read() + newTodo
            local.write(updated)
            cachedTodos.value = updated
            enqueueAndSync(PendingTodoOperation(PendingOperationType.ADD, newTodo))
            newTodo
        }
    }

    override suspend fun update(todo: Todo): Result<Todo> {
        if (todo.title.isBlank()) return Result.failure(IllegalArgumentException("A todo needs a title"))
        return runCatching {
            val normalized = todo.copy(
                title = todo.title.trim(),
                description = todo.description.trim(),
                dueDate = todo.dueDate?.trim()?.ifBlank { null },
            )
            val oldTodo = local.read().firstOrNull { it.id == todo.id }
            val updated = local.read().map { if (it.id == todo.id) normalized else it }
            local.write(updated)
            cachedTodos.value = updated
            enqueueAndSync(
                PendingTodoOperation(
                    type = PendingOperationType.UPDATE,
                    todo = normalized,
                    previousTitle = oldTodo?.title?.takeIf { it != normalized.title },
                    sourceEntityId = oldTodo?.listEntityId,
                ),
            )
            normalized
        }
    }

    override suspend fun delete(todo: Todo): Result<Unit> = runCatching {
        val remaining = local.read().filterNot { it.id == todo.id }
        local.write(remaining)
        cachedTodos.value = remaining
        enqueueAndSync(PendingTodoOperation(PendingOperationType.DELETE, todo))
    }

    override suspend fun setCompleted(todo: Todo, completed: Boolean): Result<Todo> {
        return update(todo.copy(completed = completed))
    }

    override suspend fun move(todo: Todo, destEntityId: String): Result<Todo> {
        val dest = destEntityId.trim()
        require(dest.isNotBlank()) { "Choose a destination list" }
        if (todo.listEntityId == dest) return Result.success(todo)
        return runCatching {
            val moved = todo.copy(listEntityId = dest)
            val updated = local.read().map { if (it.id == todo.id) moved else it }
            local.write(updated)
            cachedTodos.value = updated
            enqueueAndSync(
                PendingTodoOperation(
                    type = PendingOperationType.MOVE,
                    todo = moved,
                    previousTitle = todo.title,
                    sourceEntityId = todo.listEntityId,
                ),
            )
            moved
        }
    }

    private suspend fun enqueueAndSync(operation: PendingTodoOperation) {
        local.writePendingOperations(local.readPendingOperations() + operation)
        syncPending(connection())
    }

    private suspend fun syncPending(connection: HaConnection) {
        val pending = local.readPendingOperations()
        pending.forEachIndexed { index, operation ->
            runCatching {
                apply(operation, connection)
            }.onFailure {
                local.writePendingOperations(pending.drop(index))
                throw it
            }
        }
        if (pending.isNotEmpty()) local.writePendingOperations(emptyList())
    }

    private suspend fun resolveRemoteItem(
        operation: PendingTodoOperation,
        connection: HaConnection,
        entityId: String,
    ): String? {
        val remoteItems = listItems(
            connection.baseUrl,
            connection.token,
            entityId,
            connection.api,
        )
        return remoteItems.firstOrNull { remote ->
            remote.uid == operation.todo.id ||
                remote.summary == operation.previousTitle ||
                remote.summary == operation.todo.title
        }?.let { it.uid ?: it.summary }
    }

    private suspend fun apply(
        operation: PendingTodoOperation,
        connection: HaConnection,
    ) {
        val targetEntity = operation.todo.listEntityId.ifBlank { connection.entityId }
        when (operation.type) {
            PendingOperationType.ADD -> mutateItem(
                connection.baseUrl,
                connection.token,
                targetEntity,
                "add",
                operation.todo.title,
                null,
                null,
                operation.todo.description.takeIf { it.isNotBlank() },
                operation.todo.dueDate,
            )
            PendingOperationType.UPDATE -> {
                val item = checkNotNull(
                    resolveRemoteItem(operation, connection, targetEntity),
                ) {
                    "Home Assistant does not contain the todo being updated. " +
                        "Refresh the list before editing it; the update was kept queued locally."
                }
                mutateItem(
                    connection.baseUrl,
                    connection.token,
                    targetEntity,
                    "update",
                    item,
                    operation.todo.title,
                    if (operation.todo.completed) "completed" else "needs_action",
                    operation.todo.description,
                    operation.todo.dueDate.orEmpty(),
                )
            }
            PendingOperationType.DELETE -> {
                val item = resolveRemoteItem(operation, connection, targetEntity) ?: return
                mutateItem(
                    connection.baseUrl,
                    connection.token,
                    targetEntity,
                    "remove",
                    item,
                    null,
                    null,
                    null,
                    null,
                )
            }
            PendingOperationType.MOVE -> {
                val source = operation.sourceEntityId?.ifBlank { null } ?: connection.entityId
                mutateItem(
                    connection.baseUrl,
                    connection.token,
                    targetEntity,
                    "add",
                    operation.todo.title,
                    null,
                    null,
                    operation.todo.description.takeIf { it.isNotBlank() },
                    operation.todo.dueDate,
                )
                val sourceItem = resolveRemoteItem(
                    operation.copy(todo = operation.todo.copy(listEntityId = source)),
                    connection,
                    source,
                )
                if (sourceItem != null) {
                    mutateItem(
                        connection.baseUrl,
                        connection.token,
                        source,
                        "remove",
                        sourceItem,
                        null,
                        null,
                        null,
                        null,
                    )
                }
            }
        }
    }

    private suspend fun configuredApi(): Pair<HomeAssistantApi, String> {
        val connection = connection()
        return connection.api to connection.entityId
    }

    private suspend fun connection(): HaConnection {
        val config = settings.settings.first()
        require(config.homeAssistantUrl.isNotBlank()) { "Configure the Home Assistant URL first" }
        val token = secureStorage.read(TOKEN_KEY).orEmpty()
        require(token.isNotBlank()) { "Configure the Home Assistant token first" }
        val baseUrl = config.homeAssistantUrl.normalizedBaseUrl()
        return HaConnection(
            api = apiFactory(baseUrl, token),
            baseUrl = baseUrl,
            token = token,
            entityId = config.todoEntityId,
        )
    }

    override suspend fun cloudInfo(baseUrl: String, token: String): Result<CloudInfo> =
        HomeAssistantWebSocket().request(baseUrl, token, CloudCommands.STATUS, ::parseCloudInfo)

    override suspend fun testConnection(baseUrl: String, token: String): Result<String> = runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(15))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            .retryOnConnectionFailure(true)
            .build()

        require(baseUrl.isNotBlank()) { "Enter the Home Assistant URL first" }
        require(token.isNotBlank()) { "Enter or save a Home Assistant token first" }
        val url = baseUrl.normalizedBaseUrl() + "api/"
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { resp ->
            val sb = StringBuilder()
            sb.append("HTTP ${resp.code()} ${resp.message()}")
            resp.handshake()?.let { h ->
                sb.append("; TLS ${h.tlsVersion()} ${h.cipherSuite()}")
            }
            val bodySnippet = resp.body()?.string()?.take(800).orEmpty()
            if (bodySnippet.isNotBlank()) sb.append("; Body: ${bodySnippet}")
            check(resp.isSuccessful) { sb.toString() }
            sb.toString()
        }
    }

    private companion object {
        const val TOKEN_KEY = "home_assistant_token"
    }
}

private suspend fun defaultMutateTodoItem(
    connection: String,
    token: String,
    entityId: String,
    operation: String,
    item: String,
    rename: String?,
    status: String?,
    description: String?,
    due: String?,
) {
    HomeAssistantWebSocket().mutateTodo(
        baseUrl = connection,
        token = token,
        entityId = entityId,
        operation = operation,
        item = item,
        rename = rename,
        status = status,
        description = description,
        due = due,
    )
}

/**
 * Lists todo items exclusively through the Home Assistant WebSocket command
 * `todo/item/list`. The REST `todo.get_items` service is a confirmed, unfixed
 * Home Assistant core bug that always returns HTTP 500 regardless of payload
 * (see home-assistant/core#113063), so this app never uses it for reading
 * items; falling back to it only reproduced the same 500 error.
 */
suspend fun defaultListTodoItems(
    baseUrl: String,
    token: String,
    entityId: String,
    api: HomeAssistantApi,
): List<RemoteTodo> {
    var lastError: Throwable? = null
    repeat(WEBSOCKET_ATTEMPTS) { attempt ->
        val result = runCatching { HomeAssistantWebSocket().listTodoItems(baseUrl, token, entityId) }
        if (result.isSuccess) return result.getOrThrow()
        lastError = result.exceptionOrNull()
        if (attempt < WEBSOCKET_ATTEMPTS - 1) delay(750)
    }
    val diagnostic = runCatching { probeRestConnectivity(api) }.getOrNull()
    error(
        buildString {
            append("Could not load todo items for $entityId over the Home Assistant WebSocket API")
            append(" after $WEBSOCKET_ATTEMPTS attempts: ${lastError?.message ?: "unknown error"}.")
            if (diagnostic != null) append(' ').append(diagnostic)
        },
    )
}

private const val WEBSOCKET_ATTEMPTS = 3

private suspend fun probeRestConnectivity(api: HomeAssistantApi): String {
    val response = api.getStates()
    return if (response.isSuccessful) {
        "Home Assistant's REST API is reachable, so the WebSocket endpoint itself is blocked or " +
            "unreachable (check that reverse proxies/firewalls allow WebSocket upgrades on /api/websocket)."
    } else {
        "The REST API also returned HTTP ${response.code()}, so check the Home Assistant URL and token first."
    }
}

private fun createDefaultApi(baseUrl: String, token: String): HomeAssistantApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .writeTimeout(java.time.Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .build()
                    try {
                        chain.proceed(req)
                    } catch (e: java.io.IOException) {
                        throw java.io.IOException("Connection error communicating with Home Assistant: ${e.message}", e)
                    }
                }
                .build(),
        )
        .build()
        .create(HomeAssistantApi::class.java)
