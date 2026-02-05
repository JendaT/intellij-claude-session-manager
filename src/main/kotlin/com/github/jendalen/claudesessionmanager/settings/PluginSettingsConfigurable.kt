package com.github.jendalen.claudesessionmanager.settings

import com.github.jendalen.claudesessionmanager.model.PluginSettings
import com.github.jendalen.claudesessionmanager.services.SettingsService
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.columns
import javax.swing.JComponent

/**
 * Settings page for Claude Session Manager under Tools menu.
 */
class PluginSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settingsService = SettingsService.getInstance()

    // UI state
    private var claudeCommand = settingsService.claudeCommand
    private var autoResumeEnabled = settingsService.autoResumeEnabled
    private var autoResumeDelayMs = settingsService.autoResumeDelayMs.toString()
    private var notifyOnSessionChange = settingsService.getSettings().notifyOnSessionChange
    private var notifyOnAutoDetection = settingsService.getSettings().notifyOnAutoDetection
    private var syncTabNames = settingsService.syncTabNames
    private var customClaudeHomePath = settingsService.customClaudeHomePath ?: ""

    override fun getDisplayName(): String = "Claude Session Manager"

    override fun createComponent(): JComponent {
        panel = panel {
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
                        .comment("Claude CLI command (e.g., 'claude' or 'claude-x')")
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
        return panel!!
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
    }

    override fun disposeUIResources() {
        panel = null
    }
}
