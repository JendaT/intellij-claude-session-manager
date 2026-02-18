package com.github.jendalen.claudesessionmanager.teams.util

/**
 * Validates agent IDs from Claude Code team config files.
 *
 * Agent IDs come from untrusted filesystem input and are used in file paths
 * (e.g., for inbox files) and UI elements. This validator prevents path traversal
 * and injection attacks.
 */
object AgentIdValidator {
    /**
     * Pattern allowing alphanumeric, hyphens, underscores, dots, and @ signs.
     * Explicitly disallows path separators, shell metacharacters, and control chars.
     */
    private val VALID_AGENT_ID = Regex("^[a-zA-Z0-9._@-]+$")

    /**
     * Maximum agent ID length to prevent buffer-based attacks.
     */
    private const val MAX_LENGTH = 256

    /**
     * Validates that the given string is a safe agent ID.
     */
    fun isValid(agentId: String?): Boolean {
        if (agentId.isNullOrBlank()) return false
        if (agentId.length > MAX_LENGTH) return false
        if (agentId.contains("..")) return false
        return VALID_AGENT_ID.matches(agentId)
    }

    /**
     * Sanitizes an agent ID for safe use in UI display.
     * Strips any characters that could affect terminal rendering.
     */
    fun sanitizeForDisplay(agentId: String): String {
        return agentId.replace(Regex("[^a-zA-Z0-9._@\\- ]"), "").take(MAX_LENGTH)
    }
}
