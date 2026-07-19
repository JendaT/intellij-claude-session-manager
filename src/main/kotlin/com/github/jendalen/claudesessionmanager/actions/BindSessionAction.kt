package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.ui.SessionPickerDialog
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

/**
 * Action to bind a Claude session to the current terminal tab.
 * Shows a session picker dialog and creates the binding.
 */
class BindSessionAction : AnAction() {

    private val logger = Logger.getInstance(BindSessionAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get the terminal context (works with both terminal engines)
        val context = TerminalHelper.getTerminalContext(e)
        if (context == null) {
            showNotification(project, "No terminal tab selected", NotificationType.WARNING)
            return
        }

        // Show session picker
        val selectedSession = SessionPickerDialog.show(project) ?: return

        // Create tab identifier from context
        val tabIdentifier = TerminalHelper.createTabIdentifier(context)

        // Create binding
        val bindingService = BindingService.getInstance(project)

        // Check if already bound
        val existingBinding = bindingService.findBindingForTab(tabIdentifier)
        if (existingBinding != null) {
            // Update existing binding
            bindingService.updateBindingSession(existingBinding.id, selectedSession.sessionId)
        } else {
            // Create new binding
            bindingService.createBinding(tabIdentifier, selectedSession.sessionId)
        }

        // Set Claude icon and title protection on the tab (on EDT)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            context.content?.let {
                TerminalHelper.setClaudeIconOnContent(it)
                TerminalHelper.protectTabTitleIfEnabled(project, it)
            }
        }

        // Execute resume command to load the session
        val success = TerminalHelper.executeCommandInTerminal(project, context, selectedSession.sessionId)

        if (success) {
            val binding = bindingService.findBindingForTab(tabIdentifier)
            binding?.let { bindingService.markBindingResumed(it.id) }
            showNotification(
                project,
                "Bound and resumed: ${selectedSession.displaySummary()}",
                NotificationType.INFORMATION
            )
        } else {
            showNotification(
                project,
                "Bound to session but failed to execute resume command. Try Resume Session manually.",
                NotificationType.WARNING
            )
        }

        logger.info("Bound terminal '${tabIdentifier.name}' to session ${selectedSession.sessionId}")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Enable if we're in a terminal context
        e.presentation.isVisible = true
        e.presentation.isEnabled = TerminalHelper.isInTerminalContext(e)
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationHelper.show(project, message, type)
    }
}
