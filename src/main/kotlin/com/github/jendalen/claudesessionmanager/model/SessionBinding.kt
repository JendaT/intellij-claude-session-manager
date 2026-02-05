package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable

/**
 * Links a terminal tab to a Claude session.
 *
 * Stored in .idea/claudeSessionManager.xml per project.
 */
@Serializable
data class SessionBinding(
    val id: String,                      // Unique binding ID (UUID)
    val tabIdentifier: TabIdentifier,    // Composite terminal tab identifier
    val currentSessionId: String,        // Current Claude session UUID
    val sessionHistory: List<SessionHistoryEntry> = emptyList(),
    val autoResume: Boolean = true,
    val createdAt: Long,                 // When binding was created (epoch millis)
    val lastResumedAt: Long? = null,     // Last successful resume (epoch millis)
    val resumeCount: Int = 0             // Number of successful resumes
) {
    /**
     * Maximum number of history entries to keep per binding.
     */
    companion object {
        const val MAX_HISTORY_SIZE = 20
    }

    /**
     * Creates a new binding with the session added to history.
     */
    fun withNewSession(newSessionId: String): SessionBinding {
        // Don't add to history if it's the same session
        if (newSessionId == currentSessionId) return this

        val historyEntry = SessionHistoryEntry(
            sessionId = currentSessionId,
            replacedAt = System.currentTimeMillis()
        )

        // Prepend new entry and trim to max size
        val updatedHistory = (listOf(historyEntry) + sessionHistory).take(MAX_HISTORY_SIZE)

        return copy(
            currentSessionId = newSessionId,
            sessionHistory = updatedHistory
        )
    }

    /**
     * Marks the binding as resumed.
     */
    fun markResumed(): SessionBinding = copy(
        lastResumedAt = System.currentTimeMillis(),
        resumeCount = resumeCount + 1
    )
}

/**
 * Entry in session history when a session is replaced.
 */
@Serializable
data class SessionHistoryEntry(
    val sessionId: String,
    val replacedAt: Long  // When this session was replaced (epoch millis)
)
