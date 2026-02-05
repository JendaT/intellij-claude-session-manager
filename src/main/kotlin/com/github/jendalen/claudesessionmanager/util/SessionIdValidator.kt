package com.github.jendalen.claudesessionmanager.util

import java.util.UUID

/**
 * Validates Claude session IDs.
 *
 * Session IDs are UUIDs in standard format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 */
object SessionIdValidator {
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Validates that the given string is a valid UUID format.
     */
    fun isValid(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        return UUID_PATTERN.matches(sessionId)
    }

    /**
     * Parses a session ID to UUID, returning null if invalid.
     */
    fun parseOrNull(sessionId: String?): UUID? {
        if (!isValid(sessionId)) return null
        return try {
            UUID.fromString(sessionId)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Generates a new random session-like ID for internal use (e.g., binding IDs).
     */
    fun generateId(): String = UUID.randomUUID().toString()
}
