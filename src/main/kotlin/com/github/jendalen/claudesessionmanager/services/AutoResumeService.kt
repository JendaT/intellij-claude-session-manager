package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.terminal.TerminalTabContext
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.view.TerminalViewSessionState
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

        // Eager shell start for restored tabs (see startShellsForRestoredTabs): dwell per
        // tab while it is selected, how long to wait for the sessions to start, passes.
        private const val TAB_SHOW_DWELL_MS = 150L
        private const val TAB_SHOW_SETTLE_MS = 250L
        private const val SHELL_START_BUDGET_MS = 10000L
        private const val SHELL_START_PASSES = 2
    }

    private enum class ResumeOutcome { RESUMED, MISSING, FAILED }

    override suspend fun execute(project: Project) {
        val settings = SettingsService.getInstance()

        val delayMs = settings.autoResumeDelayMs
        logger.info("Binding re-attachment starts after ${delayMs}ms delay (autoResume=${settings.autoResumeEnabled})")
        // Backstop for registry files whose SessionEnd never fired (killed IDE).
        try {
            SessionRegistryService.getInstance().sweepOnce()
        } catch (e: Exception) {
            logger.warn("Session registry sweep failed: ${e.message}")
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled activity must not carry on into the resume loop as if it had
            // merely found no live tabs.
            throw e
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
        val resumedContents = mutableListOf<Content>()
        val missingBindings = mutableListOf<SessionBinding>()
        val staleBindings = mutableListOf<SessionBinding>()
        var failedCount = 0
        var runningElsewhereCount = 0
        val handledSessionIds = mutableSetOf<String>()

        // Resume each binding
        for (binding in bindings) {
            // A session runs in exactly one tab - never send the same resume twice
            // (duplicate bindings used to type the command into the running Claude).
            if (!handledSessionIds.add(binding.currentSessionId.lowercase())) {
                logger.info("Skipping duplicate auto-resume of session ${binding.currentSessionId} (binding ${binding.id})")
                continue
            }
            // Resume the newest session of this binding that actually has a transcript.
            // The current id alone is not enough: claude --resume forks the conversation
            // under a NEW session id, and if nothing is typed afterwards that fork is never
            // written to disk - the real conversation is then the previous id in history.
            // No id with a transcript at all means genuinely purged - offer recovery.
            val resumableSessionId = resolveResumableSessionId(binding)
            if (resumableSessionId == null) {
                logger.info("No session of binding ${binding.id} ('${binding.tabIdentifier.effectiveName}') has a transcript on disk - skipping resume")
                staleBindings.add(binding)
                continue
            }
            if (resumableSessionId != binding.currentSessionId) {
                logger.info("Falling back to session $resumableSessionId for binding ${binding.id} " +
                    "(current ${binding.currentSessionId} was never persisted - empty resume fork)")
                handledSessionIds.add(resumableSessionId.lowercase())
            }
            // Bindings are per project per IDE, and the same session can be bound in two
            // of them (a stale binding survives in the project the session was moved out
            // of). Typing `--resume` into the second tab starts a second process on the
            // same transcript - observed 2026-09-19, WebStorm resuming a session IDEA had
            // been running for a day. The registry file already says whether a process
            // is running; ask it before typing anything. The tab is left exactly as the
            // IDE restored it - no icon, no command - so the user sees a plain shell.
            val owner = SessionRegistryService.getInstance().read(resumableSessionId)
            if (owner != null && owner.isLive()) {
                logger.info("Not resuming session $resumableSessionId in tab '${binding.tabIdentifier.effectiveName}': " +
                    "its process (pid ${owner.pid}) is alive in ${owner.cwd ?: "an unknown directory"}" +
                    (owner.project?.let { " (project '$it', tab '${owner.tab ?: "?"}')" } ?: ""))
                runningElsewhereCount++
                continue
            }
            val (outcome, content) = resumeBinding(project, binding, bindingService, resumableSessionId)
            when (outcome) {
                ResumeOutcome.RESUMED -> {
                    resumedCount++
                    content?.let { resumedContents.add(it) }
                }
                ResumeOutcome.MISSING -> missingBindings.add(binding)
                ResumeOutcome.FAILED -> failedCount++
            }
        }
        if (runningElsewhereCount > 0) {
            logger.info("$runningElsewhereCount binding(s) left alone: their sessions are running in another IDE or terminal")
        }

        // The resume commands are only queued for tabs whose shell has not started.
        failedCount += startShellsForRestoredTabs(project, resumedContents)

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
     * Returns the newest session id of this binding whose transcript exists on disk
     * (see [SessionBinding.resumableSessionId]). Null when none exists.
     */
    private fun resolveResumableSessionId(binding: SessionBinding): String? {
        return binding.resumableSessionId { ClaudePathResolver.sessionFileExists(it) }
    }

    /**
     * Resolves the terminal tab for a binding and sends the resume command for [sessionId]
     * (usually the binding's current session, possibly an older one from history when the
     * current id was an unpersisted resume fork). Runs entirely on the EDT - tab resolution
     * touches ToolWindow/ContentManager/tabs-manager state.
     */
    private suspend fun resumeBinding(
        project: Project,
        binding: SessionBinding,
        bindingService: BindingService,
        sessionId: String = binding.currentSessionId
    ): Pair<ResumeOutcome, Content?> = withContext(Dispatchers.EDT) {
        val targetName = binding.tabIdentifier.effectiveName
        try {
            val content = resolveContentForBinding(project, binding, bindingService)
            if (content == null) {
                logger.info("Terminal tab '$targetName' not found (or ambiguous) - adding to missing list")
                return@withContext ResumeOutcome.MISSING to null
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

            val success = TerminalHelper.executeCommandInTerminal(project, context, sessionId)
            if (success) {
                bindingService.markBindingResumed(binding.id)
                TabBindingCoordinator.getInstance(project).applyTabSideEffects(content)
                logger.info("Auto-resumed session $sessionId in tab '$displayName'")
                ResumeOutcome.RESUMED to content
            } else {
                logger.warn("Failed to resume session $sessionId in tab '$displayName'")
                ResumeOutcome.FAILED to content
            }
        } catch (e: Exception) {
            logger.error("Error resuming session $sessionId: ${e.message}", e)
            ResumeOutcome.FAILED to null
        }
    }

    /**
     * Makes every resumed tab actually start its shell. The reworked terminal restores
     * tabs lazily: TransferableTerminalSessionImpl schedules the shell start with
     * initOnShow(view.component), so a restored tab's process starts only when its
     * component is first shown, and TerminalView.sendText is buffered until then.
     * Observed 2026-09-15: 24 resume commands were queued within 20 s, but only the
     * selected tab of each project launched Claude; the rest launched 3-25 min later, on
     * the user's click. For unattended operation each not-yet-started tab is selected
     * once (the start is scheduled on the first showing and is NonCancellable, so the
     * tab can be deselected right away) and the original selection is restored.
     *
     * @return number of tabs whose shell still had not started within the budget
     */
    private suspend fun startShellsForRestoredTabs(project: Project, contents: List<Content>): Int {
        if (contents.isEmpty()) return 0
        val pending = withContext(Dispatchers.EDT) { contents.filter { !isShellStarted(project, it) } }
        if (pending.isEmpty()) return 0
        logger.info("${pending.size} restored tab(s) have not started their shell - showing each once")

        for (pass in 1..SHELL_START_PASSES) {
            val stillPending = withContext(Dispatchers.EDT) { pending.filter { !isShellStarted(project, it) } }
            if (stillPending.isEmpty()) break
            showEachOnce(project, stillPending)
            val deadline = System.currentTimeMillis() + SHELL_START_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                val notStarted = withContext(Dispatchers.EDT) { stillPending.filter { !isShellStarted(project, it) } }
                if (notStarted.isEmpty()) break
                delay(TAB_SHOW_SETTLE_MS)
            }
        }

        val notStarted = withContext(Dispatchers.EDT) { pending.filter { !isShellStarted(project, it) } }
        for (content in pending - notStarted.toSet()) {
            logger.info("Started shell for restored tab '${content.displayName}'")
        }
        for (content in notStarted) {
            logger.warn("Shell of restored tab '${content.displayName}' did not start within ${SHELL_START_BUDGET_MS}ms - its resume is still queued")
        }
        return notStarted.size
    }

    private fun isShellStarted(project: Project, content: Content): Boolean {
        val tabId = TerminalHelper.getTabId(content) ?: return true
        val view = TerminalHelper.findTerminalViewByTabId(project, tabId) ?: return true
        return view.sessionState.value !is TerminalViewSessionState.NotStarted
    }

    /**
     * Selects each content for one EDT cycle so its component becomes showing, then
     * restores the previous selection. A hidden tool window is shown for the duration
     * (initOnShow needs the component on screen) and hidden again afterwards.
     */
    private suspend fun showEachOnce(project: Project, contents: List<Content>) {
        val toolWindow = withContext(Dispatchers.EDT) { TerminalHelper.getTerminalToolWindow(project) } ?: return
        val contentManager = toolWindow.contentManager
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val (original, wasVisible, previouslyActive) = withContext(Dispatchers.EDT) {
            val visible = toolWindow.isVisible
            val active = toolWindowManager.activeToolWindowId
            if (!visible) toolWindow.show()
            Triple(contentManager.selectedContent, visible, active)
        }
        try {
            for (content in contents) {
                withContext(Dispatchers.EDT) {
                    if (contentManager.getIndexOfContent(content) >= 0) {
                        contentManager.setSelectedContent(content, false)
                    }
                }
                // The hierarchy listener behind initOnShow runs on the event queue; give it
                // a dispatched turn before moving on.
                delay(TAB_SHOW_DWELL_MS)
            }
        } finally {
            withContext(Dispatchers.EDT) {
                if (original != null && contentManager.getIndexOfContent(original) >= 0) {
                    contentManager.setSelectedContent(original, false)
                }
                if (!wasVisible) {
                    // Put back whatever was in front: another tool window in the same
                    // group, or nothing.
                    val previous = previouslyActive?.takeIf { it != toolWindow.id }
                        ?.let { toolWindowManager.getToolWindow(it) }
                    if (previous != null) previous.activate(null, false) else toolWindow.hide()
                }
            }
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

        // Through the escaping choke point: tab names are OSC-settable by anything in
        // the terminal and the notification renders HTML.
        val notification = NotificationHelper.create("Claude Sessions", message, NotificationType.WARNING)

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

        // Through the escaping choke point: tab names are OSC-settable by anything in
        // the terminal and the notification renders HTML.
        val notification = NotificationHelper.create("Claude Sessions", message, NotificationType.WARNING)

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
            if (TerminalHelper.executeCommand(context, TerminalHelper.buildStartCommand(project))) {
                TabBindingCoordinator.getInstance(project).applyTabSideEffects(content)
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
            // Same fallback as the resume loop: the current id may be an unpersisted fork.
            val resumableSessionId = resolveResumableSessionId(binding)
            if (resumableSessionId == null) {
                logger.info("No session of binding ${binding.id} ('$tabName') has a transcript - not recreating its tab")
                continue
            }
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
                                // The restart-stable name comes from the TerminalTitle, never
                                // from a display-name snapshot (getPersistentName's rule). The
                                // tab was just created with tabName, so the fallback is only
                                // for a title that cannot be resolved at all.
                                val persistentName = TerminalHelper.getPersistentName(project, content) ?: tabName
                                val context = TerminalTabContext(
                                    tabName = tabName,
                                    terminalView = terminalView,
                                    shellWidget = widget,
                                    content = content,
                                    tabId = tabId,
                                    persistentName = persistentName
                                )
                                val success = TerminalHelper.executeCommandInTerminal(project, context, resumableSessionId)
                                if (success) {
                                    bindingService.attachTabUuid(binding.id, tabId, persistentName)
                                    bindingService.markBindingResumed(binding.id)
                                    TabBindingCoordinator.getInstance(project).applyTabSideEffects(content)
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
