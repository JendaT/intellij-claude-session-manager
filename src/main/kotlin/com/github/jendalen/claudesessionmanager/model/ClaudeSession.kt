package com.github.jendalen.claudesessionmanager.model

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

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
    val sessionId: String = "",
    val fullPath: String = "",
    val fileMtime: Long = 0,
    val firstPrompt: String = "",
    val summary: String = "",
    val messageCount: Int = 0,
    val created: String = "",  // ISO8601 timestamp
    val modified: String = "", // ISO8601 timestamp
    val gitBranch: String = "",
    val projectPath: String = "",
    val isSidechain: Boolean = false
) {
    companion object {
        private val logger = Logger.getInstance(ClaudeSession::class.java)
    }

    /**
     * Returns the creation timestamp as Instant.
     * Falls back to fileMtime if parsing fails.
     */
    fun createdInstant(): Instant = try {
        Instant.parse(created)
    } catch (e: DateTimeParseException) {
        logger.warn("Failed to parse created timestamp '$created' for session $sessionId, using fileMtime")
        Instant.ofEpochMilli(fileMtime)
    }

    /**
     * Returns the modification timestamp as Instant.
     * Falls back to fileMtime if parsing fails.
     */
    fun modifiedInstant(): Instant = try {
        Instant.parse(modified)
    } catch (e: DateTimeParseException) {
        logger.warn("Failed to parse modified timestamp '$modified' for session $sessionId, using fileMtime")
        Instant.ofEpochMilli(fileMtime)
    }

    /**
     * Returns the file modification time as Instant.
     */
    fun fileMtimeInstant(): Instant = Instant.ofEpochMilli(fileMtime)

    /**
     * Returns a display-friendly summary, falling back to firstPrompt if summary is empty.
     */
    fun displaySummary(): String {
        val text = summary.ifBlank { firstPrompt.take(50) + if (firstPrompt.length > 50) "..." else "" }
        return text.ifBlank { "${sessionId.take(8)}..." }
    }
}

/**
 * Root structure of sessions-index.json.
 */
@Serializable
data class SessionsIndex(
    val version: Int = 1,
    val entries: List<ClaudeSession> = emptyList(),
    val originalPath: String = ""
)
