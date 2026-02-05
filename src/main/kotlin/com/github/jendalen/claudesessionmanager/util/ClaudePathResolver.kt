package com.github.jendalen.claudesessionmanager.util

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

    /**
     * Returns the Claude home directory.
     * Checks CLAUDE_HOME environment variable first, falls back to ~/.claude
     */
    fun getClaudeHome(): File {
        val envHome = System.getenv("CLAUDE_HOME")
        return if (!envHome.isNullOrBlank()) {
            File(envHome)
        } else {
            File(System.getProperty("user.home"), ".claude")
        }
    }

    /**
     * Encodes a project path to Claude's directory name format.
     * /Users/foo/project -> -Users-foo-project
     */
    fun encodeProjectPath(projectPath: String): String {
        return projectPath.replace("/", "-").replace("\\", "-")
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
        val projectsDir = getProjectsDirectory()
        if (!projectsDir.exists() || !projectsDir.isDirectory) {
            return emptyList()
        }

        return projectsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { projectDir ->
                val indexFile = File(projectDir, "sessions-index.json")
                if (indexFile.exists() && indexFile.isFile) indexFile else null
            }
            ?: emptyList()
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
