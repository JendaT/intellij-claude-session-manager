package com.github.jendalen.claudesessionmanager.teams.settings

import com.github.jendalen.claudesessionmanager.teams.model.TeamsSettings
import com.github.jendalen.claudesessionmanager.teams.services.TeamsSettingsService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

/**
 * Settings page for Agent Teams under Tools > Claude Session Manager.
 *
 * The master toggle "Enable Agent Teams" controls the entire page.
 * When unchecked, all other settings are greyed out and the feature is dormant.
 */
class TeamsSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settingsService = TeamsSettingsService.getInstance()

    // UI state
    private var enableTeamDetection = settingsService.getSettings().enableTeamDetection
    private var createTabsForInProcessTeammates = settingsService.getSettings().createTabsForInProcessTeammates
    private var nativeTeammateSpawning = settingsService.getSettings().nativeTeammateSpawning
    private var groupTeammateTabs = settingsService.getSettings().groupTeammateTabs
    private var autoFocusNewTeammateTabs = settingsService.getSettings().autoFocusNewTeammateTabs
    private var showDashboardOnTeamCreate = settingsService.getSettings().showDashboardOnTeamCreate
    private var maxMessagesInLog = settingsService.getSettings().maxMessagesInLog.toString()
    private var truncateMessageLength = settingsService.getSettings().truncateMessageLength.toString()
    private var notifyOnTeamCreate = settingsService.getSettings().notifyOnTeamCreate
    private var notifyOnTaskComplete = settingsService.getSettings().notifyOnTaskComplete
    private var notifyOnTeammateError = settingsService.getSettings().notifyOnTeammateError
    private var notifyOnTeammateShutdown = settingsService.getSettings().notifyOnTeammateShutdown
    private var autoCloseTabsOnTeamDelete = settingsService.getSettings().autoCloseTabsOnTeamDelete

    override fun getDisplayName(): String = "Agent Teams"

    override fun createComponent(): JComponent {
        panel = panel {
            // Master toggle - controls the entire feature
            lateinit var masterToggle: Cell<javax.swing.JCheckBox>

            row {
                masterToggle = checkBox("Enable Agent Teams (experimental)")
                    .bindSelected(::enableTeamDetection)
                    .comment("Watch ~/.claude/teams/ for new agent teams and manage teammate terminal tabs")
            }

            group("Terminal Tabs") {
                row {
                    checkBox("Create terminal tabs for in-process teammates")
                        .bindSelected(::createTabsForInProcessTeammates)
                        .comment("Tmux teammates always get tabs; this adds tabs for in-process (informational only)")
                }
                row {
                    checkBox("EXPERIMENTAL: Native teammate spawning")
                        .bindSelected(::nativeTeammateSpawning)
                        .comment("Spawn teammate processes directly instead of using existing terminals")
                }
                row {
                    checkBox("Group teammate tabs together")
                        .bindSelected(::groupTeammateTabs)
                }
                row {
                    checkBox("Auto-focus new teammate tabs")
                        .bindSelected(::autoFocusNewTeammateTabs)
                }
                row {
                    checkBox("Auto-close teammate tabs when team dissolves")
                        .bindSelected(::autoCloseTabsOnTeamDelete)
                }
            }.enabledIf(masterToggle.selected)

            group("Dashboard") {
                row {
                    checkBox("Show dashboard when a new team is created")
                        .bindSelected(::showDashboardOnTeamCreate)
                }
                row("Max messages in log:") {
                    textField()
                        .bindText(::maxMessagesInLog)
                        .columns(6)
                        .comment("${TeamsSettings.MIN_MESSAGES_IN_LOG}-${TeamsSettings.MAX_MESSAGES_IN_LOG}")
                }
                row("Truncate message length:") {
                    textField()
                        .bindText(::truncateMessageLength)
                        .columns(6)
                        .comment("${TeamsSettings.MIN_TRUNCATE_LENGTH}-${TeamsSettings.MAX_TRUNCATE_LENGTH} characters")
                }
            }.enabledIf(masterToggle.selected)

            group("Notifications") {
                row {
                    checkBox("Notify when a team is created")
                        .bindSelected(::notifyOnTeamCreate)
                }
                row {
                    checkBox("Notify when a task completes")
                        .bindSelected(::notifyOnTaskComplete)
                }
                row {
                    checkBox("Notify on teammate error")
                        .bindSelected(::notifyOnTeammateError)
                }
                row {
                    checkBox("Notify on teammate shutdown")
                        .bindSelected(::notifyOnTeammateShutdown)
                }
            }.enabledIf(masterToggle.selected)
        }

        return panel!!
    }

    override fun isModified(): Boolean {
        // DialogPanel.isModified() compares current UI state against bound property values.
        // Do NOT manually compare local vars - they're only updated on panel.apply(),
        // so manual comparison always returns false (stale vars == saved settings).
        return panel?.isModified() ?: false
    }

    override fun apply() {
        // Transfer UI values to bound properties before saving
        panel?.apply()

        val messages = maxMessagesInLog.toIntOrNull() ?: TeamsSettings.DEFAULT.maxMessagesInLog
        val truncate = truncateMessageLength.toIntOrNull() ?: TeamsSettings.DEFAULT.truncateMessageLength

        settingsService.updateSettings(
            TeamsSettings(
                enableTeamDetection = enableTeamDetection,
                createTabsForInProcessTeammates = createTabsForInProcessTeammates,
                nativeTeammateSpawning = nativeTeammateSpawning,
                groupTeammateTabs = groupTeammateTabs,
                autoFocusNewTeammateTabs = autoFocusNewTeammateTabs,
                showDashboardOnTeamCreate = showDashboardOnTeamCreate,
                maxMessagesInLog = messages,
                truncateMessageLength = truncate,
                notifyOnTeamCreate = notifyOnTeamCreate,
                notifyOnTaskComplete = notifyOnTaskComplete,
                notifyOnTeammateError = notifyOnTeammateError,
                notifyOnTeammateShutdown = notifyOnTeammateShutdown,
                autoCloseTabsOnTeamDelete = autoCloseTabsOnTeamDelete
            )
        )
    }

    override fun reset() {
        val settings = settingsService.getSettings()
        enableTeamDetection = settings.enableTeamDetection
        createTabsForInProcessTeammates = settings.createTabsForInProcessTeammates
        nativeTeammateSpawning = settings.nativeTeammateSpawning
        groupTeammateTabs = settings.groupTeammateTabs
        autoFocusNewTeammateTabs = settings.autoFocusNewTeammateTabs
        showDashboardOnTeamCreate = settings.showDashboardOnTeamCreate
        maxMessagesInLog = settings.maxMessagesInLog.toString()
        truncateMessageLength = settings.truncateMessageLength.toString()
        notifyOnTeamCreate = settings.notifyOnTeamCreate
        notifyOnTaskComplete = settings.notifyOnTaskComplete
        notifyOnTeammateError = settings.notifyOnTeammateError
        notifyOnTeammateShutdown = settings.notifyOnTeammateShutdown
        autoCloseTabsOnTeamDelete = settings.autoCloseTabsOnTeamDelete
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
