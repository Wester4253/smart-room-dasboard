package com.example.smartroomdashboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoTest {
    @Test
    fun `base URL always has one trailing slash`() {
        assertEquals("http://ha.local:8123/", "http://ha.local:8123".normalizedBaseUrl())
        assertEquals("http://ha.local:8123/", "http://ha.local:8123/".normalizedBaseUrl())
        assertEquals("", "".normalizedBaseUrl())
    }

    @Test
    fun `new todo starts open with an id`() {
        val todo = Todo(title = "Turn on lights")
        assertTrue(todo.id.isNotBlank())
        assertEquals(false, todo.completed)
    }

    @Test
    fun `nabu casa remote hosts are recognised`() {
        assertTrue("https://a1b2c3d4.ui.nabu.casa".isNabuCasaHost())
        assertTrue("https://a1b2c3d4.ui.nabu.casa/".isNabuCasaHost())
        assertTrue("  https://a1b2c3d4.ui.nabu.casa/api  ".isNabuCasaHost())
        assertEquals(false, "https://home.example.com".isNabuCasaHost())
        // A lookalike must not be accepted, or we would send a password to it.
        assertEquals(false, "https://ui.nabu.casa.evil.test".isNabuCasaHost())
        assertEquals(false, "https://evil.test/ui.nabu.casa".isNabuCasaHost())
    }

    @Test
    fun `implausible base urls are rejected before a webview opens`() {
        assertTrue("https://a1b2c3d4.ui.nabu.casa".isPlausibleBaseUrl())
        assertTrue("http://homeassistant.local:8123".isPlausibleBaseUrl())
        assertEquals(false, "".isPlausibleBaseUrl())
        assertEquals(false, "homeassistant.local:8123".isPlausibleBaseUrl())
        assertEquals(false, "ftp://homeassistant.local".isPlausibleBaseUrl())
        assertEquals(false, "https://".isPlausibleBaseUrl())
    }

    @Test
    fun `login url points at a real frontend page, not the authorize view`() {
        // /auth/authorize needs a registered IndieAuth client, which we do not
        // have, so setup must use a normal page.
        assertEquals(
            "https://a1b2c3d4.ui.nabu.casa/lovelace",
            homeAssistantLoginUrl("https://a1b2c3d4.ui.nabu.casa"),
        )
    }

    @Test
    fun `onboarding script asks for a long lived token over the websocket`() {
        val script = onboardingScript()
        assertTrue(script.contains("auth/long_lived_access_token"))
        // lifespan is a required int in days; omitting it fails validation.
        assertTrue(script.contains("lifespan: $TOKEN_LIFESPAN_DAYS"))
        assertTrue(script.contains("/api/websocket"))
        assertTrue(script.contains("/api/config"))
        assertTrue(script.contains("refresh_token"))
    }

    @Test
    fun `onboarding script reports failures instead of hanging`() {
        val script = onboardingScript()
        assertTrue(script.contains("auth_invalid"))
        assertTrue(script.contains("onError"))
    }

    @Test
    fun `primary todo entity keeps an existing choice and otherwise takes the first`() {
        val found = listOf("todo.shopping" to "Shopping", "todo.work" to "Work")
        assertEquals("todo.work", choosePrimaryTodoEntity(found, "todo.work"))
        assertEquals("todo.shopping", choosePrimaryTodoEntity(found, "todo.gone"))
        assertEquals("", choosePrimaryTodoEntity(emptyList(), "todo.work"))
    }

    @Test
    fun `settings clamp the text scale into the supported range`() {
        assertEquals(MAX_TEXT_SCALE, AppSettings(textScale = 9f).clampedTextScale(), 0.001f)
        assertEquals(MIN_TEXT_SCALE, AppSettings(textScale = 0.1f).clampedTextScale(), 0.001f)
        assertEquals(1.3f, AppSettings(textScale = 1.3f).clampedTextScale(), 0.001f)
    }

    @Test
    fun `isConfigured requires a url an entity and the completed flag`() {
        assertEquals(false, AppSettings().isConfigured)
        assertEquals(
            false,
            AppSettings(homeAssistantUrl = "http://ha/", todoEntityId = "todo.a").isConfigured,
        )
        assertEquals(
            true,
            AppSettings(
                homeAssistantUrl = "http://ha/",
                todoEntityId = "todo.a",
                setupCompleted = true,
            ).isConfigured,
        )
    }
}
