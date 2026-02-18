package com.github.jendalen.claudesessionmanager.teams.ui

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.github.jendalen.claudesessionmanager.teams.services.FileSystemTeamWatcher
import com.github.jendalen.claudesessionmanager.teams.services.TeamEventListener
import com.github.jendalen.claudesessionmanager.teams.services.TeamStateParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Main dashboard panel for the Claude Teams tool window.
 *
 * Shows team members, tasks, and messages in a three-section vertical layout.
 * Subscribes to TeamWatcher events for live updates.
 */
class TeamDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), TeamEventListener, Disposable {

    private val membersPanel = MembersPanel(project)
    private val taskListPanel = TaskListPanel()
    private val messageLogPanel = MessageLogPanel()

    private val headerLabel = JBLabel("No Active Team").apply {
        border = JBUI.Borders.empty(8)
        horizontalAlignment = SwingConstants.CENTER
    }

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_DASHBOARD = "dashboard"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    init {
        // Empty state panel
        val emptyPanel = JPanel(BorderLayout()).apply {
            val label = JBLabel(
                "<html><center>No Active Team<br><br>" +
                        "Agent teams will appear here when Claude Code<br>" +
                        "starts a team session in the terminal.</center></html>"
            ).apply {
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(20)
            }
            add(label, BorderLayout.CENTER)
        }

        // Dashboard content
        val dashboardContent = JPanel(BorderLayout())

        // Header
        dashboardContent.add(headerLabel, BorderLayout.NORTH)

        // Three-way vertical split
        val topSplitter = JBSplitter(true, 0.35f).apply {
            firstComponent = membersPanel
            secondComponent = JBSplitter(true, 0.5f).apply {
                firstComponent = taskListPanel
                secondComponent = messageLogPanel
            }
        }
        dashboardContent.add(topSplitter, BorderLayout.CENTER)

        // Bottom actions
        val actionsPanel = JPanel().apply {
            border = JBUI.Borders.empty(4)

            val openLeadButton = JButton("Open Lead Terminal").apply {
                addActionListener { navigateToLead() }
            }
            add(openLeadButton)
        }
        dashboardContent.add(actionsPanel, BorderLayout.SOUTH)

        cardPanel.add(emptyPanel, CARD_EMPTY)
        cardPanel.add(dashboardContent, CARD_DASHBOARD)
        add(cardPanel, BorderLayout.CENTER)

        // Show empty state initially
        cardLayout.show(cardPanel, CARD_EMPTY)

        // Register as listener
        val watcher = FileSystemTeamWatcher.getInstance(project)
        watcher.addListener(this)

        // Check if there's already an active team
        val existingTeams = watcher.getActiveTeams()
        if (existingTeams.isNotEmpty()) {
            showTeam(existingTeams.first())
        }

        Disposer.register(project, this)
    }

    override fun onTeamEvent(event: TeamEvent) {
        ApplicationManager.getApplication().invokeLater {
            when (event) {
                is TeamEvent.TeamCreated -> showTeam(event.team)
                is TeamEvent.TeamUpdated -> showTeam(event.team)
                is TeamEvent.TeamDeleted -> showEmptyState()
                is TeamEvent.MemberAdded -> {
                    val team = AgentTeamsManager.getInstance(project).getActiveTeam()
                    if (team != null) {
                        membersPanel.updateMembers(team.members)
                    }
                }
                is TeamEvent.MemberRemoved -> {
                    val team = AgentTeamsManager.getInstance(project).getActiveTeam()
                    if (team != null) {
                        membersPanel.updateMembers(team.members)
                    }
                }
                is TeamEvent.TaskChanged -> {
                    val teamId = event.teamId
                    val tasks = TeamStateParser.getInstance(project).parseTasks(teamId)
                    taskListPanel.updateTasks(tasks)
                }
                is TeamEvent.MessageReceived -> {
                    messageLogPanel.addMessage(event.message)
                }
            }
        }
    }

    private fun showTeam(team: TeamModel) {
        val timeStr = if (team.createdAt > 0) {
            TIME_FORMAT.format(Instant.ofEpochMilli(team.createdAt))
        } else {
            "unknown"
        }
        headerLabel.text = "${team.name} (created $timeStr)"

        membersPanel.updateMembers(team.members)

        // Load tasks
        val teamId = team.teamId()
        val tasks = TeamStateParser.getInstance(project).parseTasks(teamId)
        taskListPanel.updateTasks(tasks)

        // Load messages for all agents
        val allInboxes = TeamStateParser.getInstance(project).parseAllInboxes(teamId)
        val allMessages = allInboxes.values.flatten().sortedBy { it.timestamp }
        messageLogPanel.setMessages(allMessages)

        cardLayout.show(cardPanel, CARD_DASHBOARD)
    }

    private fun showEmptyState() {
        headerLabel.text = "No Active Team"
        cardLayout.show(cardPanel, CARD_EMPTY)
    }

    private fun navigateToLead() {
        val manager = AgentTeamsManager.getInstance(project)
        val team = manager.getActiveTeam() ?: return
        val leadId = team.leadAgentId

        if (leadId.isNotBlank() && manager.getTabForMember(leadId) != null) {
            manager.navigateToMember(leadId)
        } else {
            // Fallback: just show the terminal tool window
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal") ?: return
            toolWindow.show()
        }
    }

    override fun dispose() {
        try {
            FileSystemTeamWatcher.getInstance(project).removeListener(this)
        } catch (e: Exception) {
            // Project may be disposed already
        }
    }
}
