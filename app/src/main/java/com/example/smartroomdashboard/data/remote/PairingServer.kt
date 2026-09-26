package com.example.smartroomdashboard.data.remote

import android.util.Log
import com.example.smartroomdashboard.domain.PairingPayload
import com.example.smartroomdashboard.domain.parsePairingPayload
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Listens on the local network so Home Assistant can hand this tablet its address.
 *
 * The problem this solves: a Nabu Casa remote UI host is per-account, and the user
 * cannot be expected to read it off a settings page and type it on an e-ink tablet
 * with no camera to scan a QR code.
 *
 * So the direction is inverted. The tablet listens, Home Assistant pushes. What
 * gets pushed is only an address and a list of todo entities, gated on [code]; see
 * [PairingPayload] for why no token is involved. The tablet then mints its own
 * token over the WebSocket, in the browser-origin flow.
 *
 * Deliberately narrow: one request at a time, a short read timeout, and a hard cap
 * on body size, so this is not a general-purpose listener sitting on the network.
 */
class PairingServer(
    private val onPairing: (PairingPayload) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** The code Home Assistant must echo back. Shown on screen by the UI. */
    val code: String = generateCode()

    val port: Int
        get() = serverSocket?.localPort ?: 0

    /** True once the socket is bound, so the UI can show a real address. */
    val isListening: Boolean
        get() = running && serverSocket?.isBound == true

    /**
     * The tablet's own address on this network, for the on-screen pairing
     * instructions.
     *
     * Resolved by opening a throwaway UDP socket, which picks the interface the OS
     * would route through without sending anything. The address changes with the
     * Wi-Fi network, so this is not cached.
     */
    fun localAddress(): String? {
        val socket = runCatching { java.net.DatagramSocket() }.getOrNull() ?: return null
        val address = runCatching {
            // Connecting a UDP socket picks the outbound interface without
            // sending any packet.
            socket.connect(InetAddress.getByName("10.255.255.255"), 1)
            socket.localAddress?.hostAddress
        }.getOrNull()
        runCatching { socket.close() }
        return address
    }

    fun start(port: Int = 0): Boolean {
        if (running) return true
        val socket = runCatching {
            ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))
        }.getOrElse { error ->
            Log.w(TAG, "could not open the pairing port", error)
            onFailure("Could not open a listening port for pairing.")
            return false
        }
        serverSocket = socket
        running = true
        Log.i(TAG, "pairing listener on port ${socket.localPort}")
        thread(name = "pairing-server", isDaemon = true) {
            try {
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept failed", e)
                        break
                    }
                    // One request at a time: a pairing push is a single small POST.
                    thread(name = "pairing-client", isDaemon = true) { handle(client) }
                }
            } finally {
                running = false
                runCatching { socket.close() }
                serverSocket = null
            }
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        val accepted = runCatching {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                when (val result = readPairingRequest(socket.getInputStream(), code)) {
                    is PairingHttpResult.Ok -> {
                        Log.i(TAG, "pairing push accepted: ${result.payload.baseUrl}")
                        respond(socket, HttpResult(200, "Paired."))
                        onPairing(result.payload)
                    }
                    is PairingHttpResult.Rejected -> {
                        respond(socket, HttpResult(403, result.reason))
                        onFailure(
                            "A pairing attempt was rejected (${result.reason}). " +
                                "Only press the button from your own Home Assistant.",
                        )
                    }
                }
            }
        }
        accepted.onFailure { Log.w(TAG, "pairing request failed", it) }
    }

    private fun respond(socket: Socket, result: HttpResult) {
        runCatching {
            val body = """{"ok":${result.status == 200},"message":"${result.message}"}"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 ${result.status} ${result.reason()}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8),
            )
            out.write(bytes)
            out.flush()
        }
    }

    private data class HttpResult(val status: Int, val message: String) {
        fun reason(): String = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            405 -> "Method Not Allowed"
            else -> "Length Required"
        }
    }

    companion object {
        private const val TAG = "PairingServer"
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_BODY_BYTES = 8 * 1024

        /** Six digits, avoiding sequences that are easy to misread aloud. */
        fun generateCode(): String {
            val random = SecureRandom()
            var code: String
            do {
                code = (100_000 + random.nextInt(900_000)).toString()
            } while (code.toSet().size < 5)
            return code
        }
    }
}

/** Outcome of reading one pairing request. */
sealed interface PairingHttpResult {
    data class Ok(val payload: PairingPayload) : PairingHttpResult
    data class Rejected(val status: Int, val reason: String) : PairingHttpResult
}

/** Largest request body accepted, to bound what a stranger can make us buffer. */
const val PAIRING_MAX_BODY_BYTES = 8 * 1024

/**
 * Parses one HTTP request from [input] and validates the pairing code.
 *
 * Split out from [PairingServer] and free of `android.*` so it is covered by the
 * JVM unit tests. Reads the body as *bytes* and only then decodes UTF-8:
 * `Content-Length` counts bytes, so decoding straight into a `CharArray` would
 * desynchronise on any multi-byte character and corrupt the parse.
 */
fun readPairingRequest(input: java.io.InputStream, code: String): PairingHttpResult {
    fun readLineOrNull(): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                return builder.toString()
            }
            // Guard against a client that never sends a newline.
            if (builder.length > MAX_LINE_BYTES) return null
            builder.append(b.toChar())
        }
    }

    val requestLine = readLineOrNull()
        ?: return PairingHttpResult.Rejected(400, "Empty request")
    if (!requestLine.contains("POST")) {
        return PairingHttpResult.Rejected(405, "Only POST is supported")
    }

    var contentLength = -1
    while (true) {
        val line = readLineOrNull() ?: break
        if (line.isEmpty()) break
        if (line.length > MAX_LINE_BYTES) {
            return PairingHttpResult.Rejected(400, "Header too long")
        }
        val name = line.substringBefore(':', "")
        if (name.equals("Content-Length", ignoreCase = true)) {
            contentLength = line.substringAfter(':', "").trim().toIntOrNull() ?: -1
        }
    }

    if (contentLength <= 0) {
        return PairingHttpResult.Rejected(411, "Content-Length is required")
    }
    if (contentLength > PAIRING_MAX_BODY_BYTES) {
        return PairingHttpResult.Rejected(413, "Body too large")
    }

    val bytes = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
        val n = input.read(bytes, read, contentLength - read)
        if (n < 0) break
        read += n
    }
    if (read != contentLength) {
        return PairingHttpResult.Rejected(400, "Truncated body")
    }

    val payload = parsePairingPayload(String(bytes, StandardCharsets.UTF_8), code)
        ?: return PairingHttpResult.Rejected(403, "Wrong or missing code")
    return PairingHttpResult.Ok(payload)
}

private const val MAX_LINE_BYTES = 8 * 1024

/** Builds the body Home Assistant posts. Shared shape with [parsePairingPayload]. */
fun pairingRequestBody(code: String, payload: PairingPayload): String = buildString {
    append("{\"code\":\"").append(code).append('"')
    append(",\"baseUrl\":\"").append(payload.baseUrl).append('"')
    append(",\"locationName\":\"").append(payload.locationName).append('"')
    append(",\"todoEntities\":[")
    payload.todoEntities.forEachIndexed { index, (entityId, name) ->
        if (index > 0) append(',')
        append("[\"").append(entityId).append("\",\"").append(name).append("\"]")
    }
    append("]}")
}

/** Blocking POST helper, used by the integration's companion script and by tests. */
fun postPairingPayload(host: String, port: Int, code: String, payload: PairingPayload): Boolean =
    runCatching {
        Socket(host, port).use { socket ->
            socket.soTimeout = 5_000
            val body = pairingRequestBody(code, payload)
            val request = (
                "POST / HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
                )
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            socket.getInputStream().bufferedReader().readLine()?.contains(" 200 ") == true
        }
    }.getOrDefault(false)
