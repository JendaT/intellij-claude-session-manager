package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.TabIdentifier
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
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
    private var isActive = false

    /**
     * Starts monitoring terminal output.
     */
    fun startMonitoring() {
        if (isActive) {
            logger.debug("Terminal output monitoring already active")
            return
        }

        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)

            // Register listener for new tabs
            tabsManager.addListener(this, object : TerminalTabsManagerListener {
                override fun tabAdded(tab: TerminalToolWindowTab) {
                    logger.info("New terminal tab added: ${tab.content.displayName}")
                    monitorTab(tab)
                }
            })

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

        // Listen for tab renames purely to keep the monitor's display label current for logs.
        // Renames no longer affect binding identity (that is keyed on the stable id).
        val renameListener = PropertyChangeListener { evt ->
            if (evt.propertyName == Content.PROP_DISPLAY_NAME) {
                val newName = evt.newValue as? String ?: return@PropertyChangeListener
                monitoredTabs[tabId]?.tabName = newName
            }
        }
        content.addPropertyChangeListener(renameListener)

        // Keep the binding's restart-stable persistent name in sync with user renames.
        // The persistent name is userDefinedTitle ?: defaultTitle - Claude Code's OSC
        // application-title changes never affect it, so this fires meaningfully only when
        // the user actually renames the tab (or the default title changes).
        try {
            val title = tab.view.title
            title.addTitleListener(object : com.intellij.terminal.TerminalTitleListener {
                override fun onTitleChanged(terminalTitle: com.intellij.terminal.TerminalTitle) {
                    val persistentName = terminalTitle.userDefinedTitle ?: terminalTitle.defaultTitle ?: return
                    val binding = BindingService.getInstance(project).findBindingByTabUuid(tabId)
                    if (binding != null && binding.tabIdentifier.persistentName != persistentName) {
                        BindingService.getInstance(project).updatePersistentName(binding.id, persistentName)
                    }
                }
            }, this)
        } catch (e: Exception) {
            logger.debug("Could not attach title listener for '$tabName': ${e.message}")
        }

        try {
            val terminalView = tab.view
            val outputModels = terminalView.outputModels

            // Create a disposable for this tab's monitoring
            val tabDisposable = Disposer.newDisposable("TerminalOutputMonitor-$tabId")
            Disposer.register(this, tabDisposable)

            // Create tab monitor
            val tabMonitor = TabMonitor(
                tabId = tabId,
                tabName = tabName,
                tab = tab,
                disposable = tabDisposable
            )
            monitoredTabs[tabId] = tabMonitor

            // Monitor the active output model via StateFlow
            scope.launch {
                try {
                    outputModels.active.collectLatest { activeModel ->
                        logger.debug("Active output model changed for tab '$tabName'")
                        registerOutputListener(tabMonitor, activeModel)
                    }
                } catch (e: Exception) {
                    logger.debug("Output model flow collection ended for '$tabName': ${e.message}")
                }
            }

            // Also register listener on current active model immediately
            val currentModel = outputModels.active.value
            registerOutputListener(tabMonitor, currentModel)

            logger.info("Started monitoring terminal tab: '$tabName'")

        } catch (e: Exception) {
            logger.warn("Failed to monitor tab '$tabName': ${e.message}", e)
        }
    }

    /**
     * Registers an output listener on a terminal output model.
     */
    private fun registerOutputListener(tabMonitor: TabMonitor, outputModel: TerminalOutputModel) {
        val tabName = tabMonitor.tabName

        try {
            outputModel.addListener(tabMonitor.disposable, object : TerminalOutputModelListener {
                override fun afterContentChanged(event: TerminalContentChangeEvent) {
                    val newText = event.newText?.toString() ?: return

                    // Skip type-ahead text (user typing preview)
                    if (event.isTypeAhead) return

                    // Skip trimming events (scrollback cleanup)
                    if (event.isTrimming) return

                    // Log content changes (truncate for readability)
                    val logText = if (newText.length > LOG_TRUNCATE_LENGTH) newText.take(LOG_TRUNCATE_LENGTH) + "..." else newText
                    logger.debug("Content changed in '$tabName': ${logText.replace("\n", "\\n")}")

                    // Process the new text for session IDs
                    processTerminalOutput(tabMonitor, newText)
                }
            })

            logger.debug("Registered output listener for tab '$tabName'")

        } catch (e: Exception) {
            logger.debug("Failed to register output listener for '$tabName': ${e.message}")
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

        if (foundUuids.isEmpty()) return

        // Check each UUID against sessions-index.json
        val sessionService = ClaudeSessionService.getInstance(project)
        val sessions = sessionService.getSessions()
        val sessionIds = sessions.associateBy { it.sessionId.lowercase() }

        for (uuid in foundUuids) {
            // Skip if this UUID was already processed for this tab
            if (uuid == tabMonitor.lastDetectedSessionId) continue

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
                kotlinx.coroutines.delay(SESSION_VERIFICATION_DELAY_MS)
                sessionService.invalidateCache()
                val found = sessionService.findSessionByIdGlobal(sessionId)
                if (found != null) {
                    logger.info("Session $sessionId appeared in index after delay")
                } else {
                    logger.debug("Session $sessionId still not in index after delay")
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

                if (session != null) {
                    tabMonitor.lastDetectedSessionId = session.sessionId.lowercase()
                    updateBindingForSession(tabMonitor, session.sessionId, session)
                } else if (ClaudePathResolver.sessionFileExists(sessionId)) {
                    // Session file exists but not in any index - still bind
                    tabMonitor.lastDetectedSessionId = sessionId.lowercase()
                    updateBindingForSession(tabMonitor, sessionId, null)
                    logger.info("Bound session $sessionId found via JSONL file (not in any index)")
                } else {
                    logger.debug("UUID $sessionId is not a known Claude session")
                }
            } finally {
                tabMonitor.pendingVerifications.remove(sessionId)
            }
        }
    }

    /**
     * Updates or creates a binding for the detected session.
     * Session metadata is optional - binding works with just the session ID.
     */
    private fun updateBindingForSession(
        tabMonitor: TabMonitor,
        sessionId: String,
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession?
    ) {
        val tabName = tabMonitor.tabName
        val bindingService = BindingService.getInstance(project)

        // Create tab identifier keyed on the stable tab id (name is informational only).
        val persistentName = try {
            tabMonitor.tab.view.title.let { it.userDefinedTitle ?: it.defaultTitle }
        } catch (e: Exception) {
            null
        }
        val tabIdentifier = TabIdentifier(
            name = tabName,
            createdAt = System.currentTimeMillis(),
            shellPid = null,
            workingDirectory = null,
            tabUuid = tabMonitor.tabId,
            persistentName = persistentName
        )

        val existingBinding = bindingService.findBindingForTab(tabIdentifier)
        val displayInfo = session?.displaySummary() ?: "${sessionId.take(8)}..."

        ApplicationManager.getApplication().invokeLater {
            val content = tabMonitor.tab.content

            // Skip team-managed tabs - v2.0 handles their binding
            if (TerminalHelper.isTeamTab(content)) return@invokeLater

            if (existingBinding != null) {
                // Self-heal: if this matched a legacy (name-only) binding, pin it to the
                // stable tab id so future matching no longer depends on the volatile name.
                if (existingBinding.tabIdentifier.tabUuid == null) {
                    bindingService.attachTabUuid(existingBinding.id, tabMonitor.tabId, persistentName)
                } else if (persistentName != null &&
                    existingBinding.tabIdentifier.persistentName != persistentName) {
                    bindingService.updatePersistentName(existingBinding.id, persistentName)
                }

                // Update existing binding if session changed
                if (!existingBinding.currentSessionId.equals(sessionId, ignoreCase = true)) {
                    bindingService.updateBindingSession(existingBinding.id, sessionId)
                    logger.info("Auto-updated binding for '$tabName' from ${existingBinding.currentSessionId} to $sessionId")

                    val settings = SettingsService.getInstance()
                    if (settings.notifyOnSessionChange) {
                        showNotification(
                            "Session changed in '$tabName'",
                            "Updated to: $displayInfo",
                            NotificationType.INFORMATION
                        )
                    }
                }
            } else {
                // Create new binding
                bindingService.createBinding(tabIdentifier, sessionId)
                logger.info("Auto-bound tab '$tabName' to session $sessionId")

                // Set Claude icon
                TerminalHelper.setClaudeIconOnContent(content)
                TerminalHelper.protectTabTitleIfEnabled(project, content)

                val settings = SettingsService.getInstance()
                if (settings.notifyOnAutoDetection) {
                    showNotification(
                        "Claude session detected",
                        "Auto-bound '$tabName' to: $displayInfo",
                        NotificationType.INFORMATION
                    )
                }
            }
        }
    }

    /**
     * Stops monitoring a specific tab, identified by its stable tab id.
     */
    fun stopMonitoringTab(tabId: String) {
        val monitor = monitoredTabs.remove(tabId)
        if (monitor != null) {
            Disposer.dispose(monitor.disposable)
            logger.debug("Stopped monitoring tab '${monitor.tabName}' (id=$tabId)")
        }
    }

    /**
     * Stops all terminal output monitoring.
     */
    fun stopMonitoring() {
        isActive = false
        for ((_, monitor) in monitoredTabs) {
            try {
                Disposer.dispose(monitor.disposable)
            } catch (e: Exception) {
                logger.debug("Error disposing monitor for '${monitor.tabName}': ${e.message}")
            }
        }
        monitoredTabs.clear()
        logger.info("Stopped terminal output monitoring")
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationHelper.show(project, title, content, type)
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
        var lastDetectedSessionId: String? = null,
        val textBuffer: StringBuilder = StringBuilder(),
        val pendingVerifications: MutableSet<String> = ConcurrentHashMap.newKeySet()
    )
}
