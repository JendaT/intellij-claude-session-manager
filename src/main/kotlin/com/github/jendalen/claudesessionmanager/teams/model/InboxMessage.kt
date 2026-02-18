package com.github.jendalen.claudesessionmanager.teams.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a message from an agent's inbox file.
 *
 * Messages are stored in ~/.claude/teams/{team-id}/inboxes/{agent-id}.json.
 * They represent inter-agent communication within a team.
 *
 * Claude Code writes "sender" and "recipient" fields in JSON, so we use
 * @SerialName to map them while keeping idiomatic Kotlin property names.
 */
@Serializable
data class InboxMessage(
    val type: String = "message",
    @SerialName("sender") val from: String = "",
    @SerialName("recipient") val to: String = "",
    val content: String = "",
    val timestamp: Long = 0,
    val summary: String = ""
) {
    /**
     * Returns a display-friendly preview of the content, truncated if needed.
     */
    fun contentPreview(maxLength: Int = 80): String {
        val text = summary.ifBlank { content }
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }
}
