package com.github.jendalen.claudesessionmanager.teams.services

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Watches ~/.claude/teams/ for team lifecycle events using Java NIO WatchService.
 *
 * Monitors:
 * - New team directories (TeamCreated)
 * - config.json modifications (TeamUpdated, MemberAdded/MemberRemoved)
 * - Team directory deletion (TeamDeleted)
 * - Inbox file changes (MessageReceived)
 * - Task file changes (TaskChanged)
 *
 * All file I/O runs on background threads. Events are debounced per-team at 500ms.
 * A periodic re-scan runs every POLL_INTERVAL_SECONDS as a fallback for macOS
 * NIO WatchService unreliability.
 */
@Service(Service.Level.PROJECT)
class FileSystemTeamWatcher(private val project: Project) : TeamWatcher, Disposable {

    private val logger = Logger.getInstance(FileSystemTeamWatcher::class.java)

    private val parser: TeamStateParser get() = TeamStateParser.getInstance(project)

    private val listeners = CopyOnWriteArrayList<TeamEventListener>()
    private val activeTeams = ConcurrentHashMap<String, TeamModel>()

    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    private val debounceAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    // Per-team pending events for debouncing. Each team accumulates events
    // independently so that events for team A don't cancel events for team B.
    private val pendingEvents = ConcurrentHashMap<String, MutableSet<String>>()

    // Track registered watch keys so we can identify which directory changed
    private val watchKeyToPath = ConcurrentHashMap<WatchKey, Path>()

    @Volatile
    private var isRunning = false

    companion object {
        private const val DEBOUNCE_MS = 500
        private const val FILE_STABILITY_DELAY_MS = 100L
        /** Fallback filesystem poll interval to catch events NIO misses (macOS issue) */
        private const val POLL_INTERVAL_SECONDS = 5
        /** Teams with config.json older than this are considered stale on initial scan */
        private const val STALE_TEAM_THRESHOLD_MS = 60_000L

        fun getInstance(project: Project): FileSystemTeamWatcher {
            return project.getService(FileSystemTeamWatcher::class.java)
        }
    }

    override fun start() {
        if (isRunning) return

        // Do an initial scan on a background thread
        thread(start = true, isDaemon = true, name = "ClaudeTeamWatcher-init") {
            initialScan()
            startFileWatcher()
        }
    }

    override fun stop() {
        isRunning = false
        watchThread?.interrupt()
        watchThread = null
        try {
            watchService?.close()
        } catch (e: Exception) {
            logger.debug("Error closing team watch service: ${e.message}")
        }
        watchService = null
        debounceAlarm.cancelAllRequests()
        watchKeyToPath.clear()
        pendingEvents.clear()
        logger.info("Stopped team watcher")
    }

    override fun getActiveTeams(): List<TeamModel> {
        return activeTeams.values.toList()
    }

    override fun addListener(listener: TeamEventListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TeamEventListener) {
        listeners.remove(listener)
    }

    /**
     * Scans existing teams on startup and emits TeamCreated events for recent ones.
     *
     * Team directories can persist on disk after teams end (Claude Code doesn't always
     * clean up). To avoid false detection on IDE restart, only emit events for teams
     * whose config.json was modified within the staleness threshold.
     */
    private fun initialScan() {
        val teamsDir = parser.getTeamsDirectory()
        logger.info("Scanning teams directory: ${teamsDir.absolutePath} (exists=${teamsDir.exists()}, isDir=${teamsDir.isDirectory})")

        val teams = parser.parseAllTeams()
        val now = System.currentTimeMillis()
        var activeCount = 0
        var staleCount = 0

        for (team in teams) {
            val teamId = team.teamId()
            val configFile = File(teamsDir, "$teamId/config.json")
            val ageMs = now - configFile.lastModified()

            if (ageMs > STALE_TEAM_THRESHOLD_MS) {
                staleCount++
                logger.info("Initial scan: skipping stale team '$teamId' (${team.name}), config.json age=${ageMs / 1000}s")
                continue
            }

            activeTeams[teamId] = team
            fireEvent(TeamEvent.TeamCreated(team))
            activeCount++
            logger.info("Initial scan: found active team '$teamId' (${team.name}) with ${team.members.size} members, age=${ageMs / 1000}s")
        }
        if (teams.isEmpty()) {
            val contents = teamsDir.listFiles()?.map { "${it.name} (${if (it.isDirectory) "dir" else "file"})" } ?: listOf("(listFiles=null)")
            logger.info("Initial scan found 0 teams. Directory contents: $contents")
        } else {
            logger.info("Initial scan: $activeCount active, $staleCount stale (threshold=${STALE_TEAM_THRESHOLD_MS / 1000}s)")
        }
    }

    /**
     * Periodic fallback re-scan. Detects new/removed teams that NIO events may
     * have missed (common on macOS where WatchService uses polling internally).
     */
    private fun periodicRescan() {
        try {
            val currentTeamIds = parser.discoverTeamIds().toSet()
            val knownTeamIds = activeTeams.keys.toSet()

            // Detect new teams (skip stale ones)
            val now = System.currentTimeMillis()
            for (teamId in currentTeamIds - knownTeamIds) {
                val configFile = File(parser.getTeamsDirectory(), "$teamId/config.json")
                val ageMs = now - configFile.lastModified()
                if (ageMs > STALE_TEAM_THRESHOLD_MS) {
                    continue
                }

                val team = parser.parseTeamConfig(teamId)
                if (team != null) {
                    activeTeams[teamId] = team
                    registerTeamSubdirectories(File(parser.getTeamsDirectory(), teamId))
                    fireEvent(TeamEvent.TeamCreated(team))
                    logger.info("Periodic scan: discovered new team '$teamId' (${team.name})")
                }
            }

            // Detect removed teams
            for (teamId in knownTeamIds - currentTeamIds) {
                val removed = activeTeams.remove(teamId)
                if (removed != null) {
                    fireEvent(TeamEvent.TeamDeleted(teamId))
                    logger.info("Periodic scan: team '$teamId' no longer exists")
                }
            }

            // Detect updated configs for existing teams
            for (teamId in currentTeamIds.intersect(knownTeamIds)) {
                val team = parser.parseTeamConfig(teamId) ?: continue
                val existing = activeTeams[teamId] ?: continue
                if (team.members.size != existing.members.size ||
                    team.members.map { it.agentId }.toSet() != existing.members.map { it.agentId }.toSet()
                ) {
                    activeTeams[teamId] = team
                    fireEvent(TeamEvent.TeamUpdated(team))
                    diffMembers(teamId, existing.members, team.members)
                    logger.info("Periodic scan: team '$teamId' config changed")
                }
            }
        } catch (e: Exception) {
            logger.debug("Periodic rescan error: ${e.message}")
        }
    }

    /**
     * Starts the NIO WatchService. If ~/.claude/teams/ doesn't exist yet,
     * watches the parent directory (~/.claude/) for its creation.
     */
    private fun startFileWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService()

            val teamsDir = parser.getTeamsDirectory()
            if (teamsDir.exists()) {
                registerTeamsDirectory(teamsDir)
            } else {
                // Watch parent for teams/ directory creation
                val claudeHome = ClaudePathResolver.getClaudeHome()
                if (claudeHome.exists()) {
                    registerDirectory(claudeHome.toPath())
                    logger.info("Watching ${claudeHome.absolutePath} for teams/ directory creation")
                } else {
                    logger.info("Claude home directory doesn't exist yet, team watching deferred")
                }
            }

            isRunning = true

            watchThread = thread(start = true, isDaemon = true, name = "ClaudeTeamWatcher") {
                logger.info("Started team file watcher (with ${POLL_INTERVAL_SECONDS}s periodic fallback)")
                var pollCounter = 0
                try {
                    while (!Thread.currentThread().isInterrupted && isRunning) {
                        val key = watchService?.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                        if (key != null) {
                            val watchedDir = watchKeyToPath[key]
                            for (event in key.pollEvents()) {
                                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                                val filename = (event.context() as? Path)?.toString() ?: continue
                                logger.info("NIO event: ${event.kind()} '$filename' in $watchedDir")
                                handleFileEvent(watchedDir, filename, event.kind())
                            }
                            val valid = key.reset()
                            if (!valid) {
                                watchKeyToPath.remove(key)
                            }
                        }

                        // Periodic fallback re-scan to catch events NIO misses
                        pollCounter++
                        if (pollCounter >= POLL_INTERVAL_SECONDS) {
                            pollCounter = 0
                            periodicRescan()
                        }
                    }
                } catch (e: InterruptedException) {
                    logger.debug("Team watch thread interrupted")
                } catch (e: ClosedWatchServiceException) {
                    logger.debug("Team watch service closed")
                } catch (e: Exception) {
                    logger.warn("Error in team watch thread: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to start team file watcher: ${e.message}", e)
        }
    }

    /**
     * Registers the teams directory and all its subdirectories for watching.
     */
    private fun registerTeamsDirectory(teamsDir: File) {
        registerDirectory(teamsDir.toPath())

        for (teamDir in teamsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            registerTeamSubdirectories(teamDir)
        }
    }

    private fun registerDirectory(dir: Path) {
        try {
            val key = dir.register(
                watchService!!,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            watchKeyToPath[key] = dir
        } catch (e: Exception) {
            logger.debug("Failed to register watch on $dir: ${e.message}")
        }
    }

    private fun registerTeamSubdirectories(teamDir: File) {
        registerDirectory(teamDir.toPath())
        val inboxesDir = File(teamDir, "inboxes")
        if (inboxesDir.exists() && inboxesDir.isDirectory) {
            registerDirectory(inboxesDir.toPath())
        }
        // Also watch the tasks directory for this team
        val teamId = teamDir.name
        val tasksDir = File(ClaudePathResolver.getClaudeHome(), "tasks/$teamId")
        if (tasksDir.exists() && tasksDir.isDirectory) {
            registerDirectory(tasksDir.toPath())
        }
    }

    /**
     * Handles a raw file event. Accumulates per-team events and schedules
     * a single debounced processing callback.
     */
    private fun handleFileEvent(watchedDir: Path?, filename: String, kind: WatchEvent.Kind<*>) {
        if (watchedDir == null) return

        val claudeHome = ClaudePathResolver.getClaudeHome().toPath()
        val teamsDir = parser.getTeamsDirectory().toPath()
        val watchedDirStr = watchedDir.toString()
        val teamsDirStr = teamsDir.toString()
        val claudeHomeStr = claudeHome.toString()

        // Handle teams/ directory appearing under ~/.claude/
        if (watchedDirStr == claudeHomeStr && filename == "teams" &&
            kind == StandardWatchEventKinds.ENTRY_CREATE
        ) {
            val newTeamsDir = parser.getTeamsDirectory()
            if (newTeamsDir.exists() && newTeamsDir.isDirectory) {
                registerTeamsDirectory(newTeamsDir)
                initialScan()
            }
            return
        }

        // Determine team ID for per-team debouncing
        val teamId = when {
            watchedDirStr == teamsDirStr -> filename
            else -> extractTeamId(watchedDir)
        } ?: return

        // Accumulate event
        val eventKey = "$kind:$filename"
        pendingEvents.getOrPut(teamId) { ConcurrentHashMap.newKeySet() }.add(eventKey)

        // Schedule debounced processing (single callback processes all pending teams)
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({
            try {
                processPendingEvents()
            } catch (e: Exception) {
                logger.warn("Error processing pending events: ${e.message}", e)
            }
        }, DEBOUNCE_MS)
    }

    /**
     * Processes all accumulated per-team events.
     */
    private fun processPendingEvents() {
        val snapshot = HashMap(pendingEvents)
        pendingEvents.clear()

        for ((teamId, events) in snapshot) {
            try {
                processTeamEvents(teamId, events)
            } catch (e: Exception) {
                logger.warn("Error processing events for team '$teamId': ${e.message}", e)
            }
        }
    }

    /**
     * Processes accumulated events for a single team.
     */
    private fun processTeamEvents(teamId: String, events: Set<String>) {
        val teamsDir = parser.getTeamsDirectory()
        val teamDir = File(teamsDir, teamId)

        logger.info("Processing ${events.size} event(s) for team '$teamId': $events")

        for (eventKey in events) {
            val parts = eventKey.split(":", limit = 2)
            val kindStr = parts[0]
            val filename = parts.getOrElse(1) { "" }

            when {
                // Team directory created/deleted at teams root level
                kindStr.contains("ENTRY_CREATE") && filename == teamId -> {
                    logger.info("Team dir create event: '$teamId' (isDir=${teamDir.isDirectory}, configExists=${File(teamDir, "config.json").exists()})")
                    if (teamDir.isDirectory) {
                        registerTeamSubdirectories(teamDir)
                        val team = parser.parseTeamConfig(teamId)
                        if (team != null) {
                            activeTeams[teamId] = team
                            fireEvent(TeamEvent.TeamCreated(team))
                            logger.info("Team created via NIO event: $teamId (${team.name}) with ${team.members.size} members")
                        } else {
                            logger.info("Team dir '$teamId' created but config.json not parseable yet - will be caught by config.json watcher or periodic scan")
                        }
                    }
                }
                kindStr.contains("ENTRY_DELETE") && filename == teamId -> {
                    val removed = activeTeams.remove(teamId)
                    if (removed != null) {
                        fireEvent(TeamEvent.TeamDeleted(teamId))
                        logger.info("Team deleted: $teamId")
                    }
                }
                // config.json changed
                filename == "config.json" -> {
                    handleConfigChange(teamId)
                }
                // Inbox file changed
                filename.endsWith(".json") && events.any { it.contains("inboxes") || it.contains("ENTRY_MODIFY") } -> {
                    val agentId = filename.removeSuffix(".json")
                    handleInboxChange(teamId, agentId)
                    // Also check if this is a task file change
                    handleTaskChange(teamId, filename)
                }
            }
        }
    }

    /**
     * Extracts the team ID from a watched directory path.
     * Handles both ~/.claude/teams/{teamId}/... and ~/.claude/tasks/{teamId}/... paths.
     */
    private fun extractTeamId(watchedDir: Path): String? {
        val teamsDir = parser.getTeamsDirectory().toPath()
        val tasksDir = File(ClaudePathResolver.getClaudeHome(), "tasks").toPath()
        return try {
            val relative = teamsDir.relativize(watchedDir)
            relative.getName(0)?.toString()
        } catch (e: Exception) {
            // Try tasks directory
            try {
                val relative = tasksDir.relativize(watchedDir)
                relative.getName(0)?.toString()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun handleConfigChange(teamId: String) {
        val configFile = File(parser.getTeamsDirectory(), "$teamId/config.json")
        logger.info("Config change detected for team '$teamId' (file exists=${configFile.exists()})")
        if (!isFileStable(configFile)) {
            logger.info("Config file not stable yet for team '$teamId', skipping")
            return
        }

        val newTeam = parser.parseTeamConfig(teamId)
        if (newTeam == null) {
            logger.info("Failed to parse config for team '$teamId'")
            return
        }

        val oldTeam = activeTeams[teamId]

        if (oldTeam == null) {
            // Config.json appeared after directory was created - treat as team creation
            activeTeams[teamId] = newTeam
            fireEvent(TeamEvent.TeamCreated(newTeam))
            logger.info("Team created via config.json: $teamId (${newTeam.name}) with ${newTeam.members.size} members")
        } else {
            activeTeams[teamId] = newTeam
            fireEvent(TeamEvent.TeamUpdated(newTeam))
            diffMembers(teamId, oldTeam.members, newTeam.members)
            logger.info("Team updated: $teamId (${newTeam.members.size} members)")
        }
    }

    private fun handleInboxChange(teamId: String, agentId: String) {
        val messages = parser.parseInboxMessages(teamId, agentId)
        val lastMessage = messages.lastOrNull() ?: return
        fireEvent(TeamEvent.MessageReceived(teamId, lastMessage))
    }

    private fun handleTaskChange(teamId: String, filename: String) {
        if (!filename.endsWith(".json")) return
        val tasksDir = File(ClaudePathResolver.getClaudeHome(), "tasks/$teamId")
        val taskFile = File(tasksDir, filename)
        if (!taskFile.exists()) return

        val tasks = parser.parseTasks(teamId)
        val task = tasks.find { "${it.id}.json" == filename } ?: tasks.lastOrNull() ?: return
        fireEvent(TeamEvent.TaskChanged(teamId, task))
    }

    /**
     * Compares old and new member lists and emits MemberAdded/MemberRemoved events.
     */
    private fun diffMembers(teamId: String, oldMembers: List<TeamMember>, newMembers: List<TeamMember>) {
        val oldIds = oldMembers.map { it.agentId }.toSet()
        val newIds = newMembers.map { it.agentId }.toSet()

        for (agentId in newIds - oldIds) {
            val member = newMembers.find { it.agentId == agentId } ?: continue
            fireEvent(TeamEvent.MemberAdded(teamId, member))
        }

        for (agentId in oldIds - newIds) {
            fireEvent(TeamEvent.MemberRemoved(teamId, agentId))
        }
    }

    /**
     * Checks if a file is stable (not being written to).
     */
    private fun isFileStable(file: File): Boolean {
        if (!file.exists()) return false
        val size1 = file.length()
        val mtime1 = file.lastModified()
        try {
            Thread.sleep(FILE_STABILITY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return file.length() == size1 && file.lastModified() == mtime1
    }

    private fun fireEvent(event: TeamEvent) {
        for (listener in listeners) {
            try {
                listener.onTeamEvent(event)
            } catch (e: Exception) {
                logger.warn("Error in team event listener: ${e.message}", e)
            }
        }
    }

    override fun dispose() {
        stop()
    }
}
