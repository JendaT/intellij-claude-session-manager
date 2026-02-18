package com.github.jendalen.claudesessionmanager.teams.actions

import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Quick-navigates to the team lead's terminal tab.
 *
 * Keyboard shortcut: Ctrl+Alt+1 (registered in plugin.xml).
 */
class NavigateToTeamLeadAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = AgentTeamsManager.getInstance(project)
        val team = manager.getActiveTeam() ?: return
        val leadId = team.leadAgentId

        if (leadId.isNotBlank() && manager.getTabForMember(leadId) != null) {
            manager.navigateToMember(leadId)
        } else {
            // Fallback: show the terminal tool window
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal") ?: return
            toolWindow.show()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                AgentTeamsManager.getInstance(project).getActiveTeam() != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
