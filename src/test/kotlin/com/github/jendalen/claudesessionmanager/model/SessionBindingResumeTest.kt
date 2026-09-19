package com.github.jendalen.claudesessionmanager.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [SessionBinding.resumableSessionId] - the history fallback that stops a
 * phantom current id (no transcript on disk) from being reported as a purged session.
 */
class SessionBindingResumeTest {

    private fun binding(current: String, vararg history: Pair<String, Long>) = SessionBinding(
        id = "b1",
        tabIdentifier = TabIdentifier(name = "Local", createdAt = 1L),
        currentSessionId = current,
        sessionHistory = history.map { SessionHistoryEntry(it.first, it.second) },
        createdAt = 1L
    )

    @Test
    fun `current id wins when it has a transcript`() {
        val b = binding("cur", "old" to 10L)
        assertEquals("cur", b.resumableSessionId { true })
    }

    @Test
    fun `falls back to the newest history entry with a transcript`() {
        val b = binding("phantom", "older" to 10L, "newer" to 20L, "newest-but-purged" to 30L)
        assertEquals("newer", b.resumableSessionId { it == "newer" || it == "older" })
    }

    @Test
    fun `history order is by replacedAt not list order`() {
        val b = binding("phantom", "a" to 5L, "b" to 50L)
        assertEquals("b", b.resumableSessionId { it != "phantom" })
    }

    @Test
    fun `null when nothing has a transcript`() {
        val b = binding("phantom", "old" to 10L)
        assertNull(b.resumableSessionId { false })
    }

    @Test
    fun `ids repeated in history are probed once and in newest position`() {
        val b = binding("cur", "cur" to 40L, "x" to 30L)
        val probed = mutableListOf<String>()
        assertEquals("x", b.resumableSessionId { probed.add(it); it == "x" })
        assertEquals(listOf("cur", "x"), probed)
    }
}
