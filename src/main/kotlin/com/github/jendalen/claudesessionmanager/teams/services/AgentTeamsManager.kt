package com.github.jendalen.claudesessionmanager.teams.services

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.teams.util.AgentIdValidator
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages teammate terminal tabs and maps team members to IDE UI elements.
 *
 * Listens to TeamWatcher events and creates/manages terminal tabs for teammates.
 * Supports two modes:
 * - Proxy tabs (default): informational tabs showing teammate status
 * - Native tabs (experimental): actual Claude processes for each teammate
 */
@Service(Service.Level.PROJECT)
class AgentTeamsManager(private val project: Project) : TeamEventListener, Disposable {

    private val logger = Logger.getInstance(AgentTeamsManager::class.java)

    // Map of agentId -> terminal Content
    private val memberTabMap = ConcurrentHashMap<String, Content>()

    // Map of Content displayName -> agentId (reverse lookup)
    private val tabToMemberMap = ConcurrentHashMap<String, String>()

    @Volatile
    private var currentTeam: TeamModel? = null

    companion object {
        /**
         * UserData key to mark terminal tabs as team-managed.
         * V1.0 services (AutoResumeService, SessionWatcherService, etc.)
         * should check this key and skip team tabs.
         */
        val TEAM_TAB_KEY = TerminalHelper.TEAM_TAB_KEY

        private val teamsIcon by lazy {
            try {
                IconLoader.getIcon("/icons/claude-teams.svg", AgentTeamsManager::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun getInstance(project: Project): AgentTeamsManager {
            return project.getService(AgentTeamsManager::class.java)
        }

        /**
         * Checks if a terminal tab Content is managed by the team system.
         * V1.0 services should call this to avoid interfering with team tabs.
         */
        fun isTeamTab(content: Content): Boolean {
            return TerminalHelper.isTeamTab(content)
        }
    }

    /**
     * Initializes the manager by registering as a listener on the team watcher.
     */
    fun initialize() {
        val watcher = FileSystemTeamWatcher.getInstance(project)
        watcher.addListener(this)
    }

    override fun onTeamEvent(event: TeamEvent) {
        when (event) {
            is TeamEvent.TeamCreated -> onTeamCreated(event.team)
            is TeamEvent.TeamUpdated -> onTeamUpdated(event.team)
            is TeamEvent.TeamDeleted -> onTeamDeleted(event.teamId)
            is TeamEvent.MemberAdded -> onMemberAdded(event.teamId, event.member)
            is TeamEvent.MemberRemoved -> onMemberRemoved(event.teamId, event.agentId)
            is TeamEvent.TaskChanged,
            is TeamEvent.MessageReceived -> { /* Handled by dashboard */ }
        }
    }

    fun onTeamCreated(team: TeamModel) {
        currentTeam = team
        logger.info("Team created: ${team.name} with ${team.members.size} members")

        val settings = TeamsSettingsService.getInstance().getSettings()

        ApplicationManager.getApplication().invokeLater {
            // Register the lead's terminal tab (the currently selected one)
            registerLeadTab(team)

            // Create tabs for teammates based on their mode:
            // - tmux teammates (have paneId): always create tabs (useful live output)
            // - in-process teammates (no paneId): only if setting enabled (tabs are informational only)
            for (member in team.teammates()) {
                val hasTmuxPane = member.tmuxPaneId.isNotBlank()
                if (hasTmuxPane || settings.createTabsForInProcessTeammates) {
                    createTabForMember(member, settings.nativeTeammateSpawning)
                }
            }
        }
    }

    /**
     * Registers the lead's terminal tab.
     * The lead is whoever was running Claude when the team was created,
     * so we use the currently selected terminal tab.
     */
    private fun registerLeadTab(team: TeamModel) {
        val leadId = team.leadAgentId
        if (leadId.isBlank() || memberTabMap.containsKey(leadId)) return

        val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return
        val content = toolWindow.contentManager.selectedContent ?: return

        // Don't claim a tab that's already registered to another team member
        if (content.getUserData(TEAM_TAB_KEY) != null) return

        memberTabMap[leadId] = content
        tabToMemberMap[content.displayName ?: ""] = leadId
        content.putUserData(TEAM_TAB_KEY, leadId)
        logger.info("Registered lead tab '${content.displayName}' for agent '$leadId'")
    }

    private fun onTeamUpdated(team: TeamModel) {
        currentTeam = team
    }

    fun onMemberAdded(teamId: String, member: TeamMember) {
        val settings = TeamsSettingsService.getInstance().getSettings()
        val hasTmuxPane = member.tmuxPaneId.isNotBlank()
        if (!hasTmuxPane && !settings.createTabsForInProcessTeammates) return

        ApplicationManager.getApplication().invokeLater {
            createTabForMember(member, settings.nativeTeammateSpawning)
        }
    }

    fun onMemberRemoved(teamId: String, agentId: String) {
        ApplicationManager.getApplication().invokeLater {
            markTabCompleted(agentId)
        }
    }

    fun onTeamDeleted(teamName: String) {
        logger.info("Team deleted: $teamName")
        val previousTeam = currentTeam
        currentTeam = null

        val autoClose = TeamsSettingsService.getInstance().getSettings().autoCloseTabsOnTeamDelete

        ApplicationManager.getApplication().invokeLater {
            val toolWindow = TerminalHelper.getTerminalToolWindow(project)
            val contentManager = toolWindow?.contentManager

            for ((agentId, content) in memberTabMap) {
                // Never auto-close the lead's tab
                val isLead = agentId == previousTeam?.leadAgentId
                if (autoClose && !isLead && contentManager != null) {
                    contentManager.removeContent(content, true)
                } else {
                    markTabCompleted(agentId)
                }
            }

            memberTabMap.clear()
            tabToMemberMap.clear()
        }
    }

    /**
     * Creates a terminal tab for a team member.
     */
    private fun createTabForMember(member: TeamMember, nativeSpawning: Boolean) {
        if (memberTabMap.containsKey(member.agentId)) return

        val tabName = buildTabName(member)

        if (nativeSpawning) {
            createNativeTab(member, tabName)
        } else {
            createProxyTab(member, tabName)
        }
    }

    /**
     * Creates a proxy (informational) tab for a teammate.
     * If the teammate has a tmuxPaneId, starts a monitoring view of the pane.
     * Otherwise, prints a status message.
     */
    private fun createProxyTab(member: TeamMember, tabName: String) {
        val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return
        val contentManager = toolWindow.contentManager

        // Check if a tab with this name already exists
        for (content in contentManager.contents) {
            if (content.displayName == tabName) {
                registerTab(content, member, tabName)
                return
            }
        }

        // Create a new terminal tab
        val widget = TerminalHelper.createNewTerminalTab(project, tabName)
        if (widget != null) {
            // Find the content that was just created
            for (content in contentManager.contents) {
                if (content.displayName == tabName) {
                    registerTab(content, member, tabName)
                    logger.info("Created proxy tab for teammate '${member.name}' (${member.agentId})")

                    // Send a monitoring command after the shell initializes
                    scheduleTabCommand(widget, member)
                    break
                }
            }
        }
    }

    /**
     * Schedules a command to run in the teammate's tab after the shell initializes.
     * For tmux teammates: starts a live capture-pane monitoring loop.
     * For in-process teammates: prints a status message.
     */
    private fun scheduleTabCommand(widget: org.jetbrains.plugins.terminal.ShellTerminalWidget, member: TeamMember) {
        val disposable = Disposer.newDisposable("TeamTabCommand-${member.agentId}")
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        val paneId = member.tmuxPaneId
        val name = member.name.ifBlank { member.agentId }
        val type = member.agentType.ifBlank { "agent" }

        alarm.addRequest({
            try {
                if (paneId.isNotBlank()) {
                    // tmux mode: monitor the teammate's pane with capture-pane
                    val command = buildTmuxMonitorCommand(paneId, name, type)
                    TerminalHelper.executeCommandInWidget(widget, command)
                    logger.info("Started tmux pane monitoring for '$name' (pane $paneId)")
                } else {
                    // in-process mode: print status info
                    val statusMsg = "echo 'Teammate: $name ($type) -- running in lead terminal (in-process mode)'"
                    TerminalHelper.executeCommandInWidget(widget, statusMsg)
                }
            } catch (e: Exception) {
                logger.warn("Failed to send command to teammate tab '$name': ${e.message}")
            } finally {
                Disposer.dispose(disposable)
            }
        }, 500)
    }

    /**
     * Builds a shell command that monitors a tmux pane by periodically capturing its content.
     */
    private fun buildTmuxMonitorCommand(paneId: String, name: String, type: String): String {
        // Sanitize for shell safety: paneId should be like %42, name/type are display-only
        val safePaneId = paneId.replace("'", "")
        val safeName = name.replace("'", "")
        val safeType = type.replace("'", "")

        return "while true; do " +
                "clear; " +
                "echo '=== $safeName ($safeType) | pane $safePaneId ==='; " +
                "echo ''; " +
                "tmux capture-pane -t '$safePaneId' -p -e 2>/dev/null || " +
                "{ echo 'Session ended.'; break; }; " +
                "sleep 1; " +
                "done"
    }

    /**
     * Registers a Content as a team tab with proper markers and icons.
     */
    private fun registerTab(content: Content, member: TeamMember, tabName: String) {
        memberTabMap[member.agentId] = content
        tabToMemberMap[tabName] = member.agentId

        // Mark as team-managed so v1.0 services skip this tab
        content.putUserData(TEAM_TAB_KEY, member.agentId)

        content.icon = teamsIcon
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
    }

    /**
     * Creates a native tab that runs a Claude process for the teammate.
     * Falls back to proxy tab on failure.
     */
    private fun createNativeTab(member: TeamMember, tabName: String) {
        // Validate agent ID format
        if (!AgentIdValidator.isValid(member.agentId)) {
            logger.warn("Invalid agent ID format for native spawning: ${member.agentId}, falling back to proxy tab")
            createProxyTab(member, tabName)
            return
        }

        val team = currentTeam
        if (team == null) {
            logger.warn("No active team for native spawning, falling back to proxy tab")
            createProxyTab(member, tabName)
            return
        }

        // For now, create a standard terminal tab. Native process spawning can be
        // implemented later when the experimental feature matures.
        createProxyTab(member, tabName)
    }

    /**
     * Builds a display name for a teammate's tab.
     */
    private fun buildTabName(member: TeamMember): String {
        val icon = colorToIcon(member.color)
        val type = member.agentType.ifBlank { member.name }
        return "$icon $type"
    }

    /**
     * Maps a member's color to a text label for the tab title.
     */
    private fun colorToIcon(color: String): String {
        return when (color.lowercase()) {
            "red" -> "[R]"
            "blue" -> "[B]"
            "green" -> "[G]"
            "yellow" -> "[Y]"
            "purple", "violet" -> "[P]"
            "orange" -> "[O]"
            "cyan" -> "[C]"
            "pink" -> "[K]"
            else -> "[*]"
        }
    }

    /**
     * Marks a member's tab as completed by appending "(completed)" to the tab name.
     */
    private fun markTabCompleted(agentId: String) {
        val content = memberTabMap[agentId] ?: return
        val currentName = content.displayName ?: return

        if (!currentName.endsWith("(completed)")) {
            content.displayName = "$currentName (completed)"
        }
    }

    /**
     * Returns the terminal Content for a given agent ID.
     */
    fun getTabForMember(agentId: String): Content? {
        return memberTabMap[agentId]
    }

    /**
     * Returns the TeamMember for a given terminal tab Content.
     */
    fun getMemberForTab(content: Content): TeamMember? {
        val tabName = content.displayName ?: return null
        val agentId = tabToMemberMap[tabName] ?: return null
        return currentTeam?.members?.find { it.agentId == agentId }
    }

    /**
     * Navigates to a teammate's terminal tab.
     */
    fun navigateToMember(agentId: String) {
        val content = memberTabMap[agentId] ?: return
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return@invokeLater
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.show()
        }
    }

    /**
     * Returns the currently active team model.
     */
    fun getActiveTeam(): TeamModel? = currentTeam

    /**
     * Returns all terminal Contents for teammate tabs.
     */
    fun getTeammateTerminalContents(): List<Content> {
        return memberTabMap.values.toList()
    }

    override fun dispose() {
        // Remove listener to prevent events dispatching to disposed manager
        try {
            FileSystemTeamWatcher.getInstance(project).removeListener(this)
        } catch (e: Exception) {
            // Project may be disposing; watcher may already be disposed
        }
        memberTabMap.clear()
        tabToMemberMap.clear()
        currentTeam = null
    }
}
