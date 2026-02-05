package com.github.jendalen.claudesessionmanager.terminal

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.services.ClaudeSessionService
import com.github.jendalen.claudesessionmanager.ui.SessionPickerDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsActions
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.ui.OpenPredefinedTerminalActionProvider
import javax.swing.Icon

/**
 * Provides "Claude Session" option in the terminal's predefined sessions dropdown.
 */
class ClaudeSessionTerminalProvider : OpenPredefinedTerminalActionProvider {

    override fun listOpenPredefinedTerminalActions(project: Project): List<AnAction> {
        // Check if there are any sessions available for this project
        val sessionService = ClaudeSessionService.getInstance(project)
        val sessions = sessionService.getSessions()

        if (sessions.isEmpty()) {
            return emptyList()
        }

        return listOf(OpenClaudeSessionAction())
    }

    /**
     * Action that opens a new terminal with a Claude session.
     */
    private class OpenClaudeSessionAction : AnAction() {
        private val logger = Logger.getInstance(OpenClaudeSessionAction::class.java)

        init {
            templatePresentation.text = "Claude Session"
            templatePresentation.description = "Open a terminal with a Claude session"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return

            // Show session picker
            val selectedSession = SessionPickerDialog.show(project) ?: return

            // Create new terminal tab
            val tabName = selectedSession.displaySummary().take(30)
            val widget = TerminalHelper.createNewTerminalTab(project, tabName)

            if (widget == null) {
                logger.warn("Failed to create terminal tab")
                return
            }

            // Create binding
            val tabIdentifier = TerminalHelper.createTabIdentifier(widget)
            val bindingService = BindingService.getInstance(project)
            bindingService.createBinding(tabIdentifier, selectedSession.sessionId)

            // Resume the session after terminal initializes
            Alarm(Alarm.ThreadToUse.SWING_THREAD).addRequest({
                TerminalHelper.resumeClaudeSession(widget, selectedSession.sessionId)
                TerminalHelper.setClaudeIconOnTab(project, tabName)
                bindingService.findBindingForTab(tabIdentifier)?.let {
                    bindingService.markBindingResumed(it.id)
                }
            }, 500)

            logger.info("Created Claude session tab for ${selectedSession.sessionId}")
        }
    }
}
