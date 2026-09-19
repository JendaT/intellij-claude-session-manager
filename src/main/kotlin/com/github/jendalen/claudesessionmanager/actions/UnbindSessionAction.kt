package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Action to unbind a Claude session from the current terminal tab.
 */
class UnbindSessionAction : AnAction() {

    private val logger = Logger.getInstance(UnbindSessionAction::class.java)

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

        bindingService.deleteBinding(binding.id)

        // Undo every side effect of binding, not only the icon. The decoration mark and
        // title protection lived on: a later rebind of the same tab saw "already
        // decorated" and got neither icon nor badge, while the title listener kept
        // stripping OSC titles from a tab that no longer had a session.
        context.content?.let {
            TerminalHelper.removeClaudeIconFromContent(it)
            TerminalHelper.unprotectTabTitle(project, it)
            it.putUserData(TerminalHelper.DECORATED_KEY, null)
        }

        showNotification(project, "Session binding removed", NotificationType.INFORMATION)
        logger.info("Unbound terminal '${tabIdentifier.name}' from session ${binding.currentSessionId}")
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
