package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.services.ClaudeSessionService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Action to resume the bound Claude session in the current terminal.
 */
class ResumeSessionAction : AnAction() {

    private val logger = Logger.getInstance(ResumeSessionAction::class.java)

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

        // Check if session still exists
        val sessionService = ClaudeSessionService.getInstance(project)
        if (!sessionService.sessionExists(binding.currentSessionId)) {
            showNotification(
                project,
                "Session no longer exists. Consider rebinding to a new session.",
                NotificationType.WARNING
            )
            return
        }

        // Resume the session using improved terminal execution
        if (!TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)) {
            showNotification(project, "Failed to execute resume command", NotificationType.ERROR)
            return
        }

        // Mark as resumed
        bindingService.markBindingResumed(binding.id)

        logger.info("Resumed session ${binding.currentSessionId} in terminal '${tabIdentifier.name}'")
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
        NotificationHelper.show(project, message, type)
    }
}
