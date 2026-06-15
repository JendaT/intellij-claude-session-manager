package com.github.jendalen.claudesessionmanager.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [TabIdentifier.matchConfidence], focused on the stable-UUID precedence introduced
 * to survive Claude Code's terminal-tab renaming.
 */
class TabIdentifierTest {

    private val now = 1_000_000L

    // --- Stable UUID precedence ---

    @Test
    fun `equal uuids match fully even when names differ`() {
        val a = TabIdentifier(name = "my-feature", createdAt = now, tabUuid = "uuid-1")
        // Claude Code has renamed the tab to "Claude Code", but it is the same tab.
        val b = TabIdentifier(name = "Claude Code", createdAt = now + 999_999, tabUuid = "uuid-1")
        assertEquals(1.0, a.matchConfidence(b))
    }

    @Test
    fun `different uuids never match even when names are identical`() {
        // Two distinct tabs both renamed to "Claude Code" must NOT be confused.
        val a = TabIdentifier(name = "Claude Code", createdAt = now, tabUuid = "uuid-1")
        val b = TabIdentifier(name = "Claude Code", createdAt = now, tabUuid = "uuid-2")
        assertEquals(0.0, a.matchConfidence(b))
    }

    @Test
    fun `uuid match wins regardless of timestamp distance`() {
        val a = TabIdentifier(name = "x", createdAt = 0, tabUuid = "u")
        val b = TabIdentifier(name = "y", createdAt = Long.MAX_VALUE / 2, tabUuid = "u")
        assertEquals(1.0, a.matchConfidence(b))
    }

    // --- Fallback when a uuid is missing (legacy / cross-restart) ---

    @Test
    fun `falls back to name match when one side has no uuid`() {
        val legacy = TabIdentifier(name = "build", createdAt = now)            // no uuid
        val live = TabIdentifier(name = "build", createdAt = now, tabUuid = "u")
        // Name matches and timestamps are identical -> high confidence via fallback path.
        assertTrue(legacy.matchConfidence(live) >= TabIdentifier.HIGH_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `fallback returns zero when names differ and uuids absent`() {
        val a = TabIdentifier(name = "foo", createdAt = now)
        val b = TabIdentifier(name = "bar", createdAt = now)
        assertEquals(0.0, a.matchConfidence(b))
    }

    @Test
    fun `legacy name-only match below uuid but above threshold`() {
        // Same name, far-apart timestamps (cross-restart): only the 0.7 name base applies.
        val a = TabIdentifier(name = "term", createdAt = 0)
        val b = TabIdentifier(name = "term", createdAt = 10_000_000)
        val score = a.matchConfidence(b)
        assertEquals(0.7, score)
        assertTrue(score >= TabIdentifier.MEDIUM_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `identical identifiers match fully`() {
        val a = TabIdentifier(name = "t", createdAt = now, shellPid = 5, workingDirectory = "/p", tabUuid = "u")
        assertEquals(1.0, a.matchConfidence(a.copy()))
    }

    @Test
    fun `working directory bonus applies on the fallback path`() {
        val a = TabIdentifier(name = "t", createdAt = 0, workingDirectory = "/proj")
        val b = TabIdentifier(name = "t", createdAt = 10_000_000, workingDirectory = "/proj")
        // 0.7 name base + 0.05 cwd bonus, timestamps too far apart for a time bonus.
        assertEquals(0.75, a.matchConfidence(b))
    }
}
