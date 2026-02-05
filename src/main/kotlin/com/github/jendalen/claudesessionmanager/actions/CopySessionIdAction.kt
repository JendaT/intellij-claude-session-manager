package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Action to copy the bound session ID to clipboard.
 */
class CopySessionIdAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val context = TerminalHelper.getTerminalContext(e)
        if (context == null) {
            showNotification(project, "No terminal tab selected", NotificationType.WARNING)
            return
        }

        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val bindingService = BindingService.getInstance(project)

        val binding = bindingService.findBindingForTab(tabIdentifier)
        if (binding == null) {
            showNotification(project, "This tab has no session binding", NotificationType.WARNING)
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(binding.currentSessionId))
        showNotification(project, "Session ID copied to clipboard", NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Always show if in terminal context
        if (!TerminalHelper.isInTerminalContext(e)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isVisible = true

        val context = TerminalHelper.getTerminalContext(e)
        if (context == null) {
            e.presentation.isEnabled = false
            return
        }

        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val bindingService = BindingService.getInstance(project)
        val hasBinding = bindingService.findBindingForTab(tabIdentifier) != null

        e.presentation.isEnabled = hasBinding
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification(message, type)
            .notify(project)
    }
}
