package com.github.jendalen.claudesessionmanager.teams.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Toggles visibility of the Claude Teams dashboard tool window.
 *
 * Keyboard shortcut: Ctrl+Alt+D (registered in plugin.xml).
 */
class ToggleDashboardAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Teams") ?: return

        if (toolWindow.isVisible) {
            toolWindow.hide()
        } else {
            // Show without stealing focus from the terminal
            toolWindow.show { }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
