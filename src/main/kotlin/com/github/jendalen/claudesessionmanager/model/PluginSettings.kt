package com.github.jendalen.claudesessionmanager.model

import com.intellij.openapi.diagnostic.Logger
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
    val orphanedHistoryRetentionDays: Int = 30,

    /**
     * Whether to force "Use Option as Meta Key" off in terminal settings.
     * Works around Claude Code repeatedly enabling this setting.
     * macOS only - has no effect on other platforms.
     */
    val forceOptionAsMetaKeyOff: Boolean = false
) {
    companion object {
        private val logger = Logger.getInstance(PluginSettings::class.java)

        val DEFAULT = PluginSettings()

        // Constraints
        const val MIN_AUTO_RESUME_DELAY_MS = 500L
        const val MAX_AUTO_RESUME_DELAY_MS = 30000L
        const val MIN_DEBOUNCE_MS = 100L
        const val MAX_DEBOUNCE_MS = 5000L
        const val MIN_HISTORY_SIZE = 5
        const val MAX_HISTORY_SIZE = 100
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 365
    }

    /**
     * Validates and returns a corrected copy of settings.
     * Logs warnings when values are corrected.
     */
    fun validated(): PluginSettings {
        val correctedDelay = autoResumeDelayMs.coerceIn(MIN_AUTO_RESUME_DELAY_MS, MAX_AUTO_RESUME_DELAY_MS)
        val correctedDebounce = fileWatcherDebounceMs.coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS)
        val correctedHistory = maxHistoryPerBinding.coerceIn(MIN_HISTORY_SIZE, MAX_HISTORY_SIZE)
        val correctedRetention = orphanedHistoryRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

        if (correctedDelay != autoResumeDelayMs) {
            logger.warn("autoResumeDelayMs $autoResumeDelayMs out of range, corrected to $correctedDelay")
        }
        if (correctedDebounce != fileWatcherDebounceMs) {
            logger.warn("fileWatcherDebounceMs $fileWatcherDebounceMs out of range, corrected to $correctedDebounce")
        }
        if (correctedHistory != maxHistoryPerBinding) {
            logger.warn("maxHistoryPerBinding $maxHistoryPerBinding out of range, corrected to $correctedHistory")
        }
        if (correctedRetention != orphanedHistoryRetentionDays) {
            logger.warn("orphanedHistoryRetentionDays $orphanedHistoryRetentionDays out of range, corrected to $correctedRetention")
        }

        val correctedCommand = if (claudeCommand.matches(Regex("[a-zA-Z0-9_./ \\-]+"))) {
            claudeCommand
        } else {
            logger.warn("claudeCommand contains invalid characters, falling back to default")
            "claude"
        }

        return copy(
            claudeCommand = correctedCommand,
            autoResumeDelayMs = correctedDelay,
            fileWatcherDebounceMs = correctedDebounce,
            maxHistoryPerBinding = correctedHistory,
            orphanedHistoryRetentionDays = correctedRetention
        )
    }
}
