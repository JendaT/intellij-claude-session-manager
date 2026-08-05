package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.terminal.TerminalTabContext
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Startup activity that re-attaches bindings to restored terminal tabs and auto-resumes
 * Claude sessions.
 *
 * Binding re-attachment (BindingMigrationService) runs regardless of the auto-resume
 * setting - the restart-stable identity must not depend on auto-resume being enabled.
 * Terminal tabs restore asynchronously, so the migration is retried until every binding
 * found its tab or no progress is being made.
 */
class AutoResumeService : ProjectActivity {

    private val logger = Logger.getInstance(AutoResumeService::class.java)

    companion object {
        // Terminal tabs are restored asynchronously after project open; keep re-trying the
        // binding re-attachment until they show up, within this budget. Stop early when
        // consecutive polls make no progress (bindings whose tab is gone would otherwise
        // burn the whole budget on every startup).
        private const val TAB_RESTORE_WAIT_BUDGET_MS = 15000L
        private const val TAB_RESTORE_POLL_MS = 500L
        private const val MAX_STAGNANT_POLLS = 4
    }

    private enum class ResumeOutcome { RESUMED, MISSING, FAILED }

    override suspend fun execute(project: Project) {
        val settings = SettingsService.getInstance()

        val delayMs = settings.autoResumeDelayMs
        logger.info("Binding re-attachment starts after ${delayMs}ms delay (autoResume=${settings.autoResumeEnabled})")
        delay(delayMs)

        // Re-attach bindings to restored tabs on both paths.
        waitForTabRestorationAndMigrate(project)

        if (!settings.autoResumeEnabled) {
            logger.info("Auto-resume disabled in settings")
            startSessionWatcher(project)
            return
        }

        // Get bindings that need auto-resume. Bindings already attached to a live tab go
        // first so that, should duplicate-session bindings still exist (migration removes
        // them, but it may not have run when no terminal was ready), the attached one wins
        // the per-session dedup below.
        val bindingService = BindingService.getInstance(project)
        val liveTabIds = try {
            withContext(Dispatchers.EDT) { TerminalHelper.liveTabIds(project) }
        } catch (e: Exception) {
            emptySet()
        }
        val bindings = bindingService.getAutoResumeBindings()
            .sortedByDescending { it.tabIdentifier.tabUuid != null && it.tabIdentifier.tabUuid in liveTabIds }

        if (bindings.isEmpty()) {
            logger.info("No bindings with auto-resume enabled")
            startSessionWatcher(project)
            return
        }

        logger.info("Found ${bindings.size} bindings to auto-resume")

        // Activate the suppression window BEFORE sending resume commands. This prevents
        // hook events from flip-flopping bindings during multi-tab auto-resume.
        try {
            TabBindingCoordinator.getInstance(project).suppressForAutoResume()
        } catch (e: Exception) {
            logger.warn("Failed to set auto-resume suppression: ${e.message}", e)
        }

        // Track results
        var resumedCount = 0
        val missingBindings = mutableListOf<SessionBinding>()
        val staleBindings = mutableListOf<SessionBinding>()
        var failedCount = 0
        val handledSessionIds = mutableSetOf<String>()

        // Resume each binding
        for (binding in bindings) {
            // A session runs in exactly one tab - never send the same resume twice
            // (duplicate bindings used to type the command into the running Claude).
            if (!handledSessionIds.add(binding.currentSessionId.lowercase())) {
                logger.info("Skipping duplicate auto-resume of session ${binding.currentSessionId} (binding ${binding.id})")
                continue
            }
            // Claude Code purges transcripts after its cleanup period; resuming a purged
            // session just prints "No conversation found" into the tab. Skip and offer
            // recovery instead.
            if (!ClaudePathResolver.sessionFileExists(binding.currentSessionId)) {
                logger.info("Session ${binding.currentSessionId} has no transcript on disk - skipping resume (binding ${binding.id})")
                staleBindings.add(binding)
                continue
            }
            when (resumeBinding(project, binding, bindingService)) {
                ResumeOutcome.RESUMED -> resumedCount++
                ResumeOutcome.MISSING -> missingBindings.add(binding)
                ResumeOutcome.FAILED -> failedCount++
            }
        }

        // Show notifications
        if (resumedCount > 0) {
            showSuccessNotification(project, resumedCount)
        }

        if (missingBindings.isNotEmpty()) {
            showMissingTabsNotification(project, missingBindings, bindingService)
        }

        if (staleBindings.isNotEmpty()) {
            showStaleSessionsNotification(project, staleBindings, bindingService)
        }

        if (failedCount > 0) {
            showNotification(project, "$failedCount session${if (failedCount > 1) "s" else ""} failed to resume", NotificationType.WARNING)
        }

        // Start session watcher for detecting /clear, /compact etc.
        startSessionWatcher(project)
    }

    /**
     * Repeatedly runs the binding re-attachment on the EDT while terminal tabs are being
     * restored. Exits when all bindings are attached, when consecutive polls stop making
     * progress, or when the time budget runs out.
     */
    private suspend fun waitForTabRestorationAndMigrate(project: Project) {
        val migrationService = BindingMigrationService.getInstance(project)
        var waited = 0L
        var stagnantPolls = 0
        var lastPending = Int.MAX_VALUE

        while (waited < TAB_RESTORE_WAIT_BUDGET_MS) {
            val (ready, pending) = try {
                withContext(Dispatchers.EDT) {
                    val ready = migrationService.isTerminalReady()
                    if (ready) migrationService.migrate()
                    ready to migrationService.unattachedBindings().size
                }
            } catch (e: Exception) {
                logger.warn("Binding migration failed: ${e.message}", e)
                return
            }

            if (pending == 0) return

            // Stagnation counts only actual matching attempts. "Terminal not restored yet"
            // must keep the full wait budget - on slow startups the tool window can take
            // many seconds to appear, and giving up early orphans every binding.
            if (ready) {
                stagnantPolls = if (pending >= lastPending) stagnantPolls + 1 else 0
                if (stagnantPolls >= MAX_STAGNANT_POLLS) {
                    logger.info("$pending binding(s) still unattached after ${waited}ms with no progress - continuing without them")
                    return
                }
                lastPending = pending
            }

            delay(TAB_RESTORE_POLL_MS)
            waited += TAB_RESTORE_POLL_MS
        }
        logger.info("Tab-restoration wait budget exhausted (${TAB_RESTORE_WAIT_BUDGET_MS}ms)")
    }

    /**
     * Resolves the terminal tab for a binding and sends the resume command. Runs entirely
     * on the EDT - tab resolution touches ToolWindow/ContentManager/tabs-manager state.
     */
    private suspend fun resumeBinding(
        project: Project,
        binding: SessionBinding,
        bindingService: BindingService
    ): ResumeOutcome = withContext(Dispatchers.EDT) {
        val targetName = binding.tabIdentifier.effectiveName
        try {
            val content = resolveContentForBinding(project, binding, bindingService)
            if (content == null) {
                logger.info("Terminal tab '$targetName' not found (or ambiguous) - adding to missing list")
                return@withContext ResumeOutcome.MISSING
            }

            val displayName = content.displayName ?: targetName
            val tabId = TerminalHelper.getTabId(content)
            val terminalView = tabId?.let { TerminalHelper.findTerminalViewByTabId(project, it) }
                ?: TerminalHelper.findTerminalViewByTabName(project, displayName)

            val context = TerminalTabContext(
                tabName = displayName,
                terminalView = terminalView,
                shellWidget = TerminalHelper.findWidgetByName(project, displayName),
                content = content,
                tabId = tabId,
                persistentName = binding.tabIdentifier.persistentName
            )

            val success = TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)
            if (success) {
                bindingService.markBindingResumed(binding.id)
                TerminalHelper.setClaudeIconOnContent(content)
                TerminalHelper.protectTabTitleIfEnabled(project, content)
                logger.info("Auto-resumed session ${binding.currentSessionId} in tab '$displayName'")
                ResumeOutcome.RESUMED
            } else {
                logger.warn("Failed to resume session ${binding.currentSessionId} in tab '$displayName'")
                ResumeOutcome.FAILED
            }
        } catch (e: Exception) {
            logger.error("Error resuming session ${binding.currentSessionId}: ${e.message}", e)
            ResumeOutcome.FAILED
        }
    }

    /**
     * Finds the tab Content a binding refers to. Prefers the stable tab id; falls back to an
     * UNAMBIGUOUS match on the terminal-persisted name (mirroring the migration's rules -
     * with several same-named tabs we never guess). A successful fallback match re-attaches
     * the binding to the tab's live id so tab and binding agree from then on.
     */
    private fun resolveContentForBinding(
        project: Project,
        binding: SessionBinding,
        bindingService: BindingService
    ): Content? {
        binding.tabIdentifier.tabUuid?.let { uuid ->
            TerminalHelper.findContentByTabId(project, uuid)?.let { return it }
        }

        val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return null
        val targetName = binding.tabIdentifier.effectiveName
        // Match on TerminalTitle-derived names ONLY. Unlike migration (which runs before any
        // Claude process starts), this executes mid-resume: earlier resumes may already have
        // OSC-renamed their tabs' display names, so a displayName fallback here could turn a
        // genuine ambiguity into a false "unique" match by elimination.
        val titleNames = TerminalHelper.persistentNamesSnapshot(project)
        val candidates = toolWindow.contentManager.contents
            .filterNot { TerminalHelper.isTeamTab(it) }
            .filter { titleNames[it] == targetName }

        val content = candidates.singleOrNull() ?: return null
        val liveId = TerminalHelper.getOrCreateTabId(content)
        val claimedBy = bindingService.findBindingByTabUuid(liveId)
        if (claimedBy != null && claimedBy.id != binding.id) return null

        bindingService.attachTabUuid(binding.id, liveId, titleNames[content])
        return content
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
        val tabNames = missingBindings.map { it.tabIdentifier.effectiveName }
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
                logger.info("Removed binding for missing tab '${binding.tabIdentifier.effectiveName}'")
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

    /**
     * Shown when bindings point at sessions whose transcript no longer exists on disk
     * (Claude Code purges old conversations). Resuming them is impossible forever, so the
     * user chooses: start a fresh Claude session in the same tab (the binding then follows
     * the new session via the SessionStart hook) or drop the binding.
     */
    private fun showStaleSessionsNotification(
        project: Project,
        staleBindings: List<SessionBinding>,
        bindingService: BindingService
    ) {
        val tabNames = staleBindings.map { it.tabIdentifier.effectiveName }
        val message = if (staleBindings.size == 1) {
            "Session in tab '${tabNames[0]}' no longer exists (Claude Code removes old conversations)"
        } else {
            "${staleBindings.size} sessions no longer exist: ${tabNames.take(3).joinToString(", ")}${if (tabNames.size > 3) "..." else ""}"
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("ClaudeSessionManager")
            .createNotification("Claude Sessions", message, NotificationType.WARNING)

        notification.addAction(NotificationAction.createSimple("Start new session") {
            ApplicationManager.getApplication().invokeLater {
                startFreshSessions(project, staleBindings, bindingService)
            }
            notification.expire()
        })

        notification.addAction(NotificationAction.createSimple("Remove binding${if (staleBindings.size > 1) "s" else ""}") {
            for (binding in staleBindings) {
                bindingService.deleteBinding(binding.id)
                logger.info("Removed binding for purged session ${binding.currentSessionId} ('${binding.tabIdentifier.effectiveName}')")
            }
            notification.expire()
        })

        notification.notify(project)
    }

    /**
     * Starts a plain (new-session) Claude in the tab of each stale binding. The binding is
     * not touched here - the SessionStart hook event updates its session id once Claude
     * reports in. Runs on EDT.
     */
    private fun startFreshSessions(
        project: Project,
        staleBindings: List<SessionBinding>,
        bindingService: BindingService
    ) {
        var started = 0
        for (binding in staleBindings) {
            val content = resolveContentForBinding(project, binding, bindingService)
            if (content == null) {
                logger.info("Tab for stale binding ${binding.id} ('${binding.tabIdentifier.effectiveName}') not found - cannot start new session")
                continue
            }
            val displayName = content.displayName ?: binding.tabIdentifier.effectiveName
            val tabId = TerminalHelper.getOrCreateTabId(content)
            // Authorize the upcoming fresh session to bind through the suppression window -
            // this is an explicit user action, not resume-storm noise.
            try {
                TabBindingCoordinator.getInstance(project).expectFreshSession(tabId)
            } catch (e: Exception) {
                logger.warn("Failed to register fresh-session expectation: ${e.message}", e)
            }
            val context = TerminalTabContext(
                tabName = displayName,
                terminalView = TerminalHelper.findTerminalViewByTabId(project, tabId)
                    ?: TerminalHelper.findTerminalViewByTabName(project, displayName),
                shellWidget = TerminalHelper.findWidgetByName(project, displayName),
                content = content,
                tabId = tabId,
                persistentName = binding.tabIdentifier.persistentName
            )
            if (TerminalHelper.executeCommand(context, TerminalHelper.getClaudeCommand())) {
                TerminalHelper.setClaudeIconOnContent(content)
                TerminalHelper.protectTabTitleIfEnabled(project, content)
                started++
                logger.info("Started new Claude session in tab '$displayName' (stale binding ${binding.id})")
            } else {
                logger.warn("Failed to start new Claude session in tab '$displayName'")
            }
        }
        if (started > 0) {
            showNotification(project, "$started new session${if (started > 1) "s" else ""} started", NotificationType.INFORMATION)
        }
    }

    private fun recreateMissingTabs(project: Project, missingBindings: List<SessionBinding>) {
        var createdCount = 0

        for (binding in missingBindings) {
            val tabName = binding.tabIdentifier.effectiveName
            try {
                // Try to create a new terminal tab with the original name
                val widget = TerminalHelper.createNewTerminalTab(project, tabName)
                if (widget != null) {
                    // Delay to let terminal initialize using a self-disposing alarm.
                    // Parented to BindingService so project close releases it even when
                    // the request never fires.
                    val alarmDisposable = Disposer.newDisposable("RecreateTabAlarm-$tabName")
                    Disposer.register(BindingService.getInstance(project), alarmDisposable)
                    val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, alarmDisposable)
                    alarm.addRequest({
                        try {
                            // Find the new tab and resume. Recreation typically happens
                            // exactly when names were ambiguous, so apply the same
                            // "exactly one unclaimed candidate" rule as everywhere else -
                            // never send a resume into a pre-existing same-named tab.
                            val bindingService = BindingService.getInstance(project)
                            val content = TerminalHelper.getTerminalToolWindow(project)
                                ?.contentManager?.contents
                                ?.filterNot { TerminalHelper.isTeamTab(it) }
                                ?.filter { it.displayName == tabName }
                                ?.filterNot { c ->
                                    TerminalHelper.getTabId(c)?.let { id ->
                                        bindingService.findBindingByTabUuid(id)?.id?.let { it != binding.id } == true
                                    } ?: false
                                }
                                ?.singleOrNull()

                            if (content == null) {
                                // Zero or several unclaimed candidates: guessing could send
                                // the resume into an unrelated user terminal. Leave the new
                                // tab as-is; the user can bind it manually.
                                logger.info("Recreated tab '$tabName' could not be identified unambiguously - skipping resume")
                            } else {
                                val tabId = TerminalHelper.getOrCreateTabId(content)
                                val terminalView = TerminalHelper.findTerminalViewByTabId(project, tabId)
                                val context = TerminalTabContext(
                                    tabName = tabName,
                                    terminalView = terminalView,
                                    shellWidget = widget,
                                    content = content,
                                    tabId = tabId,
                                    persistentName = tabName
                                )
                                val success = TerminalHelper.executeCommandInTerminal(project, context, binding.currentSessionId)
                                if (success) {
                                    bindingService.attachTabUuid(binding.id, tabId, tabName)
                                    bindingService.markBindingResumed(binding.id)
                                    TerminalHelper.setClaudeIconOnContent(content)
                                    TerminalHelper.protectTabTitleIfEnabled(project, content)
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
        NotificationHelper.show(project, message, type)
    }
}
