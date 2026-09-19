package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.ClaudeSession
import com.github.jendalen.claudesessionmanager.model.SessionsIndex
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Project-level service for reading Claude session data.
 *
 * Parses sessions-index.json and provides access to session information
 * for the current project and related paths.
 */
@Service(Service.Level.PROJECT)
class ClaudeSessionService(private val project: Project) {

    private val logger = Logger.getInstance(ClaudeSessionService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Multi-slot cache: keyed by absolute file path, each with its own mtime
    private data class CacheEntry(
        val index: SessionsIndex?,
        val mtime: Long,
        // The project-filtered, sorted view of `index`, built once per file version.
        // getSessions() runs on the EDT for every UUID-bearing terminal chunk; without
        // this it re-filtered and re-sorted N entries at the terminal redraw rate.
        @Volatile var sessions: List<ClaudeSession>? = null
    )
    private val indexCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /**
     * Returns the project's base path.
     */
    fun getProjectPath(): String? = project.basePath

    /**
     * Returns the sessions-index.json file for this project.
     */
    fun getSessionsIndexFile(): File? {
        val projectPath = getProjectPath() ?: return null
        return ClaudePathResolver.getSessionsIndexFile(projectPath)
    }

    /**
     * Reads and parses the sessions-index.json for this project.
     * Uses caching to avoid repeated file reads.
     */
    fun getSessionsIndex(): SessionsIndex? {
        val indexFile = getSessionsIndexFile() ?: return null
        return readSessionsIndex(indexFile)
    }

    /**
     * Returns all sessions for this project, filtered to match project path.
     */
    fun getSessions(): List<ClaudeSession> {
        val projectPath = getProjectPath() ?: return emptyList()
        val indexFile = getSessionsIndexFile() ?: return emptyList()
        val index = readSessionsIndex(indexFile) ?: return emptyList()
        val entry = indexCache[indexFile.absolutePath]
        entry?.sessions?.let { return it }

        val sessions = index.entries.filter { session ->
            // Match sessions where projectPath is within IDE's project
            ClaudePathResolver.isPathMatch(projectPath, session.projectPath)
        }.sortedByDescending { it.modifiedInstant() }
        entry?.sessions = sessions
        return sessions
    }

    /**
     * Finds a session by its ID in the current project's sessions-index.json.
     */
    fun findSessionById(sessionId: String): ClaudeSession? {
        return getSessions().find { it.sessionId.equals(sessionId, ignoreCase = true) }
    }

    /**
     * Finds a session by its ID, searching ALL known sessions-index.json files.
     * Use this when the session may have been created from a different cwd
     * than the current IDE project (e.g., parent dir, monorepo root).
     */
    fun findSessionByIdGlobal(sessionId: String): ClaudeSession? {
        // Try current project first (fastest path)
        findSessionById(sessionId)?.let { return it }

        // Search all discovered sessions-index.json files
        val allFiles = ClaudePathResolver.findAllSessionsIndexFiles()
        logger.info("Global search for session $sessionId across ${allFiles.size} index files")
        for (indexFile in allFiles) {
            val index = readSessionsIndex(indexFile)
            if (index == null) {
                logger.info("  Skipping ${indexFile.parentFile.name}: parse failed or missing")
                continue
            }
            val match = index.entries.find { it.sessionId.equals(sessionId, ignoreCase = true) }
            if (match != null) {
                logger.info("Found session $sessionId in ${indexFile.parentFile.name} (not current project)")
                return match
            }
        }
        logger.info("Session $sessionId not found in any of ${allFiles.size} index files")
        return null
    }

    /**
     * Forces a reload of the sessions index from disk.
     */
    fun invalidateCache() {
        indexCache.clear()
        ClaudePathResolver.invalidateIndexFilesCache()
    }

    /**
     * Reads a sessions-index.json file with caching.
     */
    private fun readSessionsIndex(file: File): SessionsIndex? {
        // lastModified() is 0 for a missing file - one stat answers "present" and gives
        // the cache key; isFile only guards the read below.
        val currentMtime = file.lastModified()
        if (currentMtime == 0L) {
            logger.debug("Sessions index file not found: ${file.absolutePath}")
            return null
        }
        val currentPath = file.absolutePath

        // Return cached if still valid (per-file cache slot)
        val cached = indexCache[currentPath]
        if (cached != null && cached.mtime == currentMtime) {
            // A null index is a remembered parse failure for this mtime: a corrupt file
            // used to be re-read and re-warned (with a stack trace) on every call, and
            // the output monitor calls this for every UUID-bearing chunk.
            return cached.index
        }

        return try {
            if (!file.isFile) {
                logger.debug("Sessions index path is not a file: ${file.absolutePath}")
                return null
            }
            val content = file.readText()
            val index = json.decodeFromString<SessionsIndex>(content)
            indexCache[currentPath] = CacheEntry(index, currentMtime)
            logger.debug("Loaded sessions index: ${index.entries.size} entries")
            index
        } catch (e: Exception) {
            logger.warn("Failed to parse ${file.absolutePath} (will not retry until it changes): ${e.message}", e)
            indexCache[currentPath] = CacheEntry(null, currentMtime)
            null
        }
    }

    companion object {
        fun getInstance(project: Project): ClaudeSessionService {
            return project.getService(ClaudeSessionService::class.java)
        }
    }
}
