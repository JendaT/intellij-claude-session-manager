package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.services.ClaudeSessionService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Displays current session info at the top of the Claude Session context menu.
 * Shows session summary or ID; clicking copies the session ID to clipboard.
 * When no session is bound, shows "No session bound" (disabled).
 */
class CurrentSessionInfoAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = TerminalHelper.getTerminalContext(e) ?: return
        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val binding = BindingService.getInstance(project).findBindingForTab(tabIdentifier) ?: return

        CopyPasteManager.getInstance().setContents(StringSelection(binding.currentSessionId))
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !TerminalHelper.isInTerminalContext(e)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isVisible = true

        val context = TerminalHelper.getTerminalContext(e)
        if (context == null) {
            e.presentation.text = "No session bound"
            e.presentation.isEnabled = false
            return
        }

        val tabIdentifier = TerminalHelper.createTabIdentifier(context)
        val binding = BindingService.getInstance(project).findBindingForTab(tabIdentifier)

        if (binding == null) {
            e.presentation.text = "No session bound"
            e.presentation.isEnabled = false
            return
        }

        // Try to get session metadata for a nice display name
        val sessionService = ClaudeSessionService.getInstance(project)
        val session = sessionService.findSessionById(binding.currentSessionId)

        val summary = session?.displaySummary()
        val shortId = binding.currentSessionId.take(8)

        if (summary != null && summary.isNotBlank()) {
            e.presentation.text = "$summary ($shortId...)"
        } else {
            e.presentation.text = "Session: $shortId..."
        }

        e.presentation.description = "Click to copy session ID: ${binding.currentSessionId}"
        e.presentation.isEnabled = true
    }
}
