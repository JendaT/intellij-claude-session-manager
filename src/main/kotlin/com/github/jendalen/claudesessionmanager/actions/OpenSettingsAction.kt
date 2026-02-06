package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.ClaudeHookManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Action to open Claude Session Manager settings directly from the context menu.
 * Shows "(action required)" indicator when hooks are not installed.
 */
class OpenSettingsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "Claude Session Manager"
        )
    }

    override fun update(e: AnActionEvent) {
        val hookManager = ClaudeHookManager.getInstance()
        val status = hookManager.getIntegrationStatus()

        e.presentation.text = if (status.isInstalled) {
            "Settings..."
        } else {
            "Settings... (action required)"
        }

        e.presentation.isEnabledAndVisible = true
    }
}
