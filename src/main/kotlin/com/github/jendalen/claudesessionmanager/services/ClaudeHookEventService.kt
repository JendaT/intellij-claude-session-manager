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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.*
import kotlin.concurrent.thread

/**
 * Watches for Claude session events from the SessionStart hook.
 *
 * Claude Code's SessionStart hook writes session info to ~/.claude/ide-events/current-session.json
 * This service watches that file and auto-binds/updates terminal bindings.
 */
@Service(Service.Level.PROJECT)
class ClaudeHookEventService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(ClaudeHookEventService::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    // Coroutine scope for async retry operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    @Volatile
    private var isWatching = false

    // Track last processed event to avoid duplicates
    @Volatile
    private var lastProcessedTimestamp: Long = 0

    // Suppression window: during auto-resume, don't update existing bindings
    // (auto-resume already has the correct binding from persistence)
    @Volatile
    private var autoResumeSuppressUntil: Long = 0

    // Constants for retry delays
    companion object {
        private val RETRY_DELAYS_MS = listOf(1000L, 2000L, 5000L, 10000L)
        private const val FILE_WRITE_DELAY_MS = 50L
        private const val FILE_RECHECK_DELAY_MS = 200L
        private const val POLL_INTERVAL_SECONDS = 5
        private const val AUTO_RESUME_SUPPRESS_BUFFER_MS = 15000L

        fun getInstance(project: Project): ClaudeHookEventService {
            return project.getService(ClaudeHookEventService::class.java)
        }
    }

    @Serializable
    data class SessionEvent(
        val timestamp: Long = 0,
        val sessionId: String = "",
        val source: String = "unknown",
        val cwd: String = ""
    )

    /**
     * Starts watching for Claude hook events.
     */
    fun startWatching() {
        if (isWatching) {
            logger.debug("Claude hook event watching already active")
            return
        }

        val eventsDir = File(System.getProperty("user.home"), ".claude/ide-events")
        if (!eventsDir.exists()) {
            eventsDir.mkdirs()
            logger.info("Created Claude IDE events directory: ${eventsDir.absolutePath}")
        }

        val eventsDirPath = eventsDir.toPath()

        try {
            watchService = FileSystems.getDefault().newWatchService()
            eventsDirPath.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE
            )

            isWatching = true
            watchThread = thread(start = true, isDaemon = true, name = "ClaudeHookEventWatcher") {
                logger.info("Started Claude hook event watcher for ${eventsDir.absolutePath}")

                val eventFile = File(eventsDir, "current-session.json")
                var pollCounter = 0

                try {
                    while (!Thread.currentThread().isInterrupted && isWatching) {
                        val key = watchService?.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (key != null) {
                            for (event in key.pollEvents()) {
                                val kind = event.kind()
                                if (kind == StandardWatchEventKinds.OVERFLOW) continue

                                val filename = event.context() as? Path
                                if (filename?.toString() == "current-session.json") {
                                    logger.debug("Detected change in current-session.json")
                                    // Small delay to ensure file write is complete (events can fire rapidly)
                                    Thread.sleep(FILE_WRITE_DELAY_MS)
                                    processSessionEvent(eventFile)
                                    // Check again after a short delay in case another write happened
                                    Thread.sleep(FILE_RECHECK_DELAY_MS)
                                    processSessionEvent(eventFile)
                                }
                            }
                            key.reset()
                        }

                        // Periodic fallback poll every POLL_INTERVAL_SECONDS to catch any missed events
                        pollCounter++
                        if (pollCounter >= POLL_INTERVAL_SECONDS) {
                            pollCounter = 0
                            if (eventFile.exists()) {
                                processSessionEvent(eventFile)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    logger.debug("Hook event watch thread interrupted")
                } catch (e: ClosedWatchServiceException) {
                    logger.debug("Hook event watch service closed")
                } catch (e: Exception) {
                    logger.warn("Error in hook event watch thread: ${e.message}", e)
                }
            }

            logger.info("Started watching Claude hook events at ${eventsDir.absolutePath}")

        } catch (e: Exception) {
            logger.error("Failed to start hook event watcher: ${e.message}", e)
        }
    }

    /**
     * Processes a session event file.
     */
    private fun processSessionEvent(eventFile: File) {
        try {
            if (!eventFile.exists()) {
                logger.debug("Session event file does not exist")
                return
            }

            val content = eventFile.readText().trim()
            if (content.isEmpty()) {
                logger.debug("Session event file is empty")
                return
            }

            val event = json.decodeFromString<SessionEvent>(content)

            // Skip if we've already processed this event
            if (event.timestamp <= lastProcessedTimestamp) {
                logger.debug("Skipping already processed event: ${event.sessionId}")
                return
            }
            lastProcessedTimestamp = event.timestamp

            logger.info("Processing session event: sessionId=${event.sessionId}, source=${event.source}, cwd=${event.cwd}")

            // Check if this event is for our project (directory-boundary-aware)
            val projectPath = project.basePath
            val cwdMatchesProject = projectPath == null ||
                                    event.cwd.isEmpty() ||
                                    isDirectoryMatch(event.cwd, projectPath)

            if (!cwdMatchesProject) {
                logger.info("Hook event skipped: CWD '${event.cwd}' doesn't match project '$projectPath' (project=${project.name}, session=${event.sessionId}, source=${event.source})")
                return
            }

            // All event sources may involve a session ID not yet in the index:
            // - startup: new session, not indexed until first interaction
            // - resume: Claude may create a new session ID for the continuation
            // - clear/compact: creates a new session, not indexed until first interaction
            // Use startup-like retry logic for all sources.
            bindSessionFromEvent(event)

            // Trigger the "Option as Meta Key" guard - Claude often re-enables
            // this setting during session startup. Check now + delayed recheck.
            OptionAsMetaKeyGuardService.getInstance().onSessionChangeDetected()

        } catch (e: Exception) {
            logger.warn("Failed to process session event: ${e.message}", e)
        }
    }

    /**
     * Processes a session event with immediate binding.
     *
     * Binds immediately using just the session ID from the hook event, without
     * requiring sessions-index.json verification. Many sessions (especially after
     * /clear, /compact) are never written to the index in time, or at all.
     * The binding only needs the session ID - the ClaudeSession metadata is optional
     * and only used for richer notification text.
     */
    private fun bindSessionFromEvent(event: SessionEvent) {
        val sessionService = ClaudeSessionService.getInstance(project)

        // Try to find session metadata for better notifications, but don't block on it
        sessionService.invalidateCache()
        val session = sessionService.findSessionByIdGlobal(event.sessionId)

        // Bind immediately - the hook event is authoritative, no index verification needed
        bindToFocusedTerminal(event.sessionId, session, event.source, event.cwd)

        // If session wasn't in index, schedule background retries to log when it appears
        if (session == null) {
            logger.info("Session ${event.sessionId} not yet in index for ${event.source} event (binding created anyway)")

            scope.launch {
                for ((index, delayMs) in RETRY_DELAYS_MS.withIndex()) {
                    delay(delayMs)
                    sessionService.invalidateCache()
                    val foundSession = sessionService.findSessionByIdGlobal(event.sessionId)

                    if (foundSession != null) {
                        logger.info("Session ${event.sessionId} appeared in index on retry ${index + 1} (after ${delayMs}ms, source=${event.source})")
                        return@launch
                    }
                }
                logger.info("Session ${event.sessionId} never appeared in index after all retries for ${event.source} event (binding still valid)")
            }
        }
    }

    /**
     * Binds the session to the currently focused terminal tab.
     * Session metadata is optional - binding works with just the session ID.
     */
    private fun bindToFocusedTerminal(
        sessionId: String,
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession?,
        source: String,
        cwd: String = ""
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = TerminalHelper.getTerminalToolWindow(project)
                if (toolWindow == null) {
                    logger.info("Auto-bind skipped: Terminal tool window not available (project=${project.name}, session=$sessionId, source=$source)")
                    return@invokeLater
                }

                // NOTE: Do NOT check toolWindow.isVisible here.
                // The terminal might not be the active tool window (editor or project tree
                // could be focused), but selectedContent is still valid from the user's
                // last interaction with the terminal tab.

                val contentManager = toolWindow.contentManager
                val selectedContent = contentManager.selectedContent
                if (selectedContent == null) {
                    logger.info("Auto-bind skipped: No terminal tab selected (project=${project.name}, session=$sessionId, source=$source)")
                    return@invokeLater
                }

                // Skip team-managed tabs - v2.0 handles their binding
                if (TerminalHelper.isTeamTab(selectedContent)) {
                    logger.info("Auto-bind skipped: Team-managed tab (project=${project.name}, session=$sessionId, source=$source)")
                    return@invokeLater
                }

                val tabName = selectedContent.displayName
                if (tabName.isNullOrBlank()) {
                    logger.info("Auto-bind skipped: Terminal tab has no/blank display name (project=${project.name}, session=$sessionId, source=$source)")
                    return@invokeLater
                }

                // Stable id makes the binding independent of Claude Code's tab renaming.
                val tabId = TerminalHelper.getOrCreateTabId(selectedContent)
                // Terminal-persisted name is the identity that survives IDE restarts.
                val persistentName = TerminalHelper.getPersistentName(project, selectedContent)

                val tabIdentifier = TabIdentifier(
                    name = tabName,
                    createdAt = System.currentTimeMillis(),
                    shellPid = null,
                    workingDirectory = cwd.ifBlank { null },
                    tabUuid = tabId,
                    persistentName = persistentName
                )

                val bindingService = BindingService.getInstance(project)
                val existingBinding = bindingService.findBindingForTab(tabIdentifier)

                val settings = SettingsService.getInstance()
                val displayInfo = session?.displaySummary() ?: "${sessionId.take(8)}..."

                if (existingBinding != null) {
                    // Self-heal legacy (name-only) bindings by pinning them to the stable tab id.
                    if (existingBinding.tabIdentifier.tabUuid == null) {
                        bindingService.attachTabUuid(existingBinding.id, tabId, persistentName)
                    } else if (persistentName != null &&
                        existingBinding.tabIdentifier.persistentName != persistentName) {
                        // Keep the restart-stable name in sync (covers older bindings without
                        // one and tabs renamed since the binding was created).
                        bindingService.updatePersistentName(existingBinding.id, persistentName)
                    }

                    // During auto-resume window, don't update existing bindings.
                    // Auto-resume already has the correct session from persistence.
                    // Hook events during this window are side-effects of the resume
                    // (startup + resume events interleave and cause binding flip-flop).
                    val isSuppressed = System.currentTimeMillis() < autoResumeSuppressUntil
                    if (isSuppressed) {
                        logger.info("Hook event suppressed during auto-resume window: session=$sessionId, tab='$tabName', source=$source")
                    } else if (!existingBinding.currentSessionId.equals(sessionId, ignoreCase = true)) {
                        bindingService.updateBindingSession(existingBinding.id, sessionId)
                        logger.info("Auto-updated binding for '$tabName' from ${existingBinding.currentSessionId} to $sessionId (source=$source)")

                        if (settings.notifyOnAutoDetection) {
                            showNotification(
                                "Session changed in '$tabName'",
                                "Updated to: $displayInfo (via $source)",
                                NotificationType.INFORMATION
                            )
                        }
                    } else {
                        logger.info("Session $sessionId already bound to '$tabName' (source=$source)")
                    }
                } else {
                    // Create a new binding - the hook event tells us Claude started/resumed
                    // in the currently focused terminal tab
                    bindingService.createBinding(tabIdentifier, sessionId)
                    logger.info("Auto-bound tab '$tabName' to session $sessionId (source=$source)")

                    // Set Claude icon
                    TerminalHelper.setClaudeIconOnContent(selectedContent)
                    TerminalHelper.protectTabTitleIfEnabled(project, selectedContent)

                    if (settings.notifyOnAutoDetection) {
                        showNotification(
                            "Claude session detected",
                            "Auto-bound '$tabName' to: $displayInfo (via $source)",
                            NotificationType.INFORMATION
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to bind to focused terminal: ${e.message}", e)
            }
        }
    }

    /**
     * Stops watching for hook events.
     */
    fun stopWatching() {
        isWatching = false
        watchThread?.interrupt()
        watchThread = null
        try {
            watchService?.close()
        } catch (e: Exception) {
            logger.debug("Error closing watch service: ${e.message}")
        }
        watchService = null
        logger.info("Stopped watching Claude hook events")
    }

    /**
     * Activates the auto-resume suppression window.
     * During this window, hook events will not update existing bindings
     * (only create new ones). This prevents binding flip-flop during
     * multi-tab auto-resume on IDE startup.
     */
    fun suppressForAutoResume() {
        autoResumeSuppressUntil = System.currentTimeMillis() + AUTO_RESUME_SUPPRESS_BUFFER_MS
        logger.info("Auto-resume suppression window active for ${AUTO_RESUME_SUPPRESS_BUFFER_MS}ms")
    }

    /**
     * Checks if two paths have a directory containment relationship.
     * Unlike string prefix matching, this ensures the match is at a directory boundary.
     * e.g., /foo/bar matches /foo/bar/baz but NOT /foo/bar-other
     */
    private fun isDirectoryMatch(eventCwd: String, projectPath: String): Boolean {
        val normalizedCwd = eventCwd.trimEnd('/')
        val normalizedProject = projectPath.trimEnd('/')
        return normalizedCwd == normalizedProject ||
               normalizedCwd.startsWith("$normalizedProject/")
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationHelper.show(project, title, content, type)
    }

    override fun dispose() {
        stopWatching()
        scope.cancel()  // Cancel all coroutines to prevent resource leaks
    }
}
