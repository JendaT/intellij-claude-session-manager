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

    // Cached sessions index, invalidated on file changes
    // Using a data class for atomic cache state to prevent race conditions
    private data class CacheState(
        val index: SessionsIndex?,
        val path: String?,
        val mtime: Long
    )
    @Volatile
    private var cacheState = CacheState(null, null, 0)

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
     * Finds a session by its ID.
     */
    fun findSessionById(sessionId: String): ClaudeSession? {
        return getSessions().find { it.sessionId == sessionId }
    }

    /**
     * Checks if a session with the given ID exists.
     */
    fun sessionExists(sessionId: String): Boolean {
        return findSessionById(sessionId) != null
    }

    /**
     * Forces a reload of the sessions index from disk.
     */
    fun invalidateCache() {
        cacheState = CacheState(null, null, 0)
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

        // Return cached if still valid (atomic read of cache state)
        val currentCache = cacheState
        if (currentCache.index != null &&
            currentCache.path == currentPath &&
            currentCache.mtime == currentMtime) {
            return currentCache.index
        }

        return try {
            val content = file.readText()
            val index = json.decodeFromString<SessionsIndex>(content)
            // Atomic update of cache state
            cacheState = CacheState(index, currentPath, currentMtime)
            logger.debug("Loaded sessions index: ${index.entries.size} entries")
            index
        } catch (e: Exception) {
            logger.warn("Failed to parse sessions-index.json: ${e.message}", e)
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
