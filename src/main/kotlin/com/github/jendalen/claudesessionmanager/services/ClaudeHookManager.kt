package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File

/**
 * Manages Claude Code hook integration for session detection.
 *
 * The plugin requires a SessionStart hook in Claude's settings.json that writes
 * session events to a known location. This service handles:
 * - Checking if hooks are installed
 * - Installing hooks (modifying settings.json + creating hook script)
 * - Reverting hooks (removing from settings.json)
 * - Showing what exactly was modified for transparency
 */
@Service(Service.Level.APP)
class ClaudeHookManager {

    private val logger = Logger.getInstance(ClaudeHookManager::class.java)

    companion object {
        private const val HOOK_SCRIPT_NAME = "session-notify.sh"
        private const val HOOK_MARKER = "# Claude Session Manager Plugin Hook"

        fun getInstance(): ClaudeHookManager {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ClaudeHookManager::class.java)
        }
    }

    /**
     * The hook script content that writes session events to ide-events directory.
     */
    val hookScriptContent: String = """
        |#!/bin/bash
        |$HOOK_MARKER
        |# This script notifies the IDE plugin about Claude session events.
        |# It writes session info to ~/.claude/ide-events/ for the plugin to watch.
        |
        |INPUT=${'$'}(cat)
        |SESSION_ID=${'$'}(echo "${'$'}INPUT" | jq -r '.session_id // empty')
        |SOURCE=${'$'}(echo "${'$'}INPUT" | jq -r '.source // empty')
        |CWD=${'$'}(echo "${'$'}INPUT" | jq -r '.cwd // empty')
        |
        |# Get timestamp in milliseconds
        |if [[ "${'$'}OSTYPE" == "darwin"* ]]; then
        |  if command -v gdate &> /dev/null; then
        |    TIMESTAMP=${'$'}(gdate +%s%3N)
        |  else
        |    TIMESTAMP=${'$'}(python3 -c 'import time; print(int(time.time() * 1000))')
        |  fi
        |else
        |  TIMESTAMP=${'$'}(date +%s%3N)
        |fi
        |
        |if [ -n "${'$'}SESSION_ID" ]; then
        |  EVENTS_DIR="${'$'}HOME/.claude/ide-events"
        |  mkdir -p "${'$'}EVENTS_DIR"
        |
        |  # Write current session for plugin to detect
        |  jq -n --arg ts "${'$'}TIMESTAMP" --arg sid "${'$'}SESSION_ID" --arg src "${'$'}SOURCE" --arg cwd "${'$'}CWD" \
        |    '{"timestamp":(${'$'}ts|tonumber),"sessionId":${'$'}sid,"source":${'$'}src,"cwd":${'$'}cwd}' > "${'$'}EVENTS_DIR/current-session.json"
        |fi
        |
        |exit 0
    """.trimMargin()

    /**
     * The hook configuration that goes into settings.json
     */
    val hookConfig: String = """
        |{
        |  "matcher": "*",
        |  "hooks": [
        |    {
        |      "type": "command",
        |      "command": "${'$'}HOME/.claude/hooks/$HOOK_SCRIPT_NAME"
        |    }
        |  ]
        |}
    """.trimMargin()

    data class IntegrationStatus(
        val isInstalled: Boolean,
        val hookScriptExists: Boolean,
        val hookInSettings: Boolean,
        val hookScriptPath: String,
        val settingsPath: String,
        val issues: List<String> = emptyList()
    )

    /**
     * Check the current integration status.
     */
    fun getIntegrationStatus(): IntegrationStatus {
        val claudeHome = ClaudePathResolver.getClaudeHome()
        val settingsFile = File(claudeHome, "settings.json")
        val hookScript = File(claudeHome, "hooks/$HOOK_SCRIPT_NAME")
        val issues = mutableListOf<String>()

        // Check if script exists and writes to ide-events (compatible with manual installs too)
        val hookScriptExists = hookScript.exists() && hookScript.readText().contains("ide-events")
        val hookInSettings = checkHookInSettings(settingsFile)

        if (!settingsFile.exists()) {
            issues.add("Claude settings.json not found - is Claude CLI installed?")
        }
        if (hookScriptExists && !hookInSettings) {
            issues.add("Hook script exists but not configured in settings.json")
        }
        if (hookInSettings && !hookScriptExists) {
            issues.add("Hook configured in settings.json but script missing")
        }

        return IntegrationStatus(
            isInstalled = hookScriptExists && hookInSettings,
            hookScriptExists = hookScriptExists,
            hookInSettings = hookInSettings,
            hookScriptPath = hookScript.absolutePath,
            settingsPath = settingsFile.absolutePath,
            issues = issues
        )
    }

    /**
     * Install the hooks (script + settings.json entry).
     * Returns success and any error message.
     */
    fun installHooks(): Pair<Boolean, String?> {
        try {
            val claudeHome = ClaudePathResolver.getClaudeHome()
            val settingsFile = File(claudeHome, "settings.json")
            val hooksDir = File(claudeHome, "hooks")
            val hookScript = File(hooksDir, HOOK_SCRIPT_NAME)

            // Create hooks directory
            if (!hooksDir.exists()) {
                hooksDir.mkdirs()
                logger.info("Created hooks directory: ${hooksDir.absolutePath}")
            }

            // Write hook script
            hookScript.writeText(hookScriptContent)
            hookScript.setExecutable(true)
            logger.info("Created hook script: ${hookScript.absolutePath}")

            // Update settings.json
            val settingsUpdated = addHookToSettings(settingsFile)
            if (!settingsUpdated) {
                return Pair(false, "Failed to update settings.json")
            }

            logger.info("Claude hooks installed successfully")
            return Pair(true, null)

        } catch (e: Exception) {
            logger.error("Failed to install hooks: ${e.message}", e)
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Remove the hooks (settings.json entry only, keeps script for safety).
     * Returns success and any error message.
     */
    fun revertHooks(deleteScript: Boolean = false): Pair<Boolean, String?> {
        try {
            val claudeHome = ClaudePathResolver.getClaudeHome()
            val settingsFile = File(claudeHome, "settings.json")
            val hookScript = File(claudeHome, "hooks/$HOOK_SCRIPT_NAME")

            // Remove from settings.json
            val settingsUpdated = removeHookFromSettings(settingsFile)

            // Optionally delete script
            if (deleteScript && hookScript.exists()) {
                hookScript.delete()
                logger.info("Deleted hook script: ${hookScript.absolutePath}")
            }

            if (settingsUpdated) {
                logger.info("Claude hooks reverted successfully")
                return Pair(true, null)
            } else {
                return Pair(false, "Hook entry not found in settings.json")
            }

        } catch (e: Exception) {
            logger.error("Failed to revert hooks: ${e.message}", e)
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Check if our hook is configured in settings.json
     */
    private fun checkHookInSettings(settingsFile: File): Boolean {
        if (!settingsFile.exists()) return false

        return try {
            val content = settingsFile.readText()
            val json = Json.parseToJsonElement(content).jsonObject
            val hooks = json["hooks"]?.jsonObject ?: return false
            val sessionStart = hooks["SessionStart"]?.jsonArray ?: return false

            sessionStart.any { entry ->
                val hooksList = entry.jsonObject["hooks"]?.jsonArray ?: return@any false
                hooksList.any { hook ->
                    val command = hook.jsonObject["command"]?.jsonPrimitive?.content ?: ""
                    command.contains(HOOK_SCRIPT_NAME)
                }
            }
        } catch (e: Exception) {
            logger.debug("Error checking settings.json: ${e.message}")
            false
        }
    }

    /**
     * Add our hook to settings.json
     */
    private fun addHookToSettings(settingsFile: File): Boolean {
        try {
            val jsonFormat = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

            // Read existing settings or create new
            val existingContent = if (settingsFile.exists()) {
                settingsFile.readText()
            } else {
                "{}"
            }

            val rootObject = Json.parseToJsonElement(existingContent).jsonObject.toMutableMap()

            // Get or create hooks object
            val hooksObject = (rootObject["hooks"]?.jsonObject?.toMutableMap() ?: mutableMapOf())

            // Get or create SessionStart array
            val sessionStartArray = (hooksObject["SessionStart"]?.jsonArray?.toMutableList() ?: mutableListOf())

            // Check if our hook already exists
            val alreadyExists = sessionStartArray.any { entry ->
                val hooksList = entry.jsonObject["hooks"]?.jsonArray ?: return@any false
                hooksList.any { hook ->
                    val command = hook.jsonObject["command"]?.jsonPrimitive?.content ?: ""
                    command.contains(HOOK_SCRIPT_NAME)
                }
            }

            if (alreadyExists) {
                logger.info("Hook already exists in settings.json")
                return true
            }

            // Add our hook entry
            val newHookEntry = buildJsonObject {
                put("matcher", "*")
                putJsonArray("hooks") {
                    addJsonObject {
                        put("type", "command")
                        put("command", "\$HOME/.claude/hooks/$HOOK_SCRIPT_NAME")
                    }
                }
            }
            sessionStartArray.add(newHookEntry)

            // Rebuild the hooks object
            hooksObject["SessionStart"] = JsonArray(sessionStartArray)
            rootObject["hooks"] = JsonObject(hooksObject)

            // Write back
            val newContent = jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(rootObject))
            settingsFile.writeText(newContent)

            logger.info("Added hook to settings.json")
            return true

        } catch (e: Exception) {
            logger.error("Failed to add hook to settings.json: ${e.message}", e)
            return false
        }
    }

    /**
     * Remove our hook from settings.json
     */
    private fun removeHookFromSettings(settingsFile: File): Boolean {
        if (!settingsFile.exists()) return false

        try {
            val jsonFormat = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

            val content = settingsFile.readText()
            val rootObject = Json.parseToJsonElement(content).jsonObject.toMutableMap()

            val hooksObject = rootObject["hooks"]?.jsonObject?.toMutableMap() ?: return false
            val sessionStartArray = hooksObject["SessionStart"]?.jsonArray?.toMutableList() ?: return false

            // Filter out our hook
            val originalSize = sessionStartArray.size
            val filtered = sessionStartArray.filter { entry ->
                val hooksList = entry.jsonObject["hooks"]?.jsonArray ?: return@filter true
                !hooksList.any { hook ->
                    val command = hook.jsonObject["command"]?.jsonPrimitive?.content ?: ""
                    command.contains(HOOK_SCRIPT_NAME)
                }
            }

            if (filtered.size == originalSize) {
                logger.info("Hook not found in settings.json")
                return false
            }

            // Update SessionStart (or remove if empty)
            if (filtered.isEmpty()) {
                hooksObject.remove("SessionStart")
            } else {
                hooksObject["SessionStart"] = JsonArray(filtered)
            }

            // Update hooks (or remove if empty)
            if (hooksObject.isEmpty()) {
                rootObject.remove("hooks")
            } else {
                rootObject["hooks"] = JsonObject(hooksObject)
            }

            // Write back
            val newContent = jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(rootObject))
            settingsFile.writeText(newContent)

            logger.info("Removed hook from settings.json")
            return true

        } catch (e: Exception) {
            logger.error("Failed to remove hook from settings.json: ${e.message}", e)
            return false
        }
    }
}
