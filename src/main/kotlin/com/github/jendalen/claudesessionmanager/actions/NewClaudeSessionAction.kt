package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.ui.SessionPickerDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Action to create a new terminal tab with a Claude session.
 * This appears in the terminal's "New Session" dropdown.
 */
class NewClaudeSessionAction : AnAction() {

    private val logger = Logger.getInstance(NewClaudeSessionAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Show session picker
        val selectedSession = SessionPickerDialog.show(project)
        if (selectedSession == null) {
            logger.debug("Session selection cancelled")
            return
        }

        // Create new terminal tab
        val tabName = selectedSession.displaySummary().take(30)
        val widget = TerminalHelper.createNewTerminalTab(project, tabName)

        if (widget == null) {
            showNotification(project, "Failed to create terminal tab", NotificationType.ERROR)
            return
        }

        // Create binding
        val tabIdentifier = TerminalHelper.createTabIdentifier(widget)
        val bindingService = BindingService.getInstance(project)
        bindingService.createBinding(tabIdentifier, selectedSession.sessionId)

        // Resume the session after a short delay to let terminal initialize
        com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD).addRequest({
            TerminalHelper.resumeClaudeSession(widget, selectedSession.sessionId)
            bindingService.findBindingForTab(tabIdentifier)?.let {
                bindingService.markBindingResumed(it.id)
            }
        }, 500)

        logger.info("Created new Claude tab for session ${selectedSession.sessionId}")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification(message, type)
            .notify(project)
    }
}
