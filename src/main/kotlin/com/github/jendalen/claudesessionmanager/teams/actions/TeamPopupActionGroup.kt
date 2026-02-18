package com.github.jendalen.claudesessionmanager.teams.actions

import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Action group for the "Claude Team" context menu popup.
 *
 * Visible and enabled whenever an active team is detected,
 * regardless of which terminal tab is selected. Individual
 * child actions handle their own per-tab enablement.
 */
class TeamPopupActionGroup : DefaultActionGroup() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible =
            AgentTeamsManager.getInstance(project).getActiveTeam() != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
