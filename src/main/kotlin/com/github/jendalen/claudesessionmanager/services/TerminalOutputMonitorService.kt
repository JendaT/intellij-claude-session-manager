package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.TabProfiles
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.Content
import java.beans.PropertyChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Monitors terminal output for Claude session IDs.
 *
 * This service watches terminal output for session ID patterns and automatically:
 * 1. Binds unbound terminals to new sessions when Claude starts
 * 2. Updates existing bindings when session changes (via /resume, /clear, etc.)
 */
@Service(Service.Level.PROJECT)
class TerminalOutputMonitorService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(TerminalOutputMonitorService::class.java)

    // Constants for buffer and timing
    companion object {
        private const val MAX_BUFFER_SIZE = 10000
        private const val BUFFER_TRIM_SIZE = 5000
        private const val UUID_OVERLAP_SIZE = 35  // UUID is 36 chars; keep 35 for boundary spanning
        private const val MAX_PENDING_VERIFICATIONS = 5
        private const val RECENT_SESSION_THRESHOLD_MS = 30000L
        private const val SESSION_VERIFICATION_DELAY_MS = 1500L
        private const val LOG_TRUNCATE_LENGTH = 100

        // Claude Code updates its OSC title continuously while working; once updates stop
        // for this long, the tab's activity badge reverts to the plain Claude icon.
        private const val ACTIVITY_IDLE_MS = 5000

        fun getInstance(project: Project): TerminalOutputMonitorService {
            return project.getService(TerminalOutputMonitorService::class.java)
        }
    }

    // UUID pattern for session IDs
    private val sessionIdPattern = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        Pattern.CASE_INSENSITIVE
    )

    // Track monitored tabs to avoid duplicate listeners.
    // Keyed by the stable per-tab id (Content UserData), NOT the displayName - the name is
    // overwritten by Claude Code's title takeover and is no longer a stable key.
    private val monitoredTabs = ConcurrentHashMap<String, TabMonitor>()

    // Coroutine scope for async operations - use SupervisorJob for proper cancellation
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track if service is active
    @Volatile
    private var isActive = false

    // Guards against stacking tabs-manager listeners across stop/start cycles
    // (they can only be released by disposing the service).
    @Volatile
    private var tabsManagerListenerRegistered = false

    // Prunes tab monitors when their terminal tab is closed
    private var contentRemovalListener: com.intellij.ui.content.ContentManagerListener? = null

    /**
     * Starts monitoring terminal output. May be called from any thread; the actual setup
     * runs on the EDT (tool window, content manager, and tabs-manager access).
     */
    fun startMonitoring() {
        if (isActive) {
            logger.debug("Terminal output monitoring already active")
            return
        }
        ApplicationManager.getApplication().invokeLater { doStartMonitoring() }
    }

    private fun doStartMonitoring() {
        if (isActive) return

        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)

            // Register listener for new tabs (once per service lifetime)
            if (!tabsManagerListenerRegistered) {
                tabsManager.addListener(this, object : TerminalTabsManagerListener {
                    override fun tabAdded(tab: TerminalToolWindowTab) {
                        logger.info("New terminal tab added: ${tab.content.displayName}")
                        monitorTab(tab)
                    }
                })
                tabsManagerListenerRegistered = true
            }

            // Prune monitors when tabs close - otherwise buffers and listeners accumulate
            // for the life of the project as tabs are opened and closed.
            val listener = object : com.intellij.ui.content.ContentManagerListener {
                override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                    TerminalHelper.getTabId(event.content)?.let { stopMonitoringTab(it) }
                }
            }
            TerminalHelper.getTerminalToolWindow(project)?.contentManager?.addContentManagerListener(listener)
            contentRemovalListener = listener

            // Monitor existing tabs
            for (tab in tabsManager.tabs) {
                monitorTab(tab)
            }

            isActive = true
            logger.info("Started terminal output monitoring for ${tabsManager.tabs.size} tabs")

        } catch (e: Exception) {
            logger.warn("Failed to start terminal output monitoring: ${e.message}", e)
        }
    }

    /**
     * Starts monitoring a specific terminal tab for session IDs.
     */
    private fun monitorTab(tab: TerminalToolWindowTab) {
        val content = tab.content

        // Stable id is the monitoring key; survives Claude Code renaming the tab.
        val tabId = TerminalHelper.getOrCreateTabId(content)
        val tabName = content.displayName ?: "Terminal"

        // Avoid duplicate monitoring
        if (monitoredTabs.containsKey(tabId)) {
            logger.debug("Tab '$tabName' (id=$tabId) already monitored")
            return
        }

        // Declared outside the try so the failure path can release them: a monitorTab
        // that failed halfway used to leave its disposable registered under the service
        // and its rename listener on the Content, and the retry added a second listener.
        var tabDisposable: Disposable? = null
        var renameListener: PropertyChangeListener? = null
        try {
            // Per-tab disposable created first so every listener below is tied to it and
            // released together when the tab closes (see stopMonitoringTab).
            val disposable = Disposer.newDisposable("TerminalOutputMonitor-$tabId")
            Disposer.register(this, disposable)
            tabDisposable = disposable

            // Listen for tab renames purely to keep the monitor's display label current for logs.
            // Renames no longer affect binding identity (that is keyed on the stable id).
            val listener = PropertyChangeListener { evt ->
                if (evt.propertyName == Content.PROP_DISPLAY_NAME) {
                    val newName = evt.newValue as? String ?: return@PropertyChangeListener
                    monitoredTabs[tabId]?.tabName = newName
                }
            }
            content.addPropertyChangeListener(listener)
            renameListener = listener

            // Create tab monitor
            val tabMonitor = TabMonitor(
                tabId = tabId,
                tabName = tabName,
                tab = tab,
                disposable = disposable,
                content = content,
                renameListener = listener
            )
            tabMonitor.activityAlarm = com.intellij.util.Alarm(
                com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, disposable
            )
            monitoredTabs[tabId] = tabMonitor

            // Keep the binding's restart-stable persistent name in sync with user renames,
            // and enforce tab-title protection on the reworked terminal.
            // NOTE: onTitleChanged fires on EVERY title change - including Claude Code's OSC
            // spinner updates at several Hz - and on arbitrary threads. The last-seen cache
            // filters the noise (the derived persistent name only changes on real renames),
            // and the binding write is dispatched to the EDT like all other binding writers.
            try {
                tab.view.title.addTitleListener(object : com.intellij.terminal.TerminalTitleListener {
                    @Volatile
                    private var lastSeenPersistentName: String? = null

                    override fun onTitleChanged(terminalTitle: com.intellij.terminal.TerminalTitle) {
                        // The reworked terminal applies OSC application titles without honoring
                        // trackTerminalApplicationTitleChanges, so protection is enforced here:
                        // clear the title whenever it reappears on a protected tab. The nested
                        // change{} re-fires this listener once with applicationTitle == null,
                        // which is a no-op - no loop. The suppressed title still carries
                        // Claude's live status, which is re-surfaced as tab tooltip + icon
                        // activity badge instead of a tab rename.
                        val appTitle = terminalTitle.applicationTitle
                        if (appTitle != null && TerminalHelper.isTitleProtected(content)) {
                            terminalTitle.change { applicationTitle = null }
                            reportClaudeActivity(tabMonitor, appTitle)
                            return
                        }

                        val persistentName = TerminalHelper.persistentNameOf(terminalTitle) ?: return
                        if (persistentName == lastSeenPersistentName) return
                        lastSeenPersistentName = persistentName
                        val userDefined = !terminalTitle.userDefinedTitle.isNullOrBlank()
                        ApplicationManager.getApplication().invokeLater {
                            val bindingService = BindingService.getInstance(project)
                            val binding = bindingService.findBindingByTabUuid(tabId)
                            if (binding != null && binding.tabIdentifier.persistentName != persistentName) {
                                bindingService.updatePersistentName(binding.id, persistentName)
                                // External readers see the rename too.
                                val sessionId = binding.currentSessionId
                                val projectName = project.name
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    SessionRegistryService.getInstance().upsert(sessionId, projectName, persistentName)
                                }
                                // And so does the Claude session, when it can be typed into.
                                SessionTitleSyncService.getInstance(project).onTabRenamed(tabId, persistentName, userDefined)
                            }
                        }
                    }
                }, disposable)
            } catch (e: Exception) {
                logger.warn("Could not attach title listener for '$tabName' - title protection and persistent-name sync are off for this tab: ${e.message}")
            }

            // Monitor the active output model via StateFlow. collectLatest replays the
            // current value, so no separate initial registration is needed - doubling it
            // up used to duplicate every output chunk in the scan buffer. The job is kept
            // on the monitor and cancelled on tab close: the StateFlow never completes, so
            // an uncancelled collector would pin the closed tab's object graph forever.
            val outputModels = tab.view.outputModels
            tabMonitor.collectorJob = scope.launch {
                try {
                    outputModels.active.collectLatest { activeModel ->
                        logger.debug("Active output model changed for tab '$tabName'")
                        registerOutputListener(tabMonitor, activeModel)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Output model flow collection ended for '$tabName' - the output channel is off for this tab: ${e.message}")
                }
            }

            logger.info("Started monitoring terminal tab: '$tabName'")

        } catch (e: Exception) {
            // The map entry went in before the listeners were attached; leaving it makes
            // every later monitorTab() for this tab a silent no-op ("already monitored")
            // for the life of the project. Drop it so the next tabAdded/start can retry.
            monitoredTabs.remove(tabId)
            renameListener?.let { content.removePropertyChangeListener(it) }
            tabDisposable?.let { Disposer.dispose(it) }
            logger.warn("Failed to monitor tab '$tabName': ${e.message}", e)
        }
    }

    /**
     * Re-surfaces the suppressed OSC title as non-intrusive progress: the tab keeps its
     * stable name and colored Claude icon, gains a live-activity badge while Claude works,
     * and shows Claude's current status text as the tab tooltip. The badge reverts after
     * [ACTIVITY_IDLE_MS] without title updates.
     *
     * Called from the title listener on arbitrary threads at several Hz (Claude's spinner);
     * the alarm reset is thread-safe and cheap, UI mutations are dispatched to the EDT only
     * when the visible state actually changes.
     */
    private fun reportClaudeActivity(tabMonitor: TabMonitor, rawTitle: String) {
        val alarm = tabMonitor.activityAlarm
        if (alarm != null && !alarm.isDisposed) {
            alarm.cancelAllRequests()
            alarm.addRequest({
                tabMonitor.iconActive = false
                TerminalHelper.setClaudeIconActivity(tabMonitor.content, false)
            }, ACTIVITY_IDLE_MS)
        }

        // Strip Claude's leading spinner glyph ("* Doing X..." -> "Doing X...")
        val status = rawTitle.dropWhile { !it.isLetterOrDigit() }.trim()
        if (status == tabMonitor.lastStatus && tabMonitor.iconActive) return

        ApplicationManager.getApplication().invokeLater {
            if (status.isNotEmpty() && status != tabMonitor.lastStatus) {
                tabMonitor.lastStatus = status
                try {
                    // Content.description is the tab tooltip and the platform renders it as
                    // HTML; the status comes from an OSC title any process in the tab can set.
                    // Same class as the notification <img src> fetch fixed in run 1.
                    tabMonitor.content.description = "Claude: " + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(status)
                } catch (e: Exception) {
                    logger.debug("Failed to set tab tooltip: ${e.message}")
                }
            }
            if (!tabMonitor.iconActive) {
                tabMonitor.iconActive = true
                TerminalHelper.setClaudeIconActivity(tabMonitor.content, true)
            }
        }
    }

    /**
     * Registers an output listener on a terminal output model, replacing the listener that
     * was attached to the previously active model. The terminal switches between main and
     * alternate screen buffers (Claude Code's TUI does this constantly); keeping exactly one
     * live listener prevents unbounded listener accumulation and duplicated buffer content.
     */
    private fun registerOutputListener(tabMonitor: TabMonitor, outputModel: TerminalOutputModel) {
        val tabName = tabMonitor.tabName

        try {
            tabMonitor.modelDisposable?.let { Disposer.dispose(it) }
            val modelDisposable = Disposer.newDisposable("TerminalOutputListener-${tabMonitor.tabId}")
            Disposer.register(tabMonitor.disposable, modelDisposable)
            tabMonitor.modelDisposable = modelDisposable

            outputModel.addListener(modelDisposable, object : TerminalOutputModelListener {
                override fun afterContentChanged(event: TerminalContentChangeEvent) {
                    // Cheap checks first: newText is a copy from the first changed line to
                    // the end of the buffer, and this fires tens of times a second on the
                    // EDT. Type-ahead and trimming events used to pay for that copy and
                    // then discard it.
                    if (event.isTypeAhead) return   // user typing preview
                    if (event.isTrimming) return    // scrollback cleanup

                    val newText = event.newText?.toString() ?: return

                    // Never log raw terminal content - it routinely contains secrets and
                    // idea.log ends up attached to bug reports. Length is enough for tracing.
                    if (logger.isDebugEnabled) {
                        logger.debug("Content changed in '$tabName': ${newText.length} chars")
                    }

                    // Process the new text for session IDs
                    processTerminalOutput(tabMonitor, newText)
                }
            })

            logger.debug("Registered output listener for tab '$tabName'")

        } catch (e: Exception) {
            logger.warn("Failed to register output listener for '$tabName' - the output channel is off for this tab: ${e.message}")
        }
    }

    /**
     * Processes terminal output looking for session IDs.
     * Only scans new text (plus a small overlap for UUIDs spanning chunk boundaries).
     */
    private fun processTerminalOutput(tabMonitor: TabMonitor, text: String) {
        if (text.isBlank()) return

        val tabName = tabMonitor.tabName

        // Build the scan region: overlap from previous buffer tail + new text.
        // A UUID is 36 chars, so keep 35 chars of overlap for boundary spanning.
        val overlap = if (tabMonitor.textBuffer.length > UUID_OVERLAP_SIZE) {
            tabMonitor.textBuffer.substring(tabMonitor.textBuffer.length - UUID_OVERLAP_SIZE)
        } else {
            tabMonitor.textBuffer.toString()
        }
        val scanRegion = overlap + text

        // Update the buffer (keep only recent tail for next overlap)
        tabMonitor.textBuffer.append(text)
        if (tabMonitor.textBuffer.length > MAX_BUFFER_SIZE) {
            tabMonitor.textBuffer.delete(0, tabMonitor.textBuffer.length - BUFFER_TRIM_SIZE)
        }

        // Scan only the new region for UUIDs
        val uuidMatcher = sessionIdPattern.matcher(scanRegion)
        val foundUuids = mutableSetOf<String>()

        while (uuidMatcher.find()) {
            foundUuids.add(uuidMatcher.group().lowercase())
        }

        // The tab's own bound id sits on screen for as long as Claude runs; it must not
        // cost a getSessions() per redraw.
        foundUuids.remove(tabMonitor.lastDetectedSessionId)
        if (foundUuids.isEmpty()) return

        // Check each UUID against sessions-index.json
        val sessionService = ClaudeSessionService.getInstance(project)
        val sessions = sessionService.getSessions()
        val sessionIds = sessions.associateBy { it.sessionId.lowercase() }

        for (uuid in foundUuids) {

            // Check if this UUID matches a known session
            val matchingSession = sessionIds[uuid]
            if (matchingSession != null) {
                // Check if this is a "recent" session (modified in last 30 seconds)
                val now = System.currentTimeMillis()
                val isRecentSession = (now - matchingSession.fileMtime) < RECENT_SESSION_THRESHOLD_MS

                if (isRecentSession) {
                    logger.info("Found valid session ID in terminal '$tabName': $uuid (recent=true)")
                    handleDetectedSessionId(tabMonitor, uuid)

                    // Clear buffer after successful detection
                    tabMonitor.textBuffer.clear()
                    return
                }
            }
        }

        // If we found UUIDs but none matched, they might be new sessions
        // Schedule delayed check for new sessions
        if (foundUuids.isNotEmpty()) {
            val newUuids = foundUuids - sessionIds.keys
            if (newUuids.isNotEmpty()) {
                logger.debug("Found ${newUuids.size} potential new session IDs in '$tabName', scheduling verification")
                for (uuid in newUuids.take(1)) {  // Only check the first potential new UUID
                    scheduleSessionVerification(tabMonitor, uuid)
                }
            }
        }
    }

    /**
     * Handles a detected session ID - creates or updates binding.
     * Binds immediately without requiring sessions-index.json verification,
     * matching the approach used by ClaudeHookEventService.
     */
    private fun handleDetectedSessionId(tabMonitor: TabMonitor, sessionId: String) {
        val tabName = tabMonitor.tabName

        // Debounce: don't process the same session ID repeatedly
        val lastSessionId = tabMonitor.lastDetectedSessionId
        if (lastSessionId == sessionId) {
            logger.debug("Session ID '$sessionId' already processed for '$tabName'")
            return
        }
        tabMonitor.lastDetectedSessionId = sessionId

        // Try to find session metadata for richer notifications, but don't block on it
        val sessionService = ClaudeSessionService.getInstance(project)
        val session = sessionService.findSessionByIdGlobal(sessionId)

        // Bind immediately - the detected UUID is authoritative
        updateBindingForSession(tabMonitor, sessionId, session)

        // If session wasn't in index, log when it appears
        if (session == null) {
            logger.info("Session $sessionId not yet in index (binding created anyway)")
            scope.launch {
                try {
                    kotlinx.coroutines.delay(SESSION_VERIFICATION_DELAY_MS)
                    // No cache wipe: the per-file mtime check already re-reads a changed
                    // index, and this loop only logs. Wiping made the EDT's next
                    // getSessions() re-read every index file from disk.
                    val found = sessionService.findSessionByIdGlobal(sessionId)
                    if (found != null) {
                        logger.info("Session $sessionId appeared in index after delay")
                    } else {
                        logger.debug("Session $sessionId still not in index after delay")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.info("Index re-check for $sessionId failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Schedules a delayed verification and binding of a potential session ID.
     * Unlike handleDetectedSessionId (which binds immediately for confirmed UUIDs),
     * this waits briefly for the session to appear in the index before binding,
     * since these are UUIDs found in terminal output that may not be session IDs at all.
     */
    private fun scheduleSessionVerification(tabMonitor: TabMonitor, sessionId: String) {
        if (tabMonitor.pendingVerifications.size >= MAX_PENDING_VERIFICATIONS) return // too many pending
        if (!tabMonitor.pendingVerifications.add(sessionId)) return // already pending

        scope.launch {
            try {
                kotlinx.coroutines.delay(SESSION_VERIFICATION_DELAY_MS)

                val sessionService = ClaudeSessionService.getInstance(project)
                sessionService.invalidateCache()
                val session = sessionService.findSessionByIdGlobal(sessionId)
                val sessionFile = if (session == null) ClaudePathResolver.findSessionFile(sessionId) else null

                if (session == null && sessionFile == null) {
                    logger.debug("UUID $sessionId is not a known Claude session")
                    return@launch
                }

                // A UUID in this tab's output is not proof that the session runs in this
                // tab: `cat` of a registry file, a grep through a log, an orchestrator
                // listing its workers - all print other sessions' ids, and every one of
                // them used to rebind the tab (observed 2026-09-19: an IDEA tab rebound to
                // two Warp sessions from another repo, which then had that tab written
                // into their registry files). The hook channel already refuses an event
                // whose cwd is outside the project; this channel may MOVE a session, so
                // it must be at least as strict. Without a cwd there is nothing to check
                // against, and a session this channel cannot place is not bound by it.
                val cwd = resolveSessionCwd(sessionId, session, sessionFile)
                // Under this tab's root: its tab profile's directory, else the project
                // folder (so with no profiles, exactly the old project check).
                val profile = TabProfileService.getInstance(project)
                    .profileFor(TerminalHelper.persistentNameOf(tabMonitor.tab.view.title))
                val root = TabProfiles.rootFor(project.basePath, profile)
                if (cwd == null || root == null || !TabProfiles.isUnder(root, cwd)) {
                    logger.info("Output-channel bind skipped: session $sessionId runs in '${cwd ?: "unknown cwd"}', " +
                        "not under '$root' (tab='${tabMonitor.tabName}'${profile?.let { ", profile" } ?: ""})")
                    return@launch
                }

                tabMonitor.lastDetectedSessionId = sessionId.lowercase()
                updateBindingForSession(tabMonitor, session?.sessionId ?: sessionId, session)
                if (session == null) {
                    logger.info("Bound session $sessionId found via JSONL file (not in any index)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cwd lookup reads registry and transcript files; an I/O failure
                // there is a skipped verification, not an IDE error report.
                logger.warn("Session verification failed for $sessionId in '${tabMonitor.tabName}': ${e.message}")
            } finally {
                tabMonitor.pendingVerifications.remove(sessionId)
            }
        }
    }

    /**
     * Where the session runs, from the cheapest source that has it: the index entry's
     * project path, then the registry file the hook wrote (exact cwd, present once the
     * session has started), then the transcript's own records. Null when none of them
     * knows - the caller treats that as "cannot place", not "anywhere".
     */
    private fun resolveSessionCwd(
        sessionId: String,
        indexed: com.github.jendalen.claudesessionmanager.model.ClaudeSession?,
        sessionFile: java.io.File?
    ): String? {
        indexed?.projectPath?.takeIf { it.isNotBlank() }?.let { return it }
        SessionRegistryService.getInstance().read(sessionId)?.cwd?.takeIf { it.isNotBlank() }?.let { return it }
        val file = sessionFile ?: ClaudePathResolver.findSessionFile(sessionId) ?: return null
        return ClaudePathResolver.readSessionCwd(file)
    }

    /**
     * Updates or creates a binding for the detected session via the central bind path.
     * The binding lookup runs inside the EDT block (same thread as all other binding
     * writers) to avoid acting on a stale snapshot.
     */
    private fun updateBindingForSession(
        tabMonitor: TabMonitor,
        sessionId: String,
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession?
    ) {
        ApplicationManager.getApplication().invokeLater {
            TabBindingCoordinator.getInstance(project).bindOrUpdate(
                content = tabMonitor.tab.content,
                sessionId = sessionId,
                source = "output",
                displayInfo = session?.displaySummary()
            )
        }
    }

    /**
     * Stops monitoring a specific tab, identified by its stable tab id.
     */
    fun stopMonitoringTab(tabId: String) {
        val monitor = monitoredTabs.remove(tabId)
        if (monitor != null) {
            monitor.collectorJob?.cancel()
            monitor.content.removePropertyChangeListener(monitor.renameListener)
            Disposer.dispose(monitor.disposable)
            logger.debug("Stopped monitoring tab '${monitor.tabName}' (id=$tabId)")
        }
    }

    /**
     * Stops all terminal output monitoring.
     */
    fun stopMonitoring() {
        isActive = false
        contentRemovalListener?.let { listener ->
            try {
                TerminalHelper.getTerminalToolWindow(project)?.contentManager?.removeContentManagerListener(listener)
            } catch (e: Exception) {
                logger.debug("Error removing content manager listener: ${e.message}")
            }
        }
        contentRemovalListener = null
        for ((_, monitor) in monitoredTabs) {
            try {
                monitor.collectorJob?.cancel()
                monitor.content.removePropertyChangeListener(monitor.renameListener)
                Disposer.dispose(monitor.disposable)
            } catch (e: Exception) {
                logger.debug("Error disposing monitor for '${monitor.tabName}': ${e.message}")
            }
        }
        monitoredTabs.clear()
        logger.info("Stopped terminal output monitoring")
    }

    override fun dispose() {
        stopMonitoring()
        scope.cancel()  // Cancel all coroutines to prevent resource leaks
    }

    /**
     * Class to track monitoring state for a tab.
     */
    private class TabMonitor(
        val tabId: String,
        @Volatile var tabName: String,
        val tab: TerminalToolWindowTab,
        val disposable: Disposable,
        val content: Content,
        val renameListener: PropertyChangeListener,
        @Volatile var modelDisposable: Disposable? = null,
        @Volatile var collectorJob: kotlinx.coroutines.Job? = null,
        @Volatile var lastDetectedSessionId: String? = null,
        val textBuffer: StringBuilder = StringBuilder(),
        val pendingVerifications: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        // Progress surfacing (suppressed OSC title -> tooltip + icon badge)
        @Volatile var activityAlarm: com.intellij.util.Alarm? = null,
        @Volatile var lastStatus: String? = null,
        @Volatile var iconActive: Boolean = false
    )
}
