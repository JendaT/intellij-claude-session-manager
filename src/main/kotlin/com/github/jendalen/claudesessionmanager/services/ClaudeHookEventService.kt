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

    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private var isWatching = false

    // Track last processed event to avoid duplicates
    private var lastProcessedTimestamp: Long = 0

    @Serializable
    data class SessionEvent(
        val timestamp: Long,
        val sessionId: String,
        val source: String,
        val cwd: String
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

            watchThread = thread(start = true, isDaemon = true, name = "ClaudeHookEventWatcher") {
                logger.info("Started Claude hook event watcher for ${eventsDir.absolutePath}")
                isWatching = true

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
                                    Thread.sleep(50)
                                    processSessionEvent(eventFile)
                                    // Check again after a short delay in case another write happened
                                    Thread.sleep(200)
                                    processSessionEvent(eventFile)
                                }
                            }
                            key.reset()
                        }

                        // Periodic fallback poll every 5 seconds to catch any missed events
                        pollCounter++
                        if (pollCounter >= 5) {
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

            // Check if this event is for our project
            // Be lenient: if there's already a binding for the focused terminal, update it
            // regardless of cwd (user might be switching sessions via /resume)
            val projectPath = project.basePath
            val cwdMatchesProject = projectPath == null ||
                                    event.cwd.isEmpty() ||
                                    event.cwd.startsWith(projectPath) ||
                                    projectPath.startsWith(event.cwd)

            if (!cwdMatchesProject) {
                // Check if there's an existing binding for the focused terminal that we should update
                val hasExistingBinding = checkForExistingBinding()
                if (!hasExistingBinding) {
                    logger.debug("Session cwd ${event.cwd} is not in project $projectPath and no existing binding, skipping")
                    return
                }
                logger.info("CWD doesn't match project but found existing binding, processing anyway")
            }

            // For 'startup' events, the session may not be in sessions-index.json yet
            // Wait a bit and retry a few times
            when (event.source) {
                "startup" -> processStartupEvent(event)
                "resume", "clear", "compact" -> processExistingSessionEvent(event)
                else -> {
                    logger.info("Unknown event source '${event.source}', treating as existing session event")
                    processExistingSessionEvent(event)
                }
            }

        } catch (e: Exception) {
            logger.warn("Failed to process session event: ${e.message}", e)
        }
    }

    /**
     * Checks if there's an existing binding for the currently focused terminal tab.
     */
    private fun checkForExistingBinding(): Boolean {
        var hasBinding = false
        // This is called from background thread, need to check on EDT
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val toolWindow = TerminalHelper.getTerminalToolWindow(project)
                val content = toolWindow?.contentManager?.selectedContent
                val tabName = content?.displayName

                if (tabName != null) {
                    val tabIdentifier = TabIdentifier(
                        name = tabName,
                        createdAt = System.currentTimeMillis(),
                        shellPid = null,
                        workingDirectory = null
                    )
                    val bindingService = BindingService.getInstance(project)
                    hasBinding = bindingService.findBindingForTab(tabIdentifier) != null
                }
            } catch (e: Exception) {
                logger.debug("Error checking for existing binding: ${e.message}")
            }
        }
        return hasBinding
    }

    /**
     * Processes a startup event with retry logic for session creation delay.
     */
    private fun processStartupEvent(event: SessionEvent) {
        val sessionService = ClaudeSessionService.getInstance(project)

        // Try immediately first
        sessionService.invalidateCache()
        var session = sessionService.getSessions().find { it.sessionId.equals(event.sessionId, ignoreCase = true) }

        if (session != null) {
            bindToFocusedTerminal(event.sessionId, session, event.source)
            return
        }

        // For startup, session might not be created until first message
        // Schedule retries with increasing delays
        logger.info("Session ${event.sessionId} not yet in index, scheduling retries for startup event")

        val retryDelays = listOf(1000L, 2000L, 5000L, 10000L) // 1s, 2s, 5s, 10s
        var retryIndex = 0

        fun scheduleRetry() {
            if (retryIndex >= retryDelays.size) {
                logger.warn("Session ${event.sessionId} not found after all retries")
                return
            }

            val delay = retryDelays[retryIndex]
            retryIndex++

            Thread {
                try {
                    Thread.sleep(delay)
                    sessionService.invalidateCache()
                    val foundSession = sessionService.getSessions().find {
                        it.sessionId.equals(event.sessionId, ignoreCase = true)
                    }

                    if (foundSession != null) {
                        logger.info("Session ${event.sessionId} found after ${delay}ms delay")
                        bindToFocusedTerminal(event.sessionId, foundSession, event.source)
                    } else {
                        logger.debug("Session ${event.sessionId} still not found after ${delay}ms, retrying...")
                        scheduleRetry()
                    }
                } catch (e: InterruptedException) {
                    logger.debug("Retry interrupted")
                } catch (e: Exception) {
                    logger.warn("Error during retry: ${e.message}")
                }
            }.start()
        }

        scheduleRetry()
    }

    /**
     * Processes events where session should already exist (resume, clear, compact).
     */
    private fun processExistingSessionEvent(event: SessionEvent) {
        val sessionService = ClaudeSessionService.getInstance(project)
        sessionService.invalidateCache()
        val session = sessionService.getSessions().find { it.sessionId.equals(event.sessionId, ignoreCase = true) }

        if (session == null) {
            // For resume/clear/compact, session should exist - try once more after short delay
            logger.debug("Session ${event.sessionId} not found for ${event.source} event, retrying once...")
            Thread {
                try {
                    Thread.sleep(500)
                    sessionService.invalidateCache()
                    val foundSession = sessionService.getSessions().find {
                        it.sessionId.equals(event.sessionId, ignoreCase = true)
                    }
                    if (foundSession != null) {
                        bindToFocusedTerminal(event.sessionId, foundSession, event.source)
                    } else {
                        logger.warn("Session ${event.sessionId} not found for ${event.source} event")
                    }
                } catch (e: Exception) {
                    logger.warn("Error during retry: ${e.message}")
                }
            }.start()
        } else {
            bindToFocusedTerminal(event.sessionId, session, event.source)
        }
    }

    /**
     * Binds the session to the currently focused terminal tab.
     */
    private fun bindToFocusedTerminal(
        sessionId: String,
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession,
        source: String
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = TerminalHelper.getTerminalToolWindow(project)
                if (toolWindow == null) {
                    logger.debug("Terminal tool window not available")
                    return@invokeLater
                }

                if (!toolWindow.isVisible) {
                    logger.debug("Terminal tool window not visible")
                    return@invokeLater
                }

                val contentManager = toolWindow.contentManager
                val selectedContent = contentManager.selectedContent
                if (selectedContent == null) {
                    logger.debug("No terminal tab selected")
                    return@invokeLater
                }

                val tabName = selectedContent.displayName ?: return@invokeLater

                val tabIdentifier = TabIdentifier(
                    name = tabName,
                    createdAt = System.currentTimeMillis(),
                    shellPid = null,
                    workingDirectory = null
                )

                val bindingService = BindingService.getInstance(project)
                val existingBinding = bindingService.findBindingForTab(tabIdentifier)

                val settings = SettingsService.getInstance()

                if (existingBinding != null) {
                    // Update existing binding if session changed
                    if (!existingBinding.currentSessionId.equals(sessionId, ignoreCase = true)) {
                        bindingService.updateBindingSession(existingBinding.id, sessionId)
                        logger.info("Auto-updated binding for '$tabName' from ${existingBinding.currentSessionId} to $sessionId (source=$source)")

                        if (settings.notifyOnAutoDetection) {
                            showNotification(
                                "Session changed in '$tabName'",
                                "Updated to: ${session.displaySummary()} (via $source)",
                                NotificationType.INFORMATION
                            )
                        }
                    } else {
                        logger.debug("Session $sessionId already bound to '$tabName'")
                    }
                } else {
                    // Create new binding
                    bindingService.createBinding(tabIdentifier, sessionId)
                    logger.info("Auto-bound tab '$tabName' to session $sessionId (source=$source)")

                    // Set Claude icon
                    TerminalHelper.setClaudeIconOnContent(selectedContent)

                    if (settings.notifyOnAutoDetection) {
                        showNotification(
                            "Claude session detected",
                            "Auto-bound '$tabName' to: ${session.displaySummary()}",
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

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationHelper.show(project, title, content, type)
    }

    override fun dispose() {
        stopWatching()
    }

    companion object {
        fun getInstance(project: Project): ClaudeHookEventService {
            return project.getService(ClaudeHookEventService::class.java)
        }
    }
}
