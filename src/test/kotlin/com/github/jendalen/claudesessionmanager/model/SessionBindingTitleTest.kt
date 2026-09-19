package com.github.jendalen.claudesessionmanager.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Title state is per session: a session change must drop both the observed title and a
 * pending push, or the next resume would type the old tab name into a different session.
 */
class SessionBindingTitleTest {

    private val binding = SessionBinding(
        id = "b1",
        tabIdentifier = TabIdentifier(name = "Local", createdAt = 1L),
        currentSessionId = "4e695d61-3fa2-40af-8f76-45977b1cddfa",
        createdAt = 1L,
        sessionTitle = "payments",
        pendingTitlePush = "payments-v2"
    )

    @Test
    fun `a new session clears the title and the pending push`() {
        val next = binding.withNewSession("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertNull(next.sessionTitle)
        assertNull(next.pendingTitlePush)
        assertEquals(1, next.sessionHistory.size)
    }

    @Test
    fun `the same session keeps them`() {
        val same = binding.withNewSession(binding.currentSessionId)
        assertEquals("payments", same.sessionTitle)
        assertEquals("payments-v2", same.pendingTitlePush)
    }
}
