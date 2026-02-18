package com.github.jendalen.claudesessionmanager.teams.ui

import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Panel showing team members with clickable navigation to their terminal tabs.
 */
class MembersPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<TeamMember>()
    private val memberList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MemberCellRenderer()
    }

    init {
        add(JBScrollPane(memberList), BorderLayout.CENTER)

        memberList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = memberList.selectedValue ?: return
                    AgentTeamsManager.getInstance(project).navigateToMember(selected.agentId)
                }
            }
        })
    }

    fun updateMembers(members: List<TeamMember>) {
        listModel.clear()
        members.forEach { listModel.addElement(it) }
    }

    private class MemberCellRenderer : ColoredListCellRenderer<TeamMember>() {
        override fun customizeCellRenderer(
            list: JList<out TeamMember>,
            value: TeamMember,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            val statusIcon = "[*]"
            append("$statusIcon ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ${value.agentType}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            if (value.model.isNotBlank()) {
                toolTipText = "Model: ${value.model}"
            }
        }
    }
}
