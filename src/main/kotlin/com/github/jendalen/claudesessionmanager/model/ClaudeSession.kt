package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents a Claude Code session as stored in sessions-index.json.
 *
 * Field notes from investigation:
 * - fileMtime is in milliseconds (not seconds)
 * - gitBranch can be empty string
 * - isSidechain purpose unknown, always false in samples
 */
@Serializable
data class ClaudeSession(
    val sessionId: String,
    val fullPath: String,
    val fileMtime: Long,
    val firstPrompt: String,
    val summary: String,
    val messageCount: Int,
    val created: String,  // ISO8601 timestamp
    val modified: String, // ISO8601 timestamp
    val gitBranch: String,
    val projectPath: String,
    val isSidechain: Boolean = false
) {
    /**
     * Returns the creation timestamp as Instant.
     */
    fun createdInstant(): Instant = Instant.parse(created)

    /**
     * Returns the modification timestamp as Instant.
     */
    fun modifiedInstant(): Instant = Instant.parse(modified)

    /**
     * Returns the file modification time as Instant.
     */
    fun fileMtimeInstant(): Instant = Instant.ofEpochMilli(fileMtime)

    /**
     * Returns a display-friendly summary, falling back to firstPrompt if summary is empty.
     */
    fun displaySummary(): String {
        return summary.ifBlank { firstPrompt.take(50) + if (firstPrompt.length > 50) "..." else "" }
    }
}

/**
 * Root structure of sessions-index.json.
 */
@Serializable
data class SessionsIndex(
    val version: Int,
    val entries: List<ClaudeSession>,
    val originalPath: String
)
