package com.example.smartroomdashboard.data

import com.example.smartroomdashboard.data.local.SettingsStore
import com.example.smartroomdashboard.data.local.PendingTodoOperation
import com.example.smartroomdashboard.data.local.TodoLocalStore
import com.example.smartroomdashboard.data.remote.HomeAssistantApi
import com.example.smartroomdashboard.data.remote.HomeAssistantTodoRepository
import com.example.smartroomdashboard.data.remote.RemoteTodo
import com.example.smartroomdashboard.data.remote.HomeAssistantState
import com.example.smartroomdashboard.data.remote.defaultListTodoItems
import com.example.smartroomdashboard.data.remote.homeAssistantWebSocketUrl
import com.example.smartroomdashboard.data.remote.parseTodoItemListResult
import com.example.smartroomdashboard.data.remote.toDomain
import com.example.smartroomdashboard.data.security.SecureStorage
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class HomeAssistantTodoRepositoryTest {
    @Test
    fun `add persists locally before remote synchronization`() = runTest {
        val local = MemoryLocalStore()
        val api = FakeApi()
        val repository = HomeAssistantTodoRepository(
            local = local,
            settings = TestSettingsStore(),
            secureStorage = TestSecureStorage(),
            apiFactory = { _, _ -> api },
            listItems = { _, _, _, _ ->
                listOf(RemoteTodo(summary = "Buy milk", uid = "remote-1"))
            },
            mutateItem = { _, _, _, operation, item, rename, _ ->
                if (operation == "add") api.lastAdded = item
                if (operation == "update") {
                    api.lastUpdatedItem = item
                    api.lastUpdatedRename = rename
                }
            },
        )

        val result = repository.add("Water plants")

        assertEquals("Water plants", result.getOrThrow().title)
        assertEquals(listOf("Water plants"), local.read().map { it.title })
        assertEquals("Water plants", api.lastAdded)
        assertEquals("Water plants", repository.observeTodos().first().single().title)
    }

    @Test
    fun `add remains queued until a later refresh can synchronize`() = runTest {
        val local = MemoryLocalStore()
        val api = FakeApi().apply { offline = true }
        val repository = HomeAssistantTodoRepository(
            local = local,
            settings = TestSettingsStore(),
            secureStorage = TestSecureStorage(),
            apiFactory = { _, _ -> api },
            listItems = { _, _, _, _ ->
                listOf(RemoteTodo(summary = "Buy milk", uid = "remote-1"))
            },
            mutateItem = { _, _, _, _, _, _, _ ->
                if (api.offline) throw IOException("offline")
            },
        )

        val addResult = repository.add("Water plants")

        assertEquals(true, addResult.isFailure)
        assertEquals(1, local.readPendingOperations().size)
        api.offline = false
        repository.refresh().getOrThrow()
        assertEquals(emptyList<PendingTodoOperation>(), local.readPendingOperations())
    }

    @Test
    fun `update uses the previous summary instead of the local UUID`() = runTest {
        val local = MemoryLocalStore()
        val api = FakeApi()
        val repository = HomeAssistantTodoRepository(
            local = local,
            settings = TestSettingsStore(),
            secureStorage = TestSecureStorage(),
            apiFactory = { _, _ -> api },
            listItems = { _, _, _, _ ->
                listOf(RemoteTodo(summary = "Buy milk", uid = "remote-1"))
            },
            mutateItem = { _, _, _, _, item, rename, _ ->
                api.lastUpdatedItem = item
                api.lastUpdatedRename = rename
            },
        )
        val original = Todo(title = "Buy milk")
        local.write(listOf(original))

        repository.update(original.copy(title = "Buy oat milk")).getOrThrow()

        assertEquals("remote-1", api.lastUpdatedItem)
        assertEquals("Buy oat milk", api.lastUpdatedRename)
    }

    @Test
    fun `refresh never falls back to the broken REST get_items endpoint`() = runTest {
        val local = MemoryLocalStore()
        val api = FakeApi()
        var restCalls = 0
        val repository = HomeAssistantTodoRepository(
            local = local,
            settings = TestSettingsStore(),
            secureStorage = TestSecureStorage(),
            apiFactory = { _, _ -> api },
            listItems = { _, _, _, _ ->
                restCalls += 1
                error("simulated WebSocket outage")
            },
        )

        val result = repository.refresh()

        assertEquals(true, result.isFailure)
        assertEquals(1, restCalls)
    }

    @Test
    fun `defaultListTodoItems retries the socket but never calls todo get_items`() = runTest {
        val api = CountingRestApi()

        val result = runCatching {
            defaultListTodoItems("http://ha/", "token", "todo.room", api)
        }

        assertEquals(true, result.isFailure)
        assertEquals(1, api.getStatesCalls)
    }

    @Test
    fun `websocket list command parses items and builds the socket url`() {
        assertEquals(
            "ws://homeassistant.local:8123/api/websocket",
            homeAssistantWebSocketUrl("http://homeassistant.local:8123"),
        )
        assertEquals(
            "wss://ha.example/api/websocket",
            homeAssistantWebSocketUrl("https://ha.example/"),
        )
        val items = parseTodoItemListResult(
            """
            {"id":1,"type":"result","success":true,"result":{"items":[
              {"summary":"Milk","uid":"1","status":"needs_action"},
              {"summary":"Eggs","uid":"2","status":"completed"}
            ]}}
            """.trimIndent(),
        )
        assertEquals(listOf("Milk", "Eggs"), items.map { it.summary })
        assertEquals(listOf(false, true), items.map { it.toDomain().completed })
    }

    private class MemoryLocalStore : TodoLocalStore {
        var todos = emptyList<Todo>()
        var pendingOperations = emptyList<PendingTodoOperation>()
        override suspend fun read() = todos
        override suspend fun write(todos: List<Todo>) {
            this.todos = todos
        }
        override suspend fun readPendingOperations() = pendingOperations
        override suspend fun writePendingOperations(operations: List<PendingTodoOperation>) {
            pendingOperations = operations
        }
    }

    private class TestSettingsStore : SettingsStore {
        override val settings = MutableStateFlow(AppSettings("http://ha/", "todo.room"))
        override suspend fun save(settings: AppSettings) = Unit
    }

    private class TestSecureStorage : SecureStorage {
        override fun read(key: String) = "test-token"
        override fun write(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    private class CountingRestApi : HomeAssistantApi {
        var getStatesCalls = 0
        override suspend fun getStates(): Response<List<HomeAssistantState>> {
            getStatesCalls += 1
            return Response.success(emptyList())
        }
    }

    private class FakeApi : HomeAssistantApi {
        var lastAdded: String? = null
        var lastUpdatedItem: String? = null
        var lastUpdatedRename: String? = null
        var offline = false

        override suspend fun getStates() = Response.success(
            listOf(HomeAssistantState(entity_id = "todo.room", state = "1")),
        )

    }
}
