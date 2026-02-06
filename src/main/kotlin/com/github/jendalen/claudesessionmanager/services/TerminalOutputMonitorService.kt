package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.TabIdentifier
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
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

    // Track monitored tabs to avoid duplicate listeners
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
        val tabName = tab.content.displayName ?: return

        // Avoid duplicate monitoring
        if (monitoredTabs.containsKey(tabName)) {
            logger.debug("Tab '$tabName' already monitored")
            return
        }

        try {
            val terminalView = tab.view
            val outputModels = terminalView.outputModels

            // Create a disposable for this tab's monitoring
            val tabDisposable = Disposer.newDisposable("TerminalOutputMonitor-$tabName")
            Disposer.register(this, tabDisposable)

            // Create tab monitor
            val tabMonitor = TabMonitor(
                tabName = tabName,
                tab = tab,
                disposable = tabDisposable
            )
            monitoredTabs[tabName] = tabMonitor

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
     */
    private fun processTerminalOutput(tabMonitor: TabMonitor, text: String) {
        if (text.isBlank()) return

        val tabName = tabMonitor.tabName

        // Accumulate text in buffer for multi-chunk detection
        tabMonitor.textBuffer.append(text)

        // Limit buffer size to prevent memory issues
        if (tabMonitor.textBuffer.length > MAX_BUFFER_SIZE) {
            tabMonitor.textBuffer.delete(0, tabMonitor.textBuffer.length - BUFFER_TRIM_SIZE)
        }

        val bufferText = tabMonitor.textBuffer.toString()

        // Find ALL UUIDs in the buffer
        val uuidMatcher = sessionIdPattern.matcher(bufferText)
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

                if (isRecentSession || foundUuids.size == 1) {
                    logger.info("Found valid session ID in terminal '$tabName': $uuid (recent=$isRecentSession)")
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

        // Verify this session exists in Claude's sessions-index.json
        val sessionService = ClaudeSessionService.getInstance(project)
        val session = sessionService.getSessions().find { it.sessionId.equals(sessionId, ignoreCase = true) }

        if (session == null) {
            logger.debug("Session $sessionId not found in sessions-index.json, may be creating...")
            // Session might not be in index yet - schedule a delayed recheck
            scheduleSessionVerification(tabMonitor, sessionId)
            return
        }

        // Session exists - create or update binding (use the original case from session)
        updateBindingForSession(tabMonitor, session.sessionId, session)
    }

    /**
     * Schedules a delayed verification of a session ID.
     */
    private fun scheduleSessionVerification(tabMonitor: TabMonitor, sessionId: String) {
        scope.launch {
            // Wait for session to be written to index
            kotlinx.coroutines.delay(SESSION_VERIFICATION_DELAY_MS)

            val sessionService = ClaudeSessionService.getInstance(project)
            sessionService.invalidateCache()
            val session = sessionService.getSessions().find { it.sessionId.equals(sessionId, ignoreCase = true) }

            if (session != null) {
                // Update lastDetectedSessionId to prevent reprocessing
                tabMonitor.lastDetectedSessionId = session.sessionId.lowercase()
                updateBindingForSession(tabMonitor, session.sessionId, session)
            } else {
                logger.debug("Session $sessionId still not found after delay")
            }
        }
    }

    /**
     * Updates or creates a binding for the detected session.
     */
    private fun updateBindingForSession(
        tabMonitor: TabMonitor,
        sessionId: String,
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession
    ) {
        val tabName = tabMonitor.tabName
        val bindingService = BindingService.getInstance(project)

        // Create tab identifier
        val tabIdentifier = TabIdentifier(
            name = tabName,
            createdAt = System.currentTimeMillis(),
            shellPid = null,
            workingDirectory = null
        )

        val existingBinding = bindingService.findBindingForTab(tabIdentifier)

        ApplicationManager.getApplication().invokeLater {
            val content = tabMonitor.tab.content

            if (existingBinding != null) {
                // Update existing binding if session changed
                if (!existingBinding.currentSessionId.equals(sessionId, ignoreCase = true)) {
                    bindingService.updateBindingSession(existingBinding.id, sessionId)
                    logger.info("Auto-updated binding for '$tabName' from ${existingBinding.currentSessionId} to $sessionId")

                    val settings = SettingsService.getInstance()
                    if (settings.notifyOnSessionChange) {
                        showNotification(
                            "Session changed in '$tabName'",
                            "Updated to: ${session.displaySummary()}",
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

                showNotification(
                    "Claude session detected",
                    "Auto-bound '$tabName' to: ${session.displaySummary()}",
                    NotificationType.INFORMATION
                )
            }
        }
    }

    /**
     * Stops monitoring a specific tab.
     */
    fun stopMonitoringTab(tabName: String) {
        val monitor = monitoredTabs.remove(tabName)
        if (monitor != null) {
            Disposer.dispose(monitor.disposable)
            logger.debug("Stopped monitoring tab '$tabName'")
        }
    }

    /**
     * Stops all terminal output monitoring.
     */
    fun stopMonitoring() {
        isActive = false
        for ((tabName, monitor) in monitoredTabs) {
            try {
                Disposer.dispose(monitor.disposable)
            } catch (e: Exception) {
                logger.debug("Error disposing monitor for '$tabName': ${e.message}")
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
        val tabName: String,
        val tab: TerminalToolWindowTab,
        val disposable: Disposable,
        var lastDetectedSessionId: String? = null,
        val textBuffer: StringBuilder = StringBuilder()
    )
}
