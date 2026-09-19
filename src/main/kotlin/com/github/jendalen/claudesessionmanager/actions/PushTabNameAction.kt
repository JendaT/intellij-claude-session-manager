package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.BindingService
import com.github.jendalen.claudesessionmanager.services.SessionTitleSyncService
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Renames the bound Claude session to this tab's name, now. The automatic push after an
 * IDE rename only happens while the session is idle; this is the retry for a rename made
 * while it was busy, and the manual way to name a session after the tab regardless of
 * the outbound setting - the user clicked, so the idle check is the only gate left.
 */
class PushTabNameAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = TerminalHelper.getTerminalContext(e)
        val content = context?.content
        if (context == null || content == null) {
            NotificationHelper.show(project, "No terminal tab selected", NotificationType.WARNING)
            return
        }
        val binding = BindingService.getInstance(project).findBindingForTab(TerminalHelper.createTabIdentifier(context))
        if (binding == null) {
            NotificationHelper.show(project, "This tab has no session binding", NotificationType.WARNING)
            return
        }
        // The persistent name is what a restart brings back and what the registry shows;
        // the display name may still carry an OSC title on an unprotected tab.
        val name = TerminalHelper.getPersistentName(project, content) ?: context.tabName
        val sync = SessionTitleSyncService.getInstance(project)
        sync.pushTabName(binding, content, name) { sync.report(it, name) }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !TerminalHelper.isInTerminalContext(e)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        val context = TerminalHelper.getTerminalContext(e)
        e.presentation.isEnabled = context != null &&
            BindingService.getInstance(project).findBindingForTab(TerminalHelper.createTabIdentifier(context)) != null
    }
}
