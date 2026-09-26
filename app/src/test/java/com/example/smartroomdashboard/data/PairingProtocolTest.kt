package com.example.smartroomdashboard.data

import com.example.smartroomdashboard.data.remote.PairingHttpResult
import com.example.smartroomdashboard.data.remote.pairingRequestBody
import com.example.smartroomdashboard.data.remote.readPairingRequest
import com.example.smartroomdashboard.domain.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Wire-format tests for the pairing listener.
 *
 * The Kotlin side and the Python side of this protocol share no schema, so these
 * exercise the exact bytes the integration's `_post_pairing` writes.
 */
class PairingProtocolTest {

    private fun request(body: String): ByteArrayInputStream {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return ByteArrayInputStream(
            (
                "POST / HTTP/1.1\r\n" +
                    "Host: 192.168.1.50:41234\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" + body
                ).toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun `a well formed push is accepted`() {
        val payload = PairingPayload(
            baseUrl = "https://a1b2c3d4.ui.nabu.casa",
            todoEntities = listOf("todo.shop" to "Shopping", "todo.work" to "Work"),
            locationName = "Home",
        )
        val result = readPairingRequest(request(pairingRequestBody("123456", payload)), "123456")
        assertTrue(result is PairingHttpResult.Ok)
        val ok = result as PairingHttpResult.Ok
        assertEquals("https://a1b2c3d4.ui.nabu.casa", ok.payload.baseUrl)
        assertEquals(2, ok.payload.todoEntities.size)
        assertEquals("Home", ok.payload.locationName)
    }

    @Test
    fun `multi-byte characters survive the byte length arithmetic`() {
        // Content-Length counts bytes, and these are two bytes each in UTF-8, so
        // a naive char-based read would truncate or overrun the body.
        val payload = PairingPayload(
            baseUrl = "https://a1b2c3d4.ui.nabu.casa",
            todoEntities = listOf("todo.repairs" to "Réparations"),
            locationName = "Wohnung Müller",
        )
        val result = readPairingRequest(request(pairingRequestBody("123456", payload)), "123456")
        assertTrue(result is PairingHttpResult.Ok)
        val ok = result as PairingHttpResult.Ok
        assertEquals("Wohnung Müller", ok.payload.locationName)
        assertEquals("Réparations", ok.payload.todoEntities.single().second)
    }

    @Test
    fun `the wrong code is refused`() {
        val payload = PairingPayload("https://x.ui.nabu.casa", emptyList())
        val result = readPairingRequest(request(pairingRequestBody("000000", payload)), "123456")
        assertEquals(403, (result as PairingHttpResult.Rejected).status)
    }

    @Test
    fun `a GET is refused`() {
        val raw = "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}"
        val result = readPairingRequest(ByteArrayInputStream(raw.toByteArray()), "123456")
        assertEquals(405, (result as PairingHttpResult.Rejected).status)
    }

    @Test
    fun `a missing content length is refused`() {
        val raw = "POST / HTTP/1.1\r\nHost: x\r\n\r\n{}"
        val result = readPairingRequest(ByteArrayInputStream(raw.toByteArray()), "123456")
        assertEquals(411, (result as PairingHttpResult.Rejected).status)
    }

    @Test
    fun `an oversized body is refused before it is buffered`() {
        val raw = "POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n"
        val result = readPairingRequest(ByteArrayInputStream(raw.toByteArray()), "123456")
        assertEquals(413, (result as PairingHttpResult.Rejected).status)
    }

    @Test
    fun `a truncated body is refused`() {
        val body = """{"code":"123456","baseUrl":"https://x.ui.nabu.casa"}"""
        val raw = "POST / HTTP/1.1\r\nContent-Length: ${body.length + 40}\r\n\r\n$body"
        val result = readPairingRequest(ByteArrayInputStream(raw.toByteArray()), "123456")
        assertEquals(400, (result as PairingHttpResult.Rejected).status)
    }

    @Test
    fun `accepts the exact bytes the python integration emits`() {
        // Captured from `json.dumps` in `boox_smart_room._pairing_body`, including
        // its default separators and non-ASCII escaping. If the two sides ever
        // drift, this is the test that notices.
        val python = """{"code": "123456", "baseUrl": "https://a1b2c3d4.ui.nabu.casa", """ +
            """"locationName": "Wohnung M\u00fcller", "todoEntities": """ +
            """[["todo.shop", "Shopping"], ["todo.repairs", "R\u00e9parations"]]}"""
        val result = readPairingRequest(request(python), "123456")
        assertTrue(result is PairingHttpResult.Ok)
        val ok = result as PairingHttpResult.Ok
        assertEquals("https://a1b2c3d4.ui.nabu.casa", ok.payload.baseUrl)
        // Gson decodes the \uXXXX escapes, so the name round-trips to real text.
        assertEquals("Wohnung Müller", ok.payload.locationName)
        assertEquals(2, ok.payload.todoEntities.size)
    }

    @Test
    fun `a quote in a location name cannot break the json`() {
        // The Python side uses json.dumps, so this is what it would send.
        val python = """{"code": "123456", "baseUrl": "https://x.ui.nabu.casa", """ +
            """"locationName": "Bob \"the builder\"", "todoEntities": []}"""
        val result = readPairingRequest(request(python), "123456")
        assertTrue(result is PairingHttpResult.Ok)
        assertEquals("""Bob "the builder"""", (result as PairingHttpResult.Ok).payload.locationName)
    }
}
