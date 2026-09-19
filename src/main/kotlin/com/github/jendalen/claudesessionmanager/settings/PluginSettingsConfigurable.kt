package com.github.jendalen.claudesessionmanager.settings

import com.github.jendalen.claudesessionmanager.model.PluginSettings
import com.github.jendalen.claudesessionmanager.services.ClaudeHookManager
import com.github.jendalen.claudesessionmanager.services.ClaudeTitleSettingsService
import com.github.jendalen.claudesessionmanager.services.OptionAsMetaKeyGuardService
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

    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(PluginSettingsConfigurable::class.java)

    private var panel: DialogPanel? = null
    private val settingsService = SettingsService.getInstance()
    private val hookManager = ClaudeHookManager.getInstance()
    private val titleSettings = ClaudeTitleSettingsService.getInstance()

    // UI state
    private var claudeCommand = settingsService.claudeCommand
    private var autoResumeEnabled = settingsService.autoResumeEnabled
    private var autoResumeDelayMs = settingsService.autoResumeDelayMs.toString()
    private var notifyOnSessionChange = settingsService.getSettings().notifyOnSessionChange
    private var notifyOnAutoDetection = settingsService.getSettings().notifyOnAutoDetection
    private var syncTabNames = settingsService.syncTabNames
    private var customClaudeHomePath = settingsService.customClaudeHomePath ?: ""
    private var forceOptionAsMetaKeyOff = settingsService.forceOptionAsMetaKeyOff
    private var protectTabNames = settingsService.protectTabNames

    // File-backed (~/.claude/settings.json), not a plugin-persisted setting.
    private var disableClaudeTabTitle = titleSettings.isTitleDisabled()
    // isModified() runs on the EDT after every UI event in the dialog; comparing against
    // a live read of ~/.claude/settings.json put a file parse behind each keystroke.
    private var initialDisableClaudeTabTitle = disableClaudeTabTitle

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
                row {
                    checkBox("Protect bound tab names in this IDE (ignore Claude Code's renaming)")
                        .bindSelected(::protectTabNames)
                        .comment(
                            "IDE-side: tabs with a session binding ignore the terminal-title escape " +
                                "sequence Claude Code emits, so your tab names stay put. Does not change " +
                                "any Claude Code settings. To disable these titles for ALL terminal tabs, " +
                                "turn off the IDE's Advanced Setting \"Show application title\" " +
                                "(terminal.show.application.title)."
                        )
                }
                row {
                    checkBox("Keep my custom tab names everywhere (disable Claude Code's tab renaming globally)")
                        .bindSelected(::disableClaudeTabTitle)
                        .comment(
                            "Sets ${ClaudeTitleSettingsService.ENV_KEY}=1 in ~/.claude/settings.json. " +
                                "Affects all terminals (including outside the IDE). Session binding works " +
                                "either way."
                        )
                }
            }

            group("Terminal Workarounds") {
                row {
                    checkBox("Keep \"Use Option as Meta Key\" off")
                        .bindSelected(::forceOptionAsMetaKeyOff)
                        .comment("Reverts the setting when Claude Code enables it (macOS only)")
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
            appendLine("Hook script: ${if (!status.hookScriptExists) "Missing" else if (status.hookScriptCurrent) "Current" else "Outdated"}")
            appendLine("Hook in settings.json: ${if (status.hookInSettings) "Configured for all events" else "Missing for: ${status.missingEvents.joinToString(", ")}"}")
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
            appendLine("=== Settings.json Entries ===")
            appendLine("When installed, these entries are merged into ~/.claude/settings.json (other hooks are left untouched):")
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
            1. Create (or update) the hook script at ~/.claude/hooks/session-notify.sh
            2. Add hook entries to ~/.claude/settings.json for SessionStart, UserPromptSubmit,
               PreToolUse, Notification (permission prompts), Stop and SessionEnd.
               Existing entries of other tools are kept.

            The hooks let the plugin detect sessions and keep a per-session state file
            (idle / busy / waiting) under ~/.claude/ide-events/sessions/ for external tools.
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
                // Directory listing plus a read and a ProcessHandle lookup per stale
                // file - not for the EDT behind a modal dialog.
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                    com.github.jendalen.claudesessionmanager.services.SessionRegistryService.getInstance().sweep()
                }
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
            This will remove the plugin's hook entries from ~/.claude/settings.json.

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
        // Let the panel check if UI values differ from the bound properties,
        // then compare the bound properties against stored settings.
        return panel?.isModified() == true || run {
            val settings = settingsService.getSettings()
            claudeCommand != settings.claudeCommand ||
                autoResumeEnabled != settings.autoResumeEnabled ||
                autoResumeDelayMs != settings.autoResumeDelayMs.toString() ||
                notifyOnSessionChange != settings.notifyOnSessionChange ||
                notifyOnAutoDetection != settings.notifyOnAutoDetection ||
                syncTabNames != settings.syncTabNames ||
                customClaudeHomePath != (settings.customClaudeHomePath ?: "") ||
                forceOptionAsMetaKeyOff != settings.forceOptionAsMetaKeyOff ||
                protectTabNames != settings.protectTabNames ||
                disableClaudeTabTitle != initialDisableClaudeTabTitle
        }
    }

    override fun apply() {
        // Transfer UI values to bound properties first
        panel?.apply()

        val delayMs = autoResumeDelayMs.toLongOrNull() ?: PluginSettings.DEFAULT.autoResumeDelayMs
        val protectTabNamesChanged = protectTabNames != settingsService.protectTabNames

        settingsService.updateSettings(
            settingsService.getSettings().copy(
                claudeCommand = claudeCommand.ifBlank { "claude" },
                autoResumeEnabled = autoResumeEnabled,
                autoResumeDelayMs = delayMs,
                notifyOnSessionChange = notifyOnSessionChange,
                notifyOnAutoDetection = notifyOnAutoDetection,
                syncTabNames = syncTabNames,
                customClaudeHomePath = customClaudeHomePath.ifBlank { null },
                forceOptionAsMetaKeyOff = forceOptionAsMetaKeyOff,
                protectTabNames = protectTabNames
            )
        )

        // Notify the guard service so it can act immediately if just enabled
        OptionAsMetaKeyGuardService.getInstance().onGuardSettingChanged(forceOptionAsMetaKeyOff)

        // Apply/undo title protection on already-bound tabs immediately - otherwise the
        // toggle only takes effect on the next session event, which reads as broken.
        if (protectTabNamesChanged) {
            for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                try {
                    com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
                        .updateTitleProtectionForBoundTabs(project, protectTabNames)
                } catch (e: Exception) {
                    // Non-fatal: protection still applies lazily on the next session event,
                    // but the toggle appearing to do nothing must be diagnosable.
                    logger.warn("Could not update title protection for '${project.name}': ${e.message}")
                }
            }
        }

        // Apply the Claude Code tab-title toggle to ~/.claude/settings.json if it changed.
        if (disableClaudeTabTitle != titleSettings.isTitleDisabled()) {
            val (ok, error) = titleSettings.setTitleDisabled(disableClaudeTabTitle)
            if (!ok) {
                Messages.showWarningDialog(
                    "Could not update ~/.claude/settings.json: $error",
                    "Claude Session Manager"
                )
            }
        }
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
        forceOptionAsMetaKeyOff = settings.forceOptionAsMetaKeyOff
        protectTabNames = settings.protectTabNames
        disableClaudeTabTitle = titleSettings.isTitleDisabled()
        initialDisableClaudeTabTitle = disableClaudeTabTitle
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
