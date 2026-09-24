package com.example.smartroomdashboard.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun homeAssistantWebSocketUrl(baseUrl: String): String {
    val origin = baseUrl.trim().trimEnd('/')
    val socketOrigin = when {
        origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
        origin.startsWith("http://") -> "ws://" + origin.removePrefix("http://")
        origin.startsWith("wss://") || origin.startsWith("ws://") -> origin
        else -> "ws://$origin"
    }.removeSuffix("/api/websocket")
    return "$socketOrigin/api/websocket"
}

internal fun parseTodoItemListResult(text: String): List<RemoteTodo> {
    val json = JsonParser.parseString(text).asJsonObject
    if (json.get("success")?.asBoolean != true) {
        val message = json.getAsJsonObject("error")?.get("message")?.asString
            ?: "Home Assistant rejected todo/item/list"
        error(message)
    }
    val items = json.getAsJsonObject("result")?.getAsJsonArray("items") ?: return emptyList()
    return items.map { element ->
        val item = element.asJsonObject
        RemoteTodo(
            summary = item.stringOrNull("summary") ?: item.stringOrNull("name").orEmpty(),
            uid = item.stringOrNull("uid"),
            status = item.stringOrNull("status"),
            due = item.stringOrNull("due"),
        )
    }
}

private fun com.google.gson.JsonObject.stringOrNull(name: String): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    return value.asString
}

/**
 * Lists todo items through Home Assistant's WebSocket command `todo/item/list`.
 * The REST `todo.get_items` service returns HTTP 500 on several releases when `status`
 * is sent as a list, so the socket command is the reliable read path.
 */
class HomeAssistantWebSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    suspend fun mutateTodo(
        baseUrl: String,
        token: String,
        entityId: String,
        operation: String,
        item: String,
        rename: String? = null,
        status: String? = null,
    ) {
        withTimeout(20_000) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                lateinit var socket: WebSocket

                fun finish(result: Result<Unit>) {
                    if (!finished.compareAndSet(false, true)) return
                    if (continuation.isActive) {
                        result.fold(
                            { continuation.resume(Unit) },
                            continuation::resumeWithException,
                        )
                    }
                    runCatching { socket.close(1000, "done") }
                }

                val listener = object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val json = JsonParser.parseString(text).asJsonObject
                            when (json.get("type")?.asString) {
                                "auth_required" -> webSocket.send(
                                    """{"type":"auth","access_token":${jsonString(token)}}""",
                                )
                                "auth_ok" -> {
                                    val fields = buildString {
                                        append(
                                            """{"id":1,"type":"boox_smart_room/todo/$operation","entity_id":${jsonString(entityId)},"item":${jsonString(item)}""",
                                        )
                                        rename?.let { append(""","rename":${jsonString(it)}""") }
                                        status?.let { append(""","status":${jsonString(it)}""") }
                                        append('}')
                                    }
                                    webSocket.send(fields)
                                }
                                "auth_invalid" -> finish(
                                    Result.failure(IllegalStateException("Home Assistant rejected the API token")),
                                )
                                "result" -> {
                                    if (json.get("id")?.asInt != 1) return
                                    if (json.get("success")?.asBoolean == true) {
                                        finish(Result.success(Unit))
                                    } else {
                                        val error = json.getAsJsonObject("error")
                                        finish(
                                            Result.failure(
                                                IllegalStateException(
                                                    error?.get("message")?.asString
                                                        ?: "Home Assistant rejected the todo mutation",
                                                ),
                                            ),
                                        )
                                    }
                                }
                                "error" -> finish(
                                    Result.failure(
                                        IllegalStateException(
                                            json.get("error")?.asString
                                                ?: "Home Assistant rejected the todo mutation",
                                        ),
                                    ),
                                )
                            }
                        }.onFailure { finish(Result.failure(it)) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(Result.failure(IllegalStateException("WebSocket failed: ${t.message}", t)))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finish(Result.failure(IllegalStateException("WebSocket closed before the mutation completed ($code)")))
                    }
                }

                socket = client.newWebSocket(
                    Request.Builder()
                        .url(homeAssistantWebSocketUrl(baseUrl))
                        .header("Authorization", "Bearer $token")
                        .build(),
                    listener,
                )
                continuation.invokeOnCancellation {
                    finished.set(true)
                    socket.cancel()
                }
            }
        }
    }

    suspend fun listTodoItems(baseUrl: String, token: String, entityId: String): List<RemoteTodo> {
        return withTimeout(20_000) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                lateinit var socket: WebSocket

                fun finish(result: Result<List<RemoteTodo>>) {
                    if (!finished.compareAndSet(false, true)) return
                    if (continuation.isActive) {
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                    runCatching { socket.close(1000, "done") }
                }

                val listener = object : WebSocketListener() {
                    private var authenticated = false

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val json = JsonParser.parseString(text).asJsonObject
                            when (json.get("type")?.asString) {
                                "auth_required" -> {
                                    webSocket.send("""{"type":"auth","access_token":${jsonString(token)}}""")
                                }
                                "auth_ok" -> {
                                    authenticated = true
                                    webSocket.send(
                                        """{"id":1,"type":"todo/item/list","entity_id":${jsonString(entityId)}}""",
                                    )
                                }
                                "auth_invalid" -> finish(
                                    Result.failure(IllegalStateException("Home Assistant rejected the API token")),
                                )
                                "result" -> {
                                    if (!authenticated || json.get("id")?.asInt != 1) return
                                    finish(runCatching { parseTodoItemListResult(text) })
                                }
                            }
                        }.onFailure { finish(Result.failure(it)) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(Result.failure(IllegalStateException("WebSocket failed: ${t.message}", t)))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finish(Result.failure(IllegalStateException("WebSocket closed before the todo list arrived ($code)")))
                    }
                }

                socket = client.newWebSocket(
                    Request.Builder()
                        .url(homeAssistantWebSocketUrl(baseUrl))
                        .header("Authorization", "Bearer $token")
                        .build(),
                    listener,
                )
                continuation.invokeOnCancellation {
                    finished.set(true)
                    socket.cancel()
                }
            }
        }
    }
}

private fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append('"')
    }
