package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level service for managing plugin settings.
 *
 * Settings are stored in the global IDE configuration, not per-project.
 */
@Service(Service.Level.APP)
@State(
    name = "ClaudeSessionManagerSettings",
    storages = [Storage("claudeSessionManager.xml")]
)
class SettingsService : PersistentStateComponent<SettingsService.State> {

    /**
     * Serializable state class for persistence.
     * Note: Must be a regular class (not data class) for IntelliJ XML serialization.
     * All properties must be var with default values and have public getters/setters.
     */
    class State {
        var claudeCommand: String = "claude"
        var autoResumeEnabled: Boolean = true
        var autoResumeDelayMs: Long = 2000
        var notifyOnSessionChange: Boolean = true
        var notifyOnAutoDetection: Boolean = true
        var syncTabNames: Boolean = true
        var customClaudeHomePath: String? = null
        var fileWatcherDebounceMs: Long = 500
        var maxHistoryPerBinding: Int = 20
        var orphanedHistoryRetentionDays: Int = 30
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Returns current settings as immutable PluginSettings.
     */
    fun getSettings(): PluginSettings = PluginSettings(
        claudeCommand = state.claudeCommand,
        autoResumeEnabled = state.autoResumeEnabled,
        autoResumeDelayMs = state.autoResumeDelayMs,
        notifyOnSessionChange = state.notifyOnSessionChange,
        notifyOnAutoDetection = state.notifyOnAutoDetection,
        syncTabNames = state.syncTabNames,
        customClaudeHomePath = state.customClaudeHomePath,
        fileWatcherDebounceMs = state.fileWatcherDebounceMs,
        maxHistoryPerBinding = state.maxHistoryPerBinding,
        orphanedHistoryRetentionDays = state.orphanedHistoryRetentionDays
    ).validated()

    /**
     * Updates settings from PluginSettings object.
     */
    fun updateSettings(settings: PluginSettings) {
        val validated = settings.validated()
        state.claudeCommand = validated.claudeCommand
        state.autoResumeEnabled = validated.autoResumeEnabled
        state.autoResumeDelayMs = validated.autoResumeDelayMs
        state.notifyOnSessionChange = validated.notifyOnSessionChange
        state.notifyOnAutoDetection = validated.notifyOnAutoDetection
        state.syncTabNames = validated.syncTabNames
        state.customClaudeHomePath = validated.customClaudeHomePath
        state.fileWatcherDebounceMs = validated.fileWatcherDebounceMs
        state.maxHistoryPerBinding = validated.maxHistoryPerBinding
        state.orphanedHistoryRetentionDays = validated.orphanedHistoryRetentionDays
    }

    // Convenience accessors
    var claudeCommand: String
        get() = state.claudeCommand.ifBlank { "claude" }
        set(value) { state.claudeCommand = value.ifBlank { "claude" } }

    var autoResumeEnabled: Boolean
        get() = state.autoResumeEnabled
        set(value) { state.autoResumeEnabled = value }

    var autoResumeDelayMs: Long
        get() = state.autoResumeDelayMs
        set(value) { state.autoResumeDelayMs = value.coerceIn(
            PluginSettings.MIN_AUTO_RESUME_DELAY_MS,
            PluginSettings.MAX_AUTO_RESUME_DELAY_MS
        )}

    var notifyOnSessionChange: Boolean
        get() = state.notifyOnSessionChange
        set(value) { state.notifyOnSessionChange = value }

    var notifyOnAutoDetection: Boolean
        get() = state.notifyOnAutoDetection
        set(value) { state.notifyOnAutoDetection = value }

    var syncTabNames: Boolean
        get() = state.syncTabNames
        set(value) { state.syncTabNames = value }

    var customClaudeHomePath: String?
        get() = state.customClaudeHomePath
        set(value) { state.customClaudeHomePath = value }

    companion object {
        fun getInstance(): SettingsService {
            return ApplicationManager.getApplication().getService(SettingsService::class.java)
        }
    }
}
