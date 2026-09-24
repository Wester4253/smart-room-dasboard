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
}
