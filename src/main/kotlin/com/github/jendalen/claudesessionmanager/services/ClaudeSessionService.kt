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
        val mtime: Long
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
        val index = getSessionsIndex() ?: return emptyList()

        return index.entries.filter { session ->
            // Match sessions where projectPath is within IDE's project
            ClaudePathResolver.isPathMatch(projectPath, session.projectPath)
        }.sortedByDescending { it.modifiedInstant() }
    }

    /**
     * Returns all sessions, including those from subdirectories.
     * Same as getSessions() but explicitly named for clarity.
     */
    fun getAllProjectSessions(): List<ClaudeSession> = getSessions()

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
     * Checks if a session with the given ID exists using multi-tier lookup:
     * 1. Current project's sessions-index.json (fast)
     * 2. Global search across all sessions-index.json files
     * 3. JSONL file existence check (ground truth, independent of index)
     */
    fun sessionExists(sessionId: String): Boolean {
        // findSessionByIdGlobal already checks current project first, then all indexes
        if (findSessionByIdGlobal(sessionId) != null) return true
        // Fall back to JSONL file existence (ground truth, independent of any index)
        return ClaudePathResolver.sessionFileExists(sessionId)
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
        if (!file.exists() || !file.isFile) {
            logger.debug("Sessions index file not found: ${file.absolutePath}")
            return null
        }

        val currentMtime = file.lastModified()
        val currentPath = file.absolutePath

        // Return cached if still valid (per-file cache slot)
        val cached = indexCache[currentPath]
        if (cached != null && cached.mtime == currentMtime && cached.index != null) {
            return cached.index
        }

        return try {
            val content = file.readText()
            val index = json.decodeFromString<SessionsIndex>(content)
            indexCache[currentPath] = CacheEntry(index, currentMtime)
            logger.debug("Loaded sessions index: ${index.entries.size} entries")
            index
        } catch (e: Exception) {
            logger.warn("Failed to parse ${file.absolutePath}: ${e.message}", e)
            null
        }
    }

    /**
     * Returns sessions from all discovered Claude projects (not just current).
     * Useful for session picker showing all available sessions.
     */
    fun getAllDiscoveredSessions(): Map<String, List<ClaudeSession>> {
        val result = mutableMapOf<String, List<ClaudeSession>>()

        for (indexFile in ClaudePathResolver.findAllSessionsIndexFiles()) {
            val index = readSessionsIndex(indexFile)
            if (index != null && index.entries.isNotEmpty()) {
                result[index.originalPath] = index.entries.sortedByDescending { it.modifiedInstant() }
            }
        }

        return result
    }

    companion object {
        fun getInstance(project: Project): ClaudeSessionService {
            return project.getService(ClaudeSessionService::class.java)
        }
    }
}
