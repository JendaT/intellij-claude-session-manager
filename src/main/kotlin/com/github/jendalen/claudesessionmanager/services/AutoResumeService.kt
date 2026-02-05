package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.terminal.TerminalTabContext
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import kotlinx.coroutines.delay

/**
 * Startup activity that auto-resumes Claude sessions when the IDE starts.
 *
 * For each binding with autoResume=true:
 * 1. Wait for terminal plugin to initialize
 * 2. Find terminal tab by name
 * 3. Resume the bound session
 */
class AutoResumeService : ProjectActivity {

    private val logger = Logger.getInstance(AutoResumeService::class.java)

    override suspend fun execute(project: Project) {
        val settings = SettingsService.getInstance()

        // Check if auto-resume is enabled
        if (!settings.autoResumeEnabled) {
            logger.info("Auto-resume disabled in settings")
            // Still start the session watcher
            startSessionWatcher(project)
            return
        }

        // Get the configured delay
        val delayMs = settings.autoResumeDelayMs
        logger.info("Auto-resume will start after ${delayMs}ms delay")

        // Wait for terminal to initialize
        delay(delayMs.toLong())

        // Get bindings that need auto-resume
        val bindingService = BindingService.getInstance(project)
        val bindings = bindingService.getAutoResumeBindings()

        if (bindings.isEmpty()) {
            logger.info("No bindings with auto-resume enabled")
            startSessionWatcher(project)
            return
        }

        logger.info("Found ${bindings.size} bindings to auto-resume")

        // Track results
        var resumedCount = 0
        val missingBindings = mutableListOf<SessionBinding>()
        var failedCount = 0

        // Resume each binding
        for (binding in bindings) {
            val tabName = binding.tabIdentifier.name

            // Check if session still exists
            val sessionService = ClaudeSessionService.getInstance(project)
            if (!sessionService.sessionExists(binding.currentSessionId)) {
                logger.warn("Session ${binding.currentSessionId} no longer exists for tab '$tabName'")
                missingBindings.add(binding)
                continue
            }

            // Find terminal tab by name using TerminalToolWindowTabsManager
            val terminalView = TerminalHelper.findTerminalViewByTabName(project, tabName)
            val toolWindow = TerminalHelper.getTerminalToolWindow(project)
            val content = toolWindow?.contentManager?.contents?.find { it.displayName == tabName }

            if (terminalView == null && content == null) {
                logger.info("Terminal tab '$tabName' not found - adding to missing list")
                missingBindings.add(binding)
                continue
            }

            // Create context for the tab
            val context = TerminalTabContext(
                tabName = tabName,
                terminalView = terminalView,
                shellWidget = TerminalHelper.findWidgetByName(project, tabName),
                content = content
            )

            // Resume the session
            try {
                val success = TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)
                if (success) {
                    bindingService.markBindingResumed(binding.id)
                    // Set icon on EDT
                    content?.let { c ->
                        ApplicationManager.getApplication().invokeLater {
                            TerminalHelper.setClaudeIconOnContent(c)
                        }
                    }
                    resumedCount++
                    logger.info("Auto-resumed session ${binding.currentSessionId} in tab '$tabName'")
                } else {
                    failedCount++
                    logger.warn("Failed to resume session ${binding.currentSessionId} in tab '$tabName'")
                }
            } catch (e: Exception) {
                failedCount++
                logger.error("Error resuming session ${binding.currentSessionId}: ${e.message}", e)
            }
        }

        // Show notifications
        if (resumedCount > 0) {
            showSuccessNotification(project, resumedCount)
        }

        if (missingBindings.isNotEmpty()) {
            showMissingTabsNotification(project, missingBindings, bindingService)
        }

        if (failedCount > 0) {
            showNotification(project, "$failedCount session${if (failedCount > 1) "s" else ""} failed to resume", NotificationType.WARNING)
        }

        // Start session watcher for detecting /clear, /compact etc.
        startSessionWatcher(project)
    }

    private fun startSessionWatcher(project: Project) {
        try {
            SessionWatcherService.getInstance(project).startWatching()
            logger.info("Started session watcher")
        } catch (e: Exception) {
            logger.warn("Failed to start session watcher: ${e.message}", e)
        }

        // Also start terminal output monitoring for auto-detection
        try {
            TerminalOutputMonitorService.getInstance(project).startMonitoring()
            logger.info("Started terminal output monitoring")
        } catch (e: Exception) {
            logger.warn("Failed to start terminal output monitoring: ${e.message}", e)
        }

        // Start Claude hook event watching (most reliable method)
        try {
            ClaudeHookEventService.getInstance(project).startWatching()
            logger.info("Started Claude hook event watching")
        } catch (e: Exception) {
            logger.warn("Failed to start Claude hook event watching: ${e.message}", e)
        }
    }

    private fun showSuccessNotification(project: Project, count: Int) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification(
                "Claude Sessions",
                "$count session${if (count > 1) "s" else ""} resumed",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    private fun showMissingTabsNotification(
        project: Project,
        missingBindings: List<SessionBinding>,
        bindingService: BindingService
    ) {
        val tabNames = missingBindings.map { it.tabIdentifier.name }
        val message = if (missingBindings.size == 1) {
            "Tab '${tabNames[0]}' not found"
        } else {
            "${missingBindings.size} tabs not found: ${tabNames.take(3).joinToString(", ")}${if (tabNames.size > 3) "..." else ""}"
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification("Claude Sessions", message, NotificationType.WARNING)

        // Add "Dismiss" action - removes the bindings
        notification.addAction(NotificationAction.createSimple("Dismiss (remove bindings)") {
            for (binding in missingBindings) {
                bindingService.deleteBinding(binding.id)
                logger.info("Removed binding for missing tab '${binding.tabIdentifier.name}'")
            }
            notification.expire()
            showNotification(project, "${missingBindings.size} binding${if (missingBindings.size > 1) "s" else ""} removed", NotificationType.INFORMATION)
        })

        // Add "Recreate Tabs" action - creates new tabs with the original names
        notification.addAction(NotificationAction.createSimple("Recreate Tabs") {
            ApplicationManager.getApplication().invokeLater {
                recreateMissingTabs(project, missingBindings)
            }
            notification.expire()
        })

        notification.notify(project)
    }

    private fun recreateMissingTabs(project: Project, missingBindings: List<SessionBinding>) {
        var createdCount = 0

        for (binding in missingBindings) {
            val tabName = binding.tabIdentifier.name
            try {
                // Try to create a new terminal tab with the original name
                val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
                val tabBuilder = tabsManager.createTabBuilder()

                // Create the tab - this returns a TerminalToolWindowTab
                // Note: We can't easily set the name, so we'll rely on the session to set it
                // For now, just create a generic tab and resume the session

                // Alternative: Use the old API if available
                val widget = TerminalHelper.createNewTerminalTab(project, tabName)
                if (widget != null) {
                    // Delay to let terminal initialize
                    com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD).addRequest({
                        // Find the new tab and resume
                        val terminalView = TerminalHelper.findTerminalViewByTabName(project, tabName)
                        val content = TerminalHelper.getTerminalToolWindow(project)
                            ?.contentManager?.contents?.find { it.displayName == tabName }

                        if (terminalView != null || content != null) {
                            val context = TerminalTabContext(
                                tabName = tabName,
                                terminalView = terminalView,
                                shellWidget = widget,
                                content = content
                            )
                            val success = TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)
                            if (success) {
                                val bindingService = BindingService.getInstance(project)
                                bindingService.markBindingResumed(binding.id)
                                content?.let { TerminalHelper.setClaudeIconOnContent(it) }
                                logger.info("Recreated and resumed tab '$tabName'")
                            }
                        }
                    }, 500)
                    createdCount++
                } else {
                    logger.warn("Failed to create terminal tab '$tabName'")
                }
            } catch (e: Exception) {
                logger.error("Error recreating tab '$tabName': ${e.message}", e)
            }
        }

        if (createdCount > 0) {
            showNotification(project, "$createdCount tab${if (createdCount > 1) "s" else ""} recreated", NotificationType.INFORMATION)
        }
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification("Claude Sessions", message, type)
            .notify(project)
    }
}
