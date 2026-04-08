package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File
import java.nio.file.*
import kotlin.concurrent.thread

/**
 * Watches sessions-index.json for changes and reconciles bindings.
 *
 * When Claude creates/clears/compacts a session, this detects the change
 * and updates the binding accordingly.
 */
@Service(Service.Level.PROJECT)
class SessionWatcherService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionWatcherService::class.java)

    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private val debounceAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile
    private var lastKnownSessions: Map<String, Long> = emptyMap() // sessionId -> modified timestamp
    @Volatile
    private var isWatching = false
    @Volatile
    private var watchingParentForCreation = false
    private var sessionsFilePath: Path? = null

    // File stability check constants
    companion object {
        private const val FILE_STABILITY_DELAY_MS = 100L
        private const val FILE_MODIFICATION_THRESHOLD_MS = 1000L

        fun getInstance(project: Project): SessionWatcherService {
            return project.getService(SessionWatcherService::class.java)
        }
    }

    /**
     * Starts watching the sessions-index.json file using native file system watching.
     * This works for files outside the project directory (like ~/.claude/...).
     */
    fun startWatching() {
        if (isWatching) return

        val sessionsFile = ClaudeSessionService.getInstance(project).getSessionsIndexFile()
        if (sessionsFile == null) {
            logger.info("No sessions-index.json found for project, not watching")
            return
        }

        sessionsFilePath = sessionsFile.toPath()
        val parentDir = sessionsFile.parentFile?.toPath()
        if (parentDir == null) {
            logger.warn("Cannot get parent directory of sessions-index.json")
            return
        }

        // Initialize known sessions
        refreshKnownSessions()

        try {
            watchService = FileSystems.getDefault().newWatchService()

            // Check if the project-specific directory exists yet
            val parentDirFile = parentDir.toFile()
            val dirToWatch: Path
            if (parentDirFile.exists()) {
                dirToWatch = parentDir
                watchingParentForCreation = false
            } else {
                // Directory doesn't exist yet (first Claude session for this project).
                // Watch the projects/ directory for the project dir to be created.
                val projectsDir = ClaudePathResolver.getProjectsDirectory()
                if (!projectsDir.exists()) {
                    projectsDir.mkdirs()
                }
                dirToWatch = projectsDir.toPath()
                watchingParentForCreation = true
                logger.info("Project directory doesn't exist yet, watching ${projectsDir.absolutePath} for its creation")
            }

            dirToWatch.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE
            )

            // Start background thread to poll for changes
            watchThread = thread(start = true, isDaemon = true, name = "ClaudeSessionWatcher") {
                logger.info("Started native file watcher thread for ${sessionsFile.absolutePath}")
                try {
                    while (!Thread.currentThread().isInterrupted && isWatching) {
                        val key = watchService?.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (key != null) {
                            for (event in key.pollEvents()) {
                                val kind = event.kind()
                                if (kind == StandardWatchEventKinds.OVERFLOW) continue

                                val filename = event.context() as? Path

                                if (watchingParentForCreation) {
                                    // We're watching projects/ for the project dir to appear
                                    val projectDirName = parentDir.fileName?.toString()
                                    if (filename?.toString() == projectDirName && parentDirFile.isDirectory) {
                                        logger.info("Project directory created, switching to direct watching")
                                        // Cancel the parent key and register the actual directory
                                        key.cancel()
                                        try {
                                            parentDir.register(
                                                watchService,
                                                StandardWatchEventKinds.ENTRY_MODIFY,
                                                StandardWatchEventKinds.ENTRY_CREATE
                                            )
                                            watchingParentForCreation = false
                                            // Initialize sessions now that the directory exists
                                            refreshKnownSessions()
                                        } catch (e: Exception) {
                                            logger.warn("Failed to switch to project directory watching: ${e.message}", e)
                                        }
                                    }
                                } else {
                                    if (filename?.toString() == "sessions-index.json") {
                                        logger.debug("Detected change in sessions-index.json")
                                        scheduleReconciliation()
                                    }
                                }
                            }
                            if (key.isValid) {
                                key.reset()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    logger.debug("Watch thread interrupted")
                } catch (e: ClosedWatchServiceException) {
                    logger.debug("Watch service closed")
                } catch (e: Exception) {
                    logger.warn("Error in watch thread: ${e.message}", e)
                }
            }

            isWatching = true
            logger.info("Started native file watching for ${sessionsFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to start file watcher: ${e.message}", e)
        }
    }

    /**
     * Stops watching for file changes.
     */
    fun stopWatching() {
        isWatching = false
        watchingParentForCreation = false
        watchThread?.interrupt()
        watchThread = null
        try {
            watchService?.close()
        } catch (e: Exception) {
            logger.debug("Error closing watch service: ${e.message}")
        }
        watchService = null
        debounceAlarm.cancelAllRequests()
        logger.info("Stopped watching sessions-index.json")
    }

    /**
     * Schedules a reconciliation with debouncing.
     */
    private fun scheduleReconciliation() {
        val debounceMs = SettingsService.getInstance().getSettings().fileWatcherDebounceMs

        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({
            try {
                reconcileBindings()
            } catch (e: Exception) {
                logger.error("Error during binding reconciliation: ${e.message}", e)
            }
        }, debounceMs.toInt())
    }

    /**
     * Checks if a file is stable (not being written to).
     * Returns true if the file size and mtime haven't changed over the stability delay.
     */
    private fun isFileStable(file: java.io.File): Boolean {
        if (!file.exists()) return false

        val size1 = file.length()
        val mtime1 = file.lastModified()

        try {
            Thread.sleep(FILE_STABILITY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        val size2 = file.length()
        val mtime2 = file.lastModified()

        return size1 == size2 && mtime1 == mtime2
    }

    /**
     * Reconciles bindings with current session state.
     */
    private fun reconcileBindings() {
        // Check file stability before reading to avoid partial reads
        val sessionsFile = ClaudeSessionService.getInstance(project).getSessionsIndexFile()
        if (sessionsFile != null && !isFileStable(sessionsFile)) {
            logger.debug("Sessions file not stable, skipping reconciliation")
            scheduleReconciliation() // Retry
            return
        }

        logger.debug("Reconciling bindings with sessions-index.json")

        val sessionService = ClaudeSessionService.getInstance(project)
        sessionService.invalidateCache()

        val currentSessions = sessionService.getSessions()
        val currentSessionsMap = currentSessions.associateBy { it.sessionId }
        val currentSessionTimestamps = currentSessions.associate { it.sessionId to it.fileMtime }

        val bindingService = BindingService.getInstance(project)
        val bindings = bindingService.getAllBindings()

        // Find new sessions
        val newSessionIds = currentSessionsMap.keys - lastKnownSessions.keys

        // Find sessions whose modified time changed significantly (active sessions)
        val recentlyModifiedSessions = currentSessions.filter { session ->
            val lastKnownMtime = lastKnownSessions[session.sessionId]
            if (lastKnownMtime == null) {
                // New session
                true
            } else {
                // Modified since last check (more than threshold difference)
                kotlin.math.abs(session.fileMtime - lastKnownMtime) > FILE_MODIFICATION_THRESHOLD_MS
            }
        }

        // Find the most recently modified session (likely the one being actively used)
        val mostRecentSession = recentlyModifiedSessions.maxByOrNull { it.fileMtime }

        // Handle missing/deleted sessions
        for (binding in bindings) {
            val boundSessionId = binding.currentSessionId
            if (boundSessionId !in currentSessionsMap) {
                handleMissingSession(binding.tabIdentifier.name, boundSessionId)
            }
        }

        // If there are newly modified sessions, try to update the focused terminal's binding
        if (mostRecentSession != null && recentlyModifiedSessions.isNotEmpty()) {
            logger.info("Detected ${recentlyModifiedSessions.size} modified session(s), most recent: ${mostRecentSession.sessionId}")

            // Try to auto-bind or update the focused terminal
            tryAutoBindOrUpdateSession(mostRecentSession, bindingService, newSessionIds.contains(mostRecentSession.sessionId))
        }

        lastKnownSessions = currentSessionTimestamps
    }

    /**
     * Tries to auto-bind a new session or update an existing binding for the focused terminal.
     */
    private fun tryAutoBindOrUpdateSession(
        session: com.github.jendalen.claudesessionmanager.model.ClaudeSession,
        bindingService: BindingService,
        isNewSession: Boolean
    ) {
        // Run on EDT to access UI components
        ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return@invokeLater
                // NOTE: Do NOT check toolWindow.isVisible here.
                // The terminal might not be the active tool window but selectedContent
                // is still valid from the user's last interaction.

                val contentManager = toolWindow.contentManager
                val selectedContent = contentManager.selectedContent ?: return@invokeLater

                // Skip team-managed tabs - v2.0 handles their binding
                if (TerminalHelper.isTeamTab(selectedContent)) return@invokeLater

                val tabName = selectedContent.displayName ?: return@invokeLater

                val tabIdentifier = com.github.jendalen.claudesessionmanager.model.TabIdentifier(
                    name = tabName,
                    createdAt = System.currentTimeMillis(),
                    shellPid = null,
                    workingDirectory = null
                )

                val existingBinding = bindingService.findBindingForTab(tabIdentifier)

                if (existingBinding != null) {
                    // Tab is already bound
                    if (existingBinding.currentSessionId != session.sessionId) {
                        // Session changed (e.g., /resume or /clear)
                        bindingService.updateBindingSession(existingBinding.id, session.sessionId)
                        logger.info("Auto-updated binding for tab '$tabName' to session ${session.sessionId} (was: ${existingBinding.currentSessionId})")

                        val settings = SettingsService.getInstance()
                        if (settings.notifyOnSessionChange) {
                            showNotification(
                                "Session changed in '$tabName'",
                                "Updated to: ${session.displaySummary()}",
                                NotificationType.INFORMATION
                            )
                        }
                    }
                } else if (isNewSession) {
                    // New session and no binding - create one
                    bindingService.createBinding(tabIdentifier, session.sessionId)
                    logger.info("Auto-bound new session ${session.sessionId} to tab '$tabName'")

                    // Set Claude icon
                    TerminalHelper.setClaudeIconOnContent(selectedContent)

                    showNotification(
                        "Claude session detected",
                        "Auto-bound '$tabName' to: ${session.displaySummary()}",
                        NotificationType.INFORMATION
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to auto-bind/update session: ${e.message}", e)
            }
        }
    }

    /**
     * Handles a binding whose session no longer exists in sessions-index.json.
     * NOTE: We do NOT auto-rebind to the "most recent" session - that causes
     * cross-tab contamination where unrelated sessions overwrite correct bindings.
     * The binding is left as-is; the hook event service will update it when
     * the session actually changes.
     */
    private fun handleMissingSession(tabName: String, oldSessionId: String) {
        // Many sessions are never written to sessions-index.json (e.g., after /clear).
        // A "missing" session in the index doesn't mean the binding is wrong.
        // Just log it - the hook service handles actual session changes.
        logger.info("Session $oldSessionId for tab '$tabName' not in sessions-index.json (binding preserved)")
    }

    /**
     * Refreshes the known sessions with their timestamps from the current index.
     */
    private fun refreshKnownSessions() {
        val sessions = ClaudeSessionService.getInstance(project).getSessions()
        lastKnownSessions = sessions.associate { it.sessionId to it.fileMtime }
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationHelper.show(project, title, content, type)
    }

    override fun dispose() {
        stopWatching()
    }
}
