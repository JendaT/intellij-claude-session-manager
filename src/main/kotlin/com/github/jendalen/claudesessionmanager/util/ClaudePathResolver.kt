package com.github.jendalen.claudesessionmanager.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Resolves paths to Claude CLI storage locations.
 *
 * Claude stores data in:
 * - ~/.claude/ (default)
 * - $CLAUDE_HOME if set
 *
 * Project sessions are stored in:
 * - {claude_home}/projects/{encoded_project_path}/sessions-index.json
 *
 * Path encoding: /Users/foo/project -> -Users-foo-project
 * Note: Leading slash becomes a dash, not removed.
 */
object ClaudePathResolver {

    // TTL cache for findAllSessionsIndexFiles() - directory listing changes rarely
    @Volatile
    private var cachedIndexFiles: List<File> = emptyList()
    @Volatile
    private var cachedIndexFilesTimestamp: Long = 0
    private const val INDEX_FILES_CACHE_TTL_MS = 30_000L

    private val logger = Logger.getInstance(ClaudePathResolver::class.java)

    /**
     * Returns the Claude home directory.
     * Checks CLAUDE_HOME environment variable first, falls back to ~/.claude.
     * Validates the path to prevent path traversal attacks.
     */
    fun getClaudeHome(): File {
        val envHome = System.getenv("CLAUDE_HOME")
        return if (!envHome.isNullOrBlank()) {
            val file = File(envHome)
            // SECURITY: Validate the path to prevent traversal attacks
            if (validatePath(file, "CLAUDE_HOME")) file
            else {
                logger.warn("Falling back to default Claude home due to suspicious CLAUDE_HOME")
                File(System.getProperty("user.home"), ".claude")
            }
        } else {
            File(System.getProperty("user.home"), ".claude")
        }
    }

    /**
     * Validates a path to prevent path traversal attacks.
     * Logs a warning if the path appears suspicious.
     */
    private fun validatePath(file: File, source: String): Boolean {
        val canonicalPath = file.canonicalPath
        val absolutePath = file.absolutePath

        // Path traversal: a `..` component makes the canonical path differ from the
        // absolute one, so the comparison alone catches it. A substring test on ".."
        // also rejected legitimate names such as `my..dir`.
        if (canonicalPath != absolutePath.replace("//", "/")) {
            logger.warn("Rejecting suspicious path in $source: $absolutePath -> $canonicalPath")
            return false
        }
        return true
    }

    /**
     * Encodes a project path to Claude's directory name format.
     * /Users/foo/project -> -Users-foo-project
     *
     * Claude CLI uses: path.replace(/[^a-zA-Z0-9]/g, "-")
     * i.e., every non-alphanumeric character becomes a dash.
     */
    fun encodeProjectPath(projectPath: String): String {
        return projectPath.replace(Regex("[^a-zA-Z0-9]"), "-")
    }

    /**
     * Returns the sessions-index.json file for a project path.
     */
    fun getSessionsIndexFile(projectPath: String): File {
        val encoded = encodeProjectPath(projectPath)
        return File(getClaudeHome(), "projects/$encoded/sessions-index.json")
    }

    /**
     * Returns the projects directory under Claude home.
     */
    fun getProjectsDirectory(): File {
        return File(getClaudeHome(), "projects")
    }

    /**
     * Finds all sessions-index.json files under Claude home.
     * Useful for discovering all projects with Claude sessions.
     */
    fun findAllSessionsIndexFiles(): List<File> {
        val now = System.currentTimeMillis()
        if (now - cachedIndexFilesTimestamp < INDEX_FILES_CACHE_TTL_MS && cachedIndexFiles.isNotEmpty()) {
            return cachedIndexFiles
        }

        val projectsDir = getProjectsDirectory()
        if (!projectsDir.exists() || !projectsDir.isDirectory) {
            return emptyList()
        }

        val result = projectsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { projectDir ->
                val indexFile = File(projectDir, "sessions-index.json")
                if (indexFile.exists() && indexFile.isFile) indexFile else null
            }
            ?: emptyList()

        cachedIndexFiles = result
        cachedIndexFilesTimestamp = now
        return result
    }

    /**
     * Invalidates the cached list of session index files.
     */
    fun invalidateIndexFilesCache() {
        cachedIndexFilesTimestamp = 0
    }

    /**
     * Checks if a project path matches or is a parent of another path.
     * Used for session matching: show sessions where session's projectPath starts with IDE's project path.
     */
    fun isPathMatch(ideProjectPath: String, sessionProjectPath: String): Boolean {
        val normalizedIdePath = ideProjectPath.trimEnd('/', '\\')
        val normalizedSessionPath = sessionProjectPath.trimEnd('/', '\\')

        // Exact match
        if (normalizedIdePath == normalizedSessionPath) return true

        // IDE path is parent of session path
        return normalizedSessionPath.startsWith("$normalizedIdePath/") ||
               normalizedSessionPath.startsWith("$normalizedIdePath\\")
    }

    /**
     * Checks if a session's JSONL file exists in any project directory.
     * This is the ground truth for session existence - independent of sessions-index.json.
     */
    fun sessionFileExists(sessionId: String): Boolean = findSessionFile(sessionId) != null

    /**
     * The session's transcript, wherever it lives under `projects/`, or null.
     */
    fun findSessionFile(sessionId: String): File? {
        // Validate UUID format to prevent path traversal
        if (!SESSION_ID_PATTERN.matches(sessionId)) {
            logger.warn("Invalid session ID format for file check: $sessionId")
            return null
        }

        val filename = "$sessionId.jsonl"
        // Try the cached directory set first; a miss re-lists before giving up, so a
        // project directory that appeared since the last listing is still found and the
        // answer is exactly what a fresh listing would give. Only hits avoid the readdir
        // - which is what the startup fallback walk (28 bindings x history) and the
        // output channel's per-chunk verification were paying for.
        projectDirs(refresh = false).firstNotNullOfOrNull { File(it, filename).takeIf(File::exists) }?.let { return it }
        return projectDirs(refresh = true).firstNotNullOfOrNull { File(it, filename).takeIf(File::exists) }
    }

    private val SESSION_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    @Volatile
    private var cachedProjectDirs: List<File> = emptyList()
    @Volatile
    private var cachedProjectDirsTimestamp: Long = 0

    private fun projectDirs(refresh: Boolean): List<File> {
        val now = System.currentTimeMillis()
        if (!refresh && now - cachedProjectDirsTimestamp < INDEX_FILES_CACHE_TTL_MS && cachedProjectDirs.isNotEmpty()) {
            return cachedProjectDirs
        }
        val projectsDir = getProjectsDirectory()
        if (!projectsDir.exists() || !projectsDir.isDirectory) return emptyList()
        val dirs = projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        cachedProjectDirs = dirs
        cachedProjectDirsTimestamp = now
        return dirs
    }

    /**
     * The working directory the session runs in, read from its transcript.
     *
     * The transcript's directory name cannot be used for this: Claude encodes every
     * non-alphanumeric character as `-`, so `/Shop` and `/Shop-worktree1` become
     * prefixes of each other and a prefix test says the wrong thing (the exact trap that
     * once matched worktree paths to their parent). The records themselves carry the
     * real path: the first few lines are meta records with `cwd: null`, then every
     * message record has it. Scans at most [maxLines] lines and never loads the file.
     */
    fun readSessionCwd(sessionFile: File, maxLines: Int = 20): String? {
        if (!sessionFile.isFile) return null
        return try {
            sessionFile.bufferedReader().use { reader ->
                var n = 0
                var line = reader.readLine()
                while (line != null && n < maxLines) {
                    val cwd = cwdOfRecord(line)
                    if (!cwd.isNullOrBlank()) return cwd
                    n++
                    line = reader.readLine()
                }
                null
            }
        } catch (e: Exception) {
            logger.info("Could not read cwd from ${sessionFile.name}: ${e.message}")
            null
        }
    }

    private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    private fun cwdOfRecord(line: String): String? =
        try {
            val obj = lenientJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
            val cwd = obj["cwd"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
            if (cwd.isString) cwd.content else null
        } catch (e: Exception) {
            null
        }

    /**
     * Checks if Claude CLI appears to be installed by looking for common indicators.
     */
    fun isClaudeInstalled(): Boolean {
        val claudeHome = getClaudeHome()
        // Check for presence of any indicator Claude has been used
        return claudeHome.exists() && (
            File(claudeHome, "projects").exists() ||
            File(claudeHome, "settings.json").exists()
        )
    }
}
