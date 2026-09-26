package com.example.smartroomdashboard.data

import com.example.smartroomdashboard.data.local.SettingsStore
import com.example.smartroomdashboard.data.local.PendingTodoOperation
import com.example.smartroomdashboard.data.local.TodoLocalStore
import com.example.smartroomdashboard.data.remote.HomeAssistantApi
import com.example.smartroomdashboard.data.remote.HomeAssistantTodoRepository
import com.example.smartroomdashboard.data.remote.PairingServer
import com.example.smartroomdashboard.data.remote.RemoteTodo
import com.example.smartroomdashboard.data.remote.HomeAssistantState
import com.example.smartroomdashboard.data.remote.defaultListTodoItems
import com.example.smartroomdashboard.data.remote.homeAssistantWebSocketUrl
import com.example.smartroomdashboard.data.remote.pairingRequestBody
import com.example.smartroomdashboard.data.remote.parseCloudInfo
import com.example.smartroomdashboard.data.remote.parseTodoItemListResult
import com.example.smartroomdashboard.data.remote.toDomain
import com.example.smartroomdashboard.data.security.SecureStorage
import com.example.smartroomdashboard.domain.AppSettings
import com.example.smartroomdashboard.domain.PairingPayload
import com.example.smartroomdashboard.domain.Todo
import com.example.smartroomdashboard.domain.isNabuCasaHost
import com.example.smartroomdashboard.domain.parsePairingPayload
import com.google.gson.JsonParser
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
            mutateItem = { _, _, _, operation, item, rename, _, _, _ ->
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
            mutateItem = { _, _, _, _, _, _, _, _, _ ->
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
            mutateItem = { _, _, _, _, item, rename, _, _, _ ->
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

    @Test
    fun `cloud status parses the nabu casa remote domain`() {
        val info = parseCloudInfo(
            JsonParser.parseString(
                """
                {"id":1,"type":"result","success":true,"result":{
                  "logged_in":true,
                  "remote_domain":"a1b2c3d4.ui.nabu.casa",
                  "remote_connected":true
                }}
                """.trimIndent(),
            ).asJsonObject,
        )
        assertEquals(true, info.loggedIn)
        assertEquals("a1b2c3d4.ui.nabu.casa", info.remoteDomain)
        assertEquals("https://a1b2c3d4.ui.nabu.casa", info.remoteUrl)
    }

    @Test
    fun `cloud status without remote access yields no url`() {
        val info = parseCloudInfo(
            JsonParser.parseString(
                """{"id":1,"type":"result","success":true,"result":{
                     "logged_in":true,"remote_domain":"","remote_connected":false}}""",
            ).asJsonObject,
        )
        assertEquals(null, info.remoteUrl)
    }

    @Test
    fun `a discovered local url is recognized as not a cloud host`() {
        // Guards the upgrade path: only a nabu.casa host short-circuits it, so a
        // local address always gets the chance to be upgraded.
        assertEquals(false, "http://192.168.1.50:8123/".isNabuCasaHost())
    }

    private fun pairingJson(code: String, url: String, entities: String = "[]") =
        """{"code":"$code","baseUrl":"$url","locationName":"Home","todoEntities":$entities}"""

    @Test
    fun `pairing push is accepted with the matching code`() {
        val payload = parsePairingPayload(
            pairingJson(
                "123456",
                "https://a1b2c3d4.ui.nabu.casa",
                """[["todo.shop","Shopping"],["todo.work","Work"]]""",
            ),
            expectedCode = "123456",
        )
        assertEquals("https://a1b2c3d4.ui.nabu.casa", payload?.baseUrl)
        assertEquals(2, payload?.todoEntities?.size)
        assertEquals(true, payload?.isNabuCasa)
    }

    @Test
    fun `pairing push with the wrong code is rejected`() {
        assertEquals(
            null,
            parsePairingPayload(pairingJson("999999", "https://x.ui.nabu.casa"), "123456"),
        )
    }

    @Test
    fun `pairing push with an implausible url is rejected`() {
        // Otherwise a hostile push could steer the sign-in WebView, and with it
        // the Home Assistant password, at an address of the sender's choosing.
        assertEquals(null, parsePairingPayload(pairingJson("123456", "not-a-url"), "123456"))
        assertEquals(null, parsePairingPayload(pairingJson("123456", "file:///etc"), "123456"))
    }

    @Test
    fun `pairing push drops entities that are not todo lists`() {
        val payload = parsePairingPayload(
            pairingJson(
                "123456",
                "http://ha.local:8123/",
                """[["todo.shop","Shopping"],["light.kitchen","Kitchen"],["","x"]]""",
            ),
            "123456",
        )
        assertEquals(listOf("todo.shop" to "Shopping"), payload?.todoEntities)
    }

    @Test
    fun `pairing body round trips through the parser`() {
        val original = PairingPayload(
            baseUrl = "https://a1b2c3d4.ui.nabu.casa",
            todoEntities = listOf("todo.shop" to "Shopping", "todo.work" to "Work"),
            locationName = "Home",
        )
        val body = pairingRequestBody("424242", original)
        val parsed = parsePairingPayload(body, "424242")
        assertEquals(original.baseUrl, parsed?.baseUrl)
        assertEquals(original.todoEntities, parsed?.todoEntities)
        assertEquals(original.locationName, parsed?.locationName)
    }

    @Test
    fun `pairing code is six digits and not trivially guessable`() {
        repeat(50) {
            val code = PairingServer.generateCode()
            assertEquals(6, code.length)
            assertEquals(true, code.all { it.isDigit() })
            // Reject repeated-digit runs like 111111.
            assertEquals(true, code.toSet().size >= 5)
        }
    }
}
