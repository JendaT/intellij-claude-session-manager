package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable

/**
 * Plugin settings stored at application level.
 */
@Serializable
data class PluginSettings(
    /**
     * Claude CLI command to execute.
     * Default is "claude", but can be changed to "claude-x" or other variants.
     */
    val claudeCommand: String = "claude",

    /**
     * Whether to auto-resume sessions on IDE startup.
     */
    val autoResumeEnabled: Boolean = true,

    /**
     * Delay in milliseconds before auto-resume on IDE startup.
     * Allows terminal to fully initialize.
     */
    val autoResumeDelayMs: Long = 2000,

    /**
     * Whether to show notifications for session changes.
     */
    val notifyOnSessionChange: Boolean = true,

    /**
     * Whether to show notifications for auto-detected sessions.
     */
    val notifyOnAutoDetection: Boolean = true,

    /**
     * Whether to sync Claude's /rename to terminal tab names.
     */
    val syncTabNames: Boolean = true,

    /**
     * Custom Claude home directory path. If null, auto-detection is used.
     */
    val customClaudeHomePath: String? = null,

    /**
     * File watcher debounce delay in milliseconds.
     */
    val fileWatcherDebounceMs: Long = 500,

    /**
     * Maximum session history entries per binding.
     */
    val maxHistoryPerBinding: Int = 20,

    /**
     * Days to keep orphaned history entries before cleanup.
     */
    val orphanedHistoryRetentionDays: Int = 30
) {
    companion object {
        val DEFAULT = PluginSettings()

        // Constraints
        const val MIN_AUTO_RESUME_DELAY_MS = 500L
        const val MAX_AUTO_RESUME_DELAY_MS = 30000L
        const val MIN_DEBOUNCE_MS = 100L
        const val MAX_DEBOUNCE_MS = 5000L
        const val MIN_HISTORY_SIZE = 5
        const val MAX_HISTORY_SIZE = 100
    }

    /**
     * Validates and returns a corrected copy of settings.
     */
    fun validated(): PluginSettings = copy(
        autoResumeDelayMs = autoResumeDelayMs.coerceIn(MIN_AUTO_RESUME_DELAY_MS, MAX_AUTO_RESUME_DELAY_MS),
        fileWatcherDebounceMs = fileWatcherDebounceMs.coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS),
        maxHistoryPerBinding = maxHistoryPerBinding.coerceIn(MIN_HISTORY_SIZE, MAX_HISTORY_SIZE),
        orphanedHistoryRetentionDays = orphanedHistoryRetentionDays.coerceIn(1, 365)
    )
}
