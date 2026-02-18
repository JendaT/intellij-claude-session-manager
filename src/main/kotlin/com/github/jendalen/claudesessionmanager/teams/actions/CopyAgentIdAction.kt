package com.github.jendalen.claudesessionmanager.teams.actions

import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Copies the selected teammate's agent ID to the clipboard.
 * Available from the teammate tab context menu.
 */
class CopyAgentIdAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = AgentTeamsManager.getInstance(project)

        // Find the member associated with the currently selected terminal tab
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Terminal") ?: return
        val content = toolWindow.contentManager.selectedContent ?: return
        val member = manager.getMemberForTab(content) ?: return

        CopyPasteManager.getInstance().setContents(StringSelection(member.agentId))
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Terminal")
        val content = toolWindow?.contentManager?.selectedContent
        e.presentation.isEnabledAndVisible = content != null &&
                AgentTeamsManager.isTeamTab(content)
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
