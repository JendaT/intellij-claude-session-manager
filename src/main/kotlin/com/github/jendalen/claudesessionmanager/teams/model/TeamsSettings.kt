package com.github.jendalen.claudesessionmanager.teams.model

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable

/**
 * Settings specific to Agent Teams v2.0 features.
 */
@Serializable
data class TeamsSettings(
    val enableTeamDetection: Boolean = false,
    val createTabsForInProcessTeammates: Boolean = false,
    val nativeTeammateSpawning: Boolean = false,
    val groupTeammateTabs: Boolean = true,
    val autoFocusNewTeammateTabs: Boolean = false,
    val showDashboardOnTeamCreate: Boolean = true,
    val maxMessagesInLog: Int = 20,
    val truncateMessageLength: Int = 80,
    val notifyOnTeamCreate: Boolean = true,
    val notifyOnTaskComplete: Boolean = true,
    val notifyOnTeammateError: Boolean = true,
    val notifyOnTeammateShutdown: Boolean = false,
    val autoCloseTabsOnTeamDelete: Boolean = false
) {
    companion object {
        private val logger = Logger.getInstance(TeamsSettings::class.java)

        val DEFAULT = TeamsSettings()

        const val MIN_MESSAGES_IN_LOG = 5
        const val MAX_MESSAGES_IN_LOG = 100
        const val MIN_TRUNCATE_LENGTH = 20
        const val MAX_TRUNCATE_LENGTH = 500
    }

    /**
     * Validates and returns a corrected copy of settings.
     */
    fun validated(): TeamsSettings {
        val correctedMessages = maxMessagesInLog.coerceIn(MIN_MESSAGES_IN_LOG, MAX_MESSAGES_IN_LOG)
        val correctedTruncate = truncateMessageLength.coerceIn(MIN_TRUNCATE_LENGTH, MAX_TRUNCATE_LENGTH)

        if (correctedMessages != maxMessagesInLog) {
            logger.warn("maxMessagesInLog $maxMessagesInLog out of range, corrected to $correctedMessages")
        }
        if (correctedTruncate != truncateMessageLength) {
            logger.warn("truncateMessageLength $truncateMessageLength out of range, corrected to $correctedTruncate")
        }

        return copy(
            maxMessagesInLog = correctedMessages,
            truncateMessageLength = correctedTruncate
        )
    }
}
