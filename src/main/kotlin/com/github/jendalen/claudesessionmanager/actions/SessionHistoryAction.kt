package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.model.SessionHistoryEntry
import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Action group that shows session history as a submenu.
 */
class SessionHistoryAction : ActionGroup() {

    private val logger = Logger.getInstance(SessionHistoryAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()

        val context = TerminalHelper.getTerminalContext(e) ?: return emptyArray()
        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val bindingService = BindingService.getInstance(project)

        val binding = bindingService.findBindingForTab(tabIdentifier) ?: return emptyArray()

        if (binding.sessionHistory.isEmpty()) {
            return arrayOf(NoHistoryAction())
        }

        return binding.sessionHistory.mapIndexed { index, entry ->
            RestoreSessionAction(binding.id, entry, index)
        }.toTypedArray()
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

        val context = TerminalHelper.getTerminalContext(e)
        if (context == null) {
            e.presentation.isVisible = true
            e.presentation.isEnabled = false
            return
        }

        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val bindingService = BindingService.getInstance(project)
        val binding = bindingService.findBindingForTab(tabIdentifier)

        e.presentation.isVisible = true
        e.presentation.isEnabled = binding != null && binding.sessionHistory.isNotEmpty()
    }

    /**
     * Placeholder action when there's no history.
     */
    private class NoHistoryAction : AnAction("No previous sessions") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            // No-op
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
    }

    /**
     * Action to restore a specific session from history.
     */
    private class RestoreSessionAction(
        private val bindingId: String,
        private val historyEntry: SessionHistoryEntry,
        private val index: Int
    ) : AnAction() {

        private val logger = Logger.getInstance(RestoreSessionAction::class.java)
        private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(ZoneId.systemDefault())

        init {
            val date = dateFormatter.format(Instant.ofEpochMilli(historyEntry.replacedAt))
            templatePresentation.text = "${historyEntry.sessionId.take(8)}... ($date)"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return

            val bindingService = BindingService.getInstance(project)
            val binding = bindingService.findBindingById(bindingId)

            if (binding == null) {
                showNotification(project, "Binding no longer exists", NotificationType.ERROR)
                return
            }

            // Swap: current becomes history, history becomes current
            // Claude CLI handles invalid IDs gracefully with an error in terminal
            bindingService.updateBindingSession(bindingId, historyEntry.sessionId)

            // Resume the session using context
            val context = TerminalHelper.getTerminalContext(e)
            if (context != null) {
                val command = TerminalHelper.buildResumeCommand(project, historyEntry.sessionId)
                TerminalHelper.executeCommand(context, command)
            }

            showNotification(
                project,
                "Restored session ${historyEntry.sessionId.take(8)}...",
                NotificationType.INFORMATION
            )
            logger.info("Restored session ${historyEntry.sessionId} from history")
        }

        private fun showNotification(project: Project, message: String, type: NotificationType) {
            NotificationHelper.show(project, message, type)
        }
    }
}
