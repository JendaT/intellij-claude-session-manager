package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.terminal.TerminalTabContext
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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

    companion object {
        // Terminal tabs are restored asynchronously after project open; keep re-trying the
        // binding re-attachment until they show up, within this budget.
        private const val TAB_RESTORE_WAIT_BUDGET_MS = 15000L
        private const val TAB_RESTORE_POLL_MS = 500L
    }

    override suspend fun execute(project: Project) {
        val settings = SettingsService.getInstance()

        // Check if auto-resume is enabled
        if (!settings.autoResumeEnabled) {
            logger.info("Auto-resume disabled in settings")
            // Still migrate legacy bindings and start the session watcher.
            runMigration(project)
            startSessionWatcher(project)
            return
        }

        // Get the configured delay
        val delayMs = settings.autoResumeDelayMs
        logger.info("Auto-resume will start after ${delayMs}ms delay")

        // Wait for terminal to initialize
        delay(delayMs.toLong())

        // Re-attach bindings to restored tabs (stable ids do not survive restart; matching is
        // by the terminal-persisted tab name). Tabs are restored asynchronously by the
        // terminal plugin, so retry until every binding found its tab or the budget runs out.
        runMigration(project)
        val migrationService = BindingMigrationService.getInstance(project)
        var waited = 0L
        while (migrationService.unattachedBindings().isNotEmpty() && waited < TAB_RESTORE_WAIT_BUDGET_MS) {
            delay(TAB_RESTORE_POLL_MS)
            waited += TAB_RESTORE_POLL_MS
            runMigration(project)
        }
        if (waited > 0) {
            logger.info("Auto-resume waited additional ${waited}ms for terminal tab restoration")
        }

        // Get bindings that need auto-resume
        val bindingService = BindingService.getInstance(project)
        val bindings = bindingService.getAutoResumeBindings()

        if (bindings.isEmpty()) {
            logger.info("No bindings with auto-resume enabled")
            startSessionWatcher(project)
            return
        }

        logger.info("Found ${bindings.size} bindings to auto-resume")

        // Activate suppression window on the hook event service BEFORE sending resume commands.
        // This prevents hook events from flip-flopping bindings during multi-tab auto-resume.
        try {
            ClaudeHookEventService.getInstance(project).suppressForAutoResume()
        } catch (e: Exception) {
            logger.warn("Failed to set auto-resume suppression: ${e.message}")
        }

        // Track results
        var resumedCount = 0
        val missingBindings = mutableListOf<SessionBinding>()
        var failedCount = 0

        // Resume each binding
        for (binding in bindings) {
            val tabName = binding.tabIdentifier.name
            val persistentName = binding.tabIdentifier.persistentName ?: tabName
            val tabUuid = binding.tabIdentifier.tabUuid

            // Prefer matching by stable tab id (set this startup by migration); fall back to
            // the terminal-persisted name, then the volatile display name.
            val toolWindow = TerminalHelper.getTerminalToolWindow(project)
            var terminalView = tabUuid?.let { TerminalHelper.findTerminalViewByTabId(project, it) }
            var content = tabUuid?.let { TerminalHelper.findContentByTabId(project, it) }
            if (terminalView == null && content == null) {
                content = toolWindow?.contentManager?.contents?.find {
                    TerminalHelper.getPersistentName(project, it) == persistentName
                } ?: toolWindow?.contentManager?.contents?.find { it.displayName == tabName }
                terminalView = content?.let { c ->
                    TerminalHelper.findTerminalViewByTabId(project, TerminalHelper.getOrCreateTabId(c))
                } ?: TerminalHelper.findTerminalViewByTabName(project, tabName)
            }
            // Resolve the id we will target with the command (the tab's actual current id).
            val resolvedTabId = content?.let { TerminalHelper.getTabId(it) } ?: tabUuid

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
                content = content,
                tabId = resolvedTabId
            )

            // Resume the session
            try {
                val success = TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)
                if (success) {
                    bindingService.markBindingResumed(binding.id)
                    // Set icon and title protection on EDT
                    content?.let { c ->
                        ApplicationManager.getApplication().invokeLater {
                            TerminalHelper.setClaudeIconOnContent(c)
                            TerminalHelper.protectTabTitleIfEnabled(project, c)
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

    /**
     * Runs the legacy-binding migration synchronously on the EDT so that bindings carry their
     * stable tab id before the resume loop (which matches tabs by that id) executes.
     */
    private fun runMigration(project: Project) {
        try {
            ApplicationManager.getApplication().invokeAndWait {
                BindingMigrationService.getInstance(project).migrate()
            }
        } catch (e: Exception) {
            logger.warn("Binding migration failed: ${e.message}", e)
        }
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
        NotificationHelper.show(
            project,
            "Claude Sessions",
            "$count session${if (count > 1) "s" else ""} resumed",
            NotificationType.INFORMATION
        )
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
                val widget = TerminalHelper.createNewTerminalTab(project, tabName)
                if (widget != null) {
                    // Delay to let terminal initialize using a self-disposing alarm
                    val alarmDisposable = Disposer.newDisposable("RecreateTabAlarm-$tabName")
                    val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, alarmDisposable)
                    alarm.addRequest({
                        try {
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
                        } finally {
                            Disposer.dispose(alarmDisposable)
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
        NotificationHelper.show(project, "Claude Sessions", message, type)
    }
}
