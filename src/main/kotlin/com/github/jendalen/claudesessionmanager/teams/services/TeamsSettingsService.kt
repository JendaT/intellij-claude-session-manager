package com.github.jendalen.claudesessionmanager.teams.services

import com.github.jendalen.claudesessionmanager.teams.model.TeamsSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level service for managing Agent Teams settings.
 *
 * Settings are stored in the global IDE configuration alongside the v1 settings.
 */
@Service(Service.Level.APP)
@State(
    name = "ClaudeAgentTeamsSettings",
    storages = [Storage("claudeAgentTeams.xml")]
)
class TeamsSettingsService : PersistentStateComponent<TeamsSettingsService.State> {

    /**
     * Serializable state class for persistence.
     * Must be a regular class (not data class) for IntelliJ XML serialization.
     */
    class State {
        var enableTeamDetection: Boolean = false
        var createTabsForInProcessTeammates: Boolean = false
        var nativeTeammateSpawning: Boolean = false
        var groupTeammateTabs: Boolean = true
        var autoFocusNewTeammateTabs: Boolean = false
        var showDashboardOnTeamCreate: Boolean = true
        var maxMessagesInLog: Int = 20
        var truncateMessageLength: Int = 80
        var notifyOnTeamCreate: Boolean = true
        var notifyOnTaskComplete: Boolean = true
        var notifyOnTeammateError: Boolean = true
        var notifyOnTeammateShutdown: Boolean = false
        var autoCloseTabsOnTeamDelete: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Returns current settings as immutable TeamsSettings.
     */
    fun getSettings(): TeamsSettings = TeamsSettings(
        enableTeamDetection = state.enableTeamDetection,
        createTabsForInProcessTeammates = state.createTabsForInProcessTeammates,
        nativeTeammateSpawning = state.nativeTeammateSpawning,
        groupTeammateTabs = state.groupTeammateTabs,
        autoFocusNewTeammateTabs = state.autoFocusNewTeammateTabs,
        showDashboardOnTeamCreate = state.showDashboardOnTeamCreate,
        maxMessagesInLog = state.maxMessagesInLog,
        truncateMessageLength = state.truncateMessageLength,
        notifyOnTeamCreate = state.notifyOnTeamCreate,
        notifyOnTaskComplete = state.notifyOnTaskComplete,
        notifyOnTeammateError = state.notifyOnTeammateError,
        notifyOnTeammateShutdown = state.notifyOnTeammateShutdown,
        autoCloseTabsOnTeamDelete = state.autoCloseTabsOnTeamDelete
    ).validated()

    /**
     * Updates settings from TeamsSettings object.
     */
    fun updateSettings(settings: TeamsSettings) {
        val validated = settings.validated()
        state.enableTeamDetection = validated.enableTeamDetection
        state.createTabsForInProcessTeammates = validated.createTabsForInProcessTeammates
        state.nativeTeammateSpawning = validated.nativeTeammateSpawning
        state.groupTeammateTabs = validated.groupTeammateTabs
        state.autoFocusNewTeammateTabs = validated.autoFocusNewTeammateTabs
        state.showDashboardOnTeamCreate = validated.showDashboardOnTeamCreate
        state.maxMessagesInLog = validated.maxMessagesInLog
        state.truncateMessageLength = validated.truncateMessageLength
        state.notifyOnTeamCreate = validated.notifyOnTeamCreate
        state.notifyOnTaskComplete = validated.notifyOnTaskComplete
        state.notifyOnTeammateError = validated.notifyOnTeammateError
        state.notifyOnTeammateShutdown = validated.notifyOnTeammateShutdown
        state.autoCloseTabsOnTeamDelete = validated.autoCloseTabsOnTeamDelete
    }

    companion object {
        fun getInstance(): TeamsSettingsService {
            return ApplicationManager.getApplication().getService(TeamsSettingsService::class.java)
        }
    }
}
