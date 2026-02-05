package com.github.jendalen.claudesessionmanager.model

/**
 * Represents the validated state of a Claude session.
 *
 * Used to determine what actions are available for a session before attempting them.
 * This validation layer prevents blind resume attempts and provides clear user feedback.
 */
sealed class SessionState {
    /**
     * Session exists and is currently active in a terminal.
     */
    data class Active(
        val session: ClaudeSession,
        val terminalPid: Long
    ) : SessionState()

    /**
     * Session exists and can be resumed.
     */
    data class Resumable(
        val session: ClaudeSession
    ) : SessionState()

    /**
     * Session is currently in use by another terminal/IDE instance.
     */
    data class Locked(
        val session: ClaudeSession,
        val owner: String
    ) : SessionState()

    /**
     * Session file is missing or unreadable.
     */
    data class Stale(
        val lastKnownInfo: SessionMetadata
    ) : SessionState()

    /**
     * Session file is corrupted or unreadable.
     */
    data class Corrupted(
        val sessionId: String,
        val error: String
    ) : SessionState()

    /**
     * Session does not exist.
     */
    object Missing : SessionState()

    /**
     * Claude CLI authentication has expired.
     */
    object AuthExpired : SessionState()

    /**
     * Session was created with a newer CLI version.
     */
    data class VersionMismatch(
        val sessionVersion: String,
        val currentVersion: String
    ) : SessionState()

    /**
     * Generic error state.
     */
    data class Error(
        val reason: String
    ) : SessionState()

    /**
     * Returns true if the session can be resumed.
     */
    fun isResumable(): Boolean = this is Resumable

    /**
     * Returns a user-friendly status message.
     */
    fun statusMessage(): String = when (this) {
        is Active -> "Active in terminal (PID: $terminalPid)"
        is Resumable -> "Ready to resume"
        is Locked -> "Locked by: $owner"
        is Stale -> "Session file missing"
        is Corrupted -> "Corrupted: $error"
        is Missing -> "Session not found"
        is AuthExpired -> "Claude authentication expired"
        is VersionMismatch -> "Version mismatch (session: $sessionVersion, current: $currentVersion)"
        is Error -> "Error: $reason"
    }
}

/**
 * Minimal metadata about a session, used when full session data is unavailable.
 */
data class SessionMetadata(
    val sessionId: String,
    val lastKnownSummary: String? = null,
    val lastKnownPath: String? = null
)
