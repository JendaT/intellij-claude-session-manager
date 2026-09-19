package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable

/**
 * Links a terminal tab to a Claude session.
 *
 * Persisted per project in .idea/workspace.xml (component ClaudeSessionBindings).
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

    /**
     * The newest session of this binding that can actually be resumed: the current id
     * first, then the history newest-first, the first one for which [hasTranscript] is
     * true. Null when none has a transcript.
     *
     * The current id alone is not enough: a fresh session writes its transcript only after
     * the first user message, and misattributed startup events plant ids that never get
     * one. Treating such an id as "purged" lost sessions that were sitting in history.
     */
    fun resumableSessionId(hasTranscript: (String) -> Boolean): String? {
        val candidates = listOf(currentSessionId) +
            sessionHistory.sortedByDescending { it.replacedAt }.map { it.sessionId }
        return candidates.distinct().firstOrNull(hasTranscript)
    }
}

/**
 * Entry in session history when a session is replaced.
 */
@Serializable
data class SessionHistoryEntry(
    val sessionId: String,
    val replacedAt: Long  // When this session was replaced (epoch millis)
)
