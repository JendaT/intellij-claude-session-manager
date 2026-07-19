package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
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

    // Constants for retry delays
    companion object {
        private val RETRY_DELAYS_MS = listOf(1000L, 2000L, 5000L, 10000L)
        private const val FILE_WRITE_DELAY_MS = 50L
        private const val POLL_INTERVAL_SECONDS = 5

        // Guard rails for the untrusted event file
        private const val MAX_EVENT_FILE_BYTES = 64 * 1024L
        private const val MAX_TIMESTAMP_SKEW_MS = 60_000L

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
                var lastPolledMtime = 0L

                try {
                    while (!Thread.currentThread().isInterrupted && isWatching) {
                        val key = watchService?.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (key != null) {
                            // Coalesce: one write typically produces CREATE plus one or more
                            // MODIFY events - process the file once per batch, not per event.
                            var matched = false
                            for (event in key.pollEvents()) {
                                val kind = event.kind()
                                if (kind == StandardWatchEventKinds.OVERFLOW) continue

                                val filename = event.context() as? Path
                                if (filename?.toString() == "current-session.json") {
                                    matched = true
                                }
                            }
                            key.reset()
                            if (matched) {
                                logger.debug("Detected change in current-session.json")
                                // Small delay to ensure file write is complete
                                Thread.sleep(FILE_WRITE_DELAY_MS)
                                processSessionEvent(eventFile)
                            }
                        }

                        // Periodic fallback poll every POLL_INTERVAL_SECONDS to catch any missed
                        // events. Gated on mtime so the steady state is a stat call, not a full
                        // read + JSON parse of an unchanged file.
                        pollCounter++
                        if (pollCounter >= POLL_INTERVAL_SECONDS) {
                            pollCounter = 0
                            val mtime = eventFile.lastModified()
                            if (mtime != 0L && mtime != lastPolledMtime) {
                                lastPolledMtime = mtime
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

            // The event file is untrusted input; read a bounded number of bytes so the cap
            // cannot be raced by the file growing between a length check and the read.
            val bytes = eventFile.inputStream().use { it.readNBytes(MAX_EVENT_FILE_BYTES.toInt() + 1) }
            if (bytes.size > MAX_EVENT_FILE_BYTES) {
                logger.warn("Session event file too large (>${MAX_EVENT_FILE_BYTES} bytes), ignoring")
                return
            }
            val content = bytes.toString(Charsets.UTF_8).trim()
            if (content.isEmpty()) {
                logger.debug("Session event file is empty")
                return
            }

            val event = json.decodeFromString<SessionEvent>(content)

            // Reject implausible timestamps outright - clamping the stored watermark instead
            // would make a corrupt far-future event reprocess forever on the fallback poll.
            if (event.timestamp > System.currentTimeMillis() + MAX_TIMESTAMP_SKEW_MS) {
                logger.warn("Session event timestamp too far in the future (${event.timestamp}), ignoring")
                return
            }

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

        // Try to find session metadata for better notifications, but don't block on it.
        // Uses the cached index view - a full invalidate + rescan here would block the
        // shared watch thread on disk I/O before the bind is even dispatched; the retry
        // coroutine below invalidates and rescans for the cache-miss case.
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
     * All binding rules (suppression, self-heal, adoption, create) live in
     * [TabBindingCoordinator]; this method only resolves the focused tab.
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

                val selectedContent = toolWindow.contentManager.selectedContent
                if (selectedContent == null) {
                    logger.info("Auto-bind skipped: No terminal tab selected (project=${project.name}, session=$sessionId, source=$source)")
                    return@invokeLater
                }

                TabBindingCoordinator.getInstance(project).bindOrUpdate(
                    content = selectedContent,
                    sessionId = sessionId,
                    source = source,
                    workingDirectory = cwd.ifBlank { null },
                    displayInfo = session?.displaySummary()
                )
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

    override fun dispose() {
        stopWatching()
        scope.cancel()  // Cancel all coroutines to prevent resource leaks
    }
}
