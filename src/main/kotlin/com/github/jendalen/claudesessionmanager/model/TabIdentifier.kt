package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable

/**
 * Composite identifier for terminal tabs.
 *
 * JetBrains terminal tab IDs may not persist across IDE restarts or may change
 * when tabs are reordered. This composite key uses weighted matching:
 * - Primary: tab name (user-visible)
 * - Secondary: creation timestamp (survives reorders)
 * - Tertiary: shell PID or working directory (optional)
 *
 * Matching strategy:
 * 1. Exact match on all fields = 100% confidence
 * 2. Name + timestamp match = 90% confidence
 * 3. Name only match (no duplicates) = 70% confidence
 * 4. Name match with duplicates = requires disambiguation
 */
@Serializable
data class TabIdentifier(
    val name: String,
    val createdAt: Long,           // Epoch millis when tab was created
    val shellPid: Long? = null,    // Shell process ID (optional)
    val workingDirectory: String? = null  // Initial CWD (optional)
) {
    /**
     * Calculates match confidence against another TabIdentifier.
     * Returns value between 0.0 and 1.0.
     *
     * NOTE: After IDE restart, timestamp and PID change completely.
     * Name matching is the primary and often only reliable match.
     */
    fun matchConfidence(other: TabIdentifier): Double {
        if (this == other) return 1.0

        // Name must match (case-sensitive)
        if (name != other.name) {
            return 0.0
        }

        // Name matches - this is sufficient for basic matching (0.7 base score)
        var score = 0.7

        // Bonus for timestamp match (useful within same session)
        val timeDelta = kotlin.math.abs(createdAt - other.createdAt)
        if (timeDelta < 1000) {
            score += 0.2
        } else if (timeDelta < 5000) {
            score += 0.1
        }

        // Shell PID match bonus
        if (shellPid != null && other.shellPid != null && shellPid == other.shellPid) {
            score += 0.05
        }

        // Working directory match bonus
        if (workingDirectory != null && other.workingDirectory != null &&
            workingDirectory == other.workingDirectory) {
            score += 0.05
        }

        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.8
        const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5
    }
}
