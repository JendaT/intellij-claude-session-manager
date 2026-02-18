package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.services.FileSystemTeamWatcher
import com.github.jendalen.claudesessionmanager.teams.services.TeamEventListener
import com.github.jendalen.claudesessionmanager.teams.services.TeamsSettingsService
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Listens to team events and shows balloon notifications based on user preferences.
 *
 * All notification types are individually configurable via TeamsSettings.
 */
@Service(Service.Level.PROJECT)
class TeamNotificationService(private val project: Project) : TeamEventListener, Disposable {

    private val logger = Logger.getInstance(TeamNotificationService::class.java)

    companion object {
        fun getInstance(project: Project): TeamNotificationService {
            return project.getService(TeamNotificationService::class.java)
        }
    }

    /**
     * Registers this service as a listener on the team watcher.
     */
    fun initialize() {
        FileSystemTeamWatcher.getInstance(project).addListener(this)
    }

    override fun onTeamEvent(event: TeamEvent) {
        val settings = TeamsSettingsService.getInstance().getSettings()

        when (event) {
            is TeamEvent.TeamCreated -> {
                if (settings.notifyOnTeamCreate) {
                    val memberCount = event.team.members.size
                    NotificationHelper.info(
                        project,
                        "Agent Team Created",
                        "Team '${event.team.name}' created with $memberCount teammate${if (memberCount != 1) "s" else ""}"
                    )
                }
            }
            is TeamEvent.TaskChanged -> {
                if (settings.notifyOnTaskComplete &&
                    event.task.status == com.github.jendalen.claudesessionmanager.teams.model.TaskStatus.COMPLETED
                ) {
                    NotificationHelper.info(
                        project,
                        "Task Completed",
                        "Task #${event.task.id}: ${event.task.description.lineSequence().firstOrNull() ?: ""}"
                    )
                }
            }
            is TeamEvent.MemberRemoved -> {
                if (settings.notifyOnTeammateShutdown) {
                    NotificationHelper.info(
                        project,
                        "Teammate Removed",
                        "Agent '${event.agentId}' has been removed from the team"
                    )
                }
            }
            is TeamEvent.MessageReceived -> {
                if (settings.notifyOnTeammateError && event.message.type == "error") {
                    NotificationHelper.warning(
                        project,
                        "Teammate Error",
                        "${event.message.from}: ${event.message.contentPreview(80)}"
                    )
                }
            }
            is TeamEvent.TeamDeleted -> {
                if (settings.notifyOnTeamCreate) {
                    NotificationHelper.info(
                        project,
                        "Agent Team Ended",
                        "Team '${event.teamId}' has been deleted"
                    )
                }
            }
            is TeamEvent.TeamUpdated,
            is TeamEvent.MemberAdded -> {
                // No notifications for these events
            }
        }
    }

    override fun dispose() {
        try {
            FileSystemTeamWatcher.getInstance(project).removeListener(this)
        } catch (e: Exception) {
            // Project may be disposing; watcher may already be disposed
        }
    }
}
