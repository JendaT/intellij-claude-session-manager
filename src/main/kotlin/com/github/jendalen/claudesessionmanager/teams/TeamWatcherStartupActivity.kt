package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.services.AgentTeamsManager
import com.github.jendalen.claudesessionmanager.teams.services.FileSystemTeamWatcher
import com.github.jendalen.claudesessionmanager.teams.services.TeamsSettingsService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that initializes team watching when the project opens.
 *
 * Starts the FileSystemTeamWatcher and connects it to the AgentTeamsManager.
 * Also performs an initial scan for any existing teams that may have been
 * created before the IDE started (recovery from IDE restart mid-team session).
 */
class TeamWatcherStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(TeamWatcherStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val settings = TeamsSettingsService.getInstance().getSettings()
        if (!settings.enableTeamDetection) {
            logger.info("Team detection disabled in settings")
            return
        }

        try {
            // Initialize the AgentTeamsManager (registers as listener on watcher)
            AgentTeamsManager.getInstance(project).initialize()

            // Initialize the notification service
            TeamNotificationService.getInstance(project).initialize()

            // Start the file system watcher (does initial scan + live watching)
            FileSystemTeamWatcher.getInstance(project).start()

            logger.info("Team watcher started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start team watcher: ${e.message}", e)
        }
    }
}
