package com.github.jendalen.claudesessionmanager.teams.actions

import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * Opens a popup picker showing all team members. Selecting one navigates
 * to their terminal tab.
 *
 * Keyboard shortcut: Ctrl+Alt+T (registered in plugin.xml).
 */
class NavigateToTeammateAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = AgentTeamsManager.getInstance(project)
        val team = manager.getActiveTeam() ?: return

        val members = team.members

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(members)
            .setTitle("Navigate to Teammate")
            .setRenderer(object : ColoredListCellRenderer<TeamMember>() {
                override fun customizeCellRenderer(
                    list: JList<out TeamMember>,
                    value: TeamMember,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.agentType}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (value.model.isNotBlank()) {
                        append("  (${value.model})", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
            })
            .setItemChosenCallback { member ->
                manager.navigateToMember(member.agentId)
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
                AgentTeamsManager.getInstance(project).getActiveTeam() != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
