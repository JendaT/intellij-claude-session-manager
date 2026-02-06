package com.github.jendalen.claudesessionmanager.settings

import com.github.jendalen.claudesessionmanager.model.PluginSettings
import com.github.jendalen.claudesessionmanager.services.ClaudeHookManager
import com.github.jendalen.claudesessionmanager.services.SettingsService
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Settings page for Claude Session Manager under Tools menu.
 */
class PluginSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settingsService = SettingsService.getInstance()
    private val hookManager = ClaudeHookManager.getInstance()

    // UI state
    private var claudeCommand = settingsService.claudeCommand
    private var autoResumeEnabled = settingsService.autoResumeEnabled
    private var autoResumeDelayMs = settingsService.autoResumeDelayMs.toString()
    private var notifyOnSessionChange = settingsService.getSettings().notifyOnSessionChange
    private var notifyOnAutoDetection = settingsService.getSettings().notifyOnAutoDetection
    private var syncTabNames = settingsService.syncTabNames
    private var customClaudeHomePath = settingsService.customClaudeHomePath ?: ""

    // Integration UI components (need to be refreshed)
    private var statusLabel: JBLabel? = null
    private var installButton: JButton? = null
    private var revertButton: JButton? = null
    private var detailsArea: JBTextArea? = null

    override fun getDisplayName(): String = "Claude Session Manager"

    override fun createComponent(): JComponent {
        panel = panel {
            // Integration Status - most prominent section
            group("Claude Code Integration") {
                row {
                    statusLabel = JBLabel().apply {
                        border = JBUI.Borders.empty(4)
                    }
                    cell(statusLabel!!)
                }
                row {
                    installButton = JButton("Install Hooks").apply {
                        addActionListener { installHooks() }
                    }
                    cell(installButton!!)

                    revertButton = JButton("Revert Hooks").apply {
                        addActionListener { revertHooks() }
                    }
                    cell(revertButton!!)
                }
                row {
                    comment("The plugin requires hooks in Claude Code to detect session changes reliably.")
                }

                collapsibleGroup("Integration Details (click to expand)") {
                    row {
                        detailsArea = JBTextArea(12, 60).apply {
                            isEditable = false
                            font = JBUI.Fonts.smallFont()
                            background = JBColor.PanelBackground
                        }
                        cell(JBScrollPane(detailsArea))
                            .align(AlignX.FILL)
                    }
                }
            }

            group("Auto-Resume") {
                row {
                    checkBox("Enable auto-resume on IDE startup")
                        .bindSelected(::autoResumeEnabled)
                }
                row("Delay before resume (ms):") {
                    textField()
                        .bindText(::autoResumeDelayMs)
                        .columns(8)
                        .comment("Wait time for terminal initialization (${PluginSettings.MIN_AUTO_RESUME_DELAY_MS}-${PluginSettings.MAX_AUTO_RESUME_DELAY_MS})")
                }
            }

            group("Notifications") {
                row {
                    checkBox("Show notification when session changes (e.g., /clear)")
                        .bindSelected(::notifyOnSessionChange)
                }
                row {
                    checkBox("Show notification when Claude session is auto-detected")
                        .bindSelected(::notifyOnAutoDetection)
                }
            }

            group("Tab Synchronization") {
                row {
                    checkBox("Sync Claude /rename to terminal tab name")
                        .bindSelected(::syncTabNames)
                }
            }

            group("Claude CLI") {
                row("Command:") {
                    textField()
                        .bindText(::claudeCommand)
                        .columns(20)
                        .comment("e.g., 'claude' or another alias which runs Claude with your preferred settings")
                }
                row("Home directory:") {
                    textField()
                        .bindText(::customClaudeHomePath)
                        .columns(40)
                        .comment("Leave empty for auto-detection (${ClaudePathResolver.getClaudeHome().absolutePath})")
                }
                row {
                    val claudeInstalled = ClaudePathResolver.isClaudeInstalled()
                    label(if (claudeInstalled) "Claude CLI detected" else "Claude CLI not detected")
                }
            }
        }

        // Initialize integration status display
        refreshIntegrationStatus()

        return panel!!
    }

    /**
     * Refresh the integration status UI.
     */
    private fun refreshIntegrationStatus() {
        val status = hookManager.getIntegrationStatus()

        // Update status label
        statusLabel?.apply {
            if (status.isInstalled) {
                text = "Installed"
                icon = AllIcons.General.InspectionsOK
                foreground = JBColor.GREEN.darker()
            } else {
                text = "Not Installed (action required)"
                icon = AllIcons.General.Warning
                foreground = JBColor.ORANGE
            }
        }

        // Update buttons
        installButton?.isEnabled = !status.isInstalled
        revertButton?.isEnabled = status.hookScriptExists || status.hookInSettings

        // Update details
        detailsArea?.text = buildString {
            appendLine("=== Integration Status ===")
            appendLine("Hook script: ${if (status.hookScriptExists) "Exists" else "Missing"}")
            appendLine("Hook in settings.json: ${if (status.hookInSettings) "Configured" else "Not configured"}")
            appendLine()
            appendLine("=== Paths ===")
            appendLine("Settings: ${status.settingsPath}")
            appendLine("Script: ${status.hookScriptPath}")

            if (status.issues.isNotEmpty()) {
                appendLine()
                appendLine("=== Issues ===")
                status.issues.forEach { appendLine("- $it") }
            }

            appendLine()
            appendLine("=== Hook Script Content ===")
            appendLine("When installed, this script will be created:")
            appendLine()
            appendLine(hookManager.hookScriptContent)

            appendLine()
            appendLine("=== Settings.json Entry ===")
            appendLine("When installed, this entry will be added to hooks.SessionStart:")
            appendLine()
            appendLine(hookManager.hookConfig)
        }
    }

    /**
     * Install Claude hooks with confirmation.
     */
    private fun installHooks() {
        val result = Messages.showOkCancelDialog(
            """
            This will:
            1. Create a hook script at ~/.claude/hooks/session-notify.sh
            2. Add a SessionStart hook entry to ~/.claude/settings.json

            The hook allows the plugin to detect when Claude sessions start or change.
            You can review the exact content in "Integration Details" above.

            Proceed with installation?
            """.trimIndent(),
            "Install Claude Hooks",
            "Install",
            "Cancel",
            Messages.getQuestionIcon()
        )

        if (result == Messages.OK) {
            val (success, error) = hookManager.installHooks()
            if (success) {
                Messages.showInfoMessage("Hooks installed successfully.", "Success")
            } else {
                Messages.showErrorDialog("Failed to install hooks: $error", "Error")
            }
            refreshIntegrationStatus()
        }
    }

    /**
     * Revert Claude hooks with confirmation.
     */
    private fun revertHooks() {
        val deleteScript = Messages.showYesNoCancelDialog(
            """
            This will remove the SessionStart hook entry from ~/.claude/settings.json.

            Do you also want to delete the hook script file?
            - Yes: Remove from settings AND delete the script file
            - No: Remove from settings only (keeps script for potential reuse)
            """.trimIndent(),
            "Revert Claude Hooks",
            "Yes, delete script",
            "No, keep script",
            "Cancel",
            Messages.getQuestionIcon()
        )

        if (deleteScript == Messages.CANCEL) return

        val (success, error) = hookManager.revertHooks(deleteScript = (deleteScript == Messages.YES))
        if (success) {
            Messages.showInfoMessage("Hooks reverted successfully.", "Success")
        } else {
            Messages.showWarningDialog("Revert result: $error", "Warning")
        }
        refreshIntegrationStatus()
    }

    override fun isModified(): Boolean {
        val settings = settingsService.getSettings()
        return claudeCommand != settings.claudeCommand ||
               autoResumeEnabled != settings.autoResumeEnabled ||
               autoResumeDelayMs != settings.autoResumeDelayMs.toString() ||
               notifyOnSessionChange != settings.notifyOnSessionChange ||
               notifyOnAutoDetection != settings.notifyOnAutoDetection ||
               syncTabNames != settings.syncTabNames ||
               customClaudeHomePath != (settings.customClaudeHomePath ?: "")
    }

    override fun apply() {
        val delayMs = autoResumeDelayMs.toLongOrNull() ?: PluginSettings.DEFAULT.autoResumeDelayMs

        settingsService.updateSettings(
            settingsService.getSettings().copy(
                claudeCommand = claudeCommand.ifBlank { "claude" },
                autoResumeEnabled = autoResumeEnabled,
                autoResumeDelayMs = delayMs,
                notifyOnSessionChange = notifyOnSessionChange,
                notifyOnAutoDetection = notifyOnAutoDetection,
                syncTabNames = syncTabNames,
                customClaudeHomePath = customClaudeHomePath.ifBlank { null }
            )
        )
    }

    override fun reset() {
        val settings = settingsService.getSettings()
        claudeCommand = settings.claudeCommand
        autoResumeEnabled = settings.autoResumeEnabled
        autoResumeDelayMs = settings.autoResumeDelayMs.toString()
        notifyOnSessionChange = settings.notifyOnSessionChange
        notifyOnAutoDetection = settings.notifyOnAutoDetection
        syncTabNames = settings.syncTabNames
        customClaudeHomePath = settings.customClaudeHomePath ?: ""
        panel?.reset()
        refreshIntegrationStatus()
    }

    override fun disposeUIResources() {
        panel = null
        statusLabel = null
        installButton = null
        revertButton = null
        detailsArea = null
    }
}
