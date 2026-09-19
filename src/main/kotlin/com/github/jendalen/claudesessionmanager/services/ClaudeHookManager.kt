package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File

/**
 * Manages Claude Code hook integration for session detection and the session registry.
 *
 * The plugin installs one hook script and registers it for several Claude Code events:
 * SessionStart (session detection, as before) plus UserPromptSubmit, PreToolUse,
 * Notification (permission_prompt), Stop and SessionEnd, which maintain the per-session
 * state files under ~/.claude/ide-events/sessions/ that external tools read. This
 * service handles:
 * - Checking if hooks are installed and current
 * - Installing hooks (modifying settings.json + creating hook script)
 * - Reverting hooks (removing from settings.json)
 * - Showing what exactly was modified for transparency
 *
 * settings.json is shared with other tools' hooks under the same events: entries are
 * merged in and removed individually, the rest of the file is never rewritten.
 */
@Service(Service.Level.APP)
class ClaudeHookManager {

    private val logger = Logger.getInstance(ClaudeHookManager::class.java)

    companion object {
        private const val HOOK_SCRIPT_NAME = "session-notify.sh"
        private const val HOOK_SCRIPT_RESOURCE = "/hooks/session-notify.sh"
        private const val HOOK_VERSION_MARKER = "# csm-hook-version: 2"
        private const val HOOK_TIMEOUT_SECONDS = 5

        /**
         * Events the script is registered for, with the matcher each entry carries
         * (null = no matcher key: the event has no matcher or all values are wanted).
         * SessionStart keeps "*" for compatibility with existing installs.
         */
        val HOOK_EVENTS: List<Pair<String, String?>> = listOf(
            "SessionStart" to "*",
            "UserPromptSubmit" to null,
            "PreToolUse" to null,
            "Notification" to "permission_prompt",
            "Stop" to null,
            "SessionEnd" to null
        )

        fun getInstance(): ClaudeHookManager {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ClaudeHookManager::class.java)
        }
    }

    /**
     * The hook script content, bundled as a plugin resource.
     */
    val hookScriptContent: String by lazy {
        ClaudeHookManager::class.java.getResourceAsStream(HOOK_SCRIPT_RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Bundled hook script missing: $HOOK_SCRIPT_RESOURCE")
    }

    /**
     * The entries that go into settings.json, one per event, for display.
     */
    val hookConfig: String by lazy {
        val pretty = Json { prettyPrint = true }
        HOOK_EVENTS.joinToString("\n\n") { (event, matcher) ->
            "hooks.$event:\n" + pretty.encodeToString(JsonObject.serializer(), hookEntry(matcher))
        }
    }

    private fun hookEntry(matcher: String?): JsonObject = buildJsonObject {
        if (matcher != null) put("matcher", matcher)
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", "\$HOME/.claude/hooks/$HOOK_SCRIPT_NAME")
                put("timeout", HOOK_TIMEOUT_SECONDS)
            }
        }
    }

    /**
     * The command string the installer writes, plus the two spellings a user may have
     * hand-edited it into. Matched exactly: a `contains(HOOK_SCRIPT_NAME)` test treated
     * any third-party hook with the same basename as ours - install then skipped adding
     * the plugin's entry and revert deleted the foreign one.
     */
    private val ownedCommands: Set<String> = setOf(
        "\$HOME/.claude/hooks/$HOOK_SCRIPT_NAME",
        "~/.claude/hooks/$HOOK_SCRIPT_NAME",
        "${System.getProperty("user.home")}/.claude/hooks/$HOOK_SCRIPT_NAME"
    )

    private fun JsonElement.isOurHook(): Boolean =
        ((this as? JsonObject)?.get("command")?.jsonPrimitive?.contentOrNull ?: "") in ownedCommands

    private fun JsonElement.isOurEntry(): Boolean {
        val hooksList = (this as? JsonObject)?.get("hooks")?.jsonArray ?: return false
        return hooksList.any { it.isOurHook() }
    }

    /**
     * The entry with our hook removed from its `hooks` array, or null when nothing else
     * was in it. Another tool's command sharing the same entry survives - dropping the
     * whole entry took its hook with ours, against the "merge, never overwrite other
     * keys" promise.
     */
    private fun JsonElement.withoutOurHook(): JsonElement? {
        val obj = this as? JsonObject ?: return this
        val hooksList = obj["hooks"]?.jsonArray ?: return this
        val kept = hooksList.filterNot { it.isOurHook() }
        if (kept.size == hooksList.size) return this
        if (kept.isEmpty()) return null
        return JsonObject(obj.toMutableMap().apply { put("hooks", JsonArray(kept)) })
    }

    data class IntegrationStatus(
        val isInstalled: Boolean,
        val hookScriptExists: Boolean,
        val hookScriptCurrent: Boolean,
        val hookInSettings: Boolean,
        val hookScriptPath: String,
        val settingsPath: String,
        val missingEvents: List<String> = emptyList(),
        val issues: List<String> = emptyList()
    )

    /**
     * Check the current integration status. "Installed" means the bundled script version
     * is on disk and every event has our entry - an older script or a partial entry set
     * (e.g. a v1 install with SessionStart only) reports as not installed so the settings
     * page offers the upgrade.
     */
    fun getIntegrationStatus(claudeHome: File = ClaudePathResolver.getClaudeHome()): IntegrationStatus {
        val settingsFile = File(claudeHome, "settings.json")
        val hookScript = File(claudeHome, "hooks/$HOOK_SCRIPT_NAME")
        val issues = mutableListOf<String>()

        val scriptText = if (hookScript.exists()) hookScript.readText() else ""
        val hookScriptExists = scriptText.contains("ide-events")
        val hookScriptCurrent = scriptText.contains(HOOK_VERSION_MARKER)
        val hooksObject = readHooksObject(settingsFile)
        val missingEvents = HOOK_EVENTS.map { it.first }.filterNot { event ->
            hooksObject?.get(event)?.jsonArray?.any { it.isOurEntry() } == true
        }
        val hookInSettings = missingEvents.isEmpty()

        if (!settingsFile.exists()) {
            issues.add("Claude settings.json not found - is Claude CLI installed?")
        }
        if (hookScriptExists && !hookScriptCurrent) {
            issues.add("Hook script is an older version - reinstall to enable the session registry")
        }
        if (hookScriptExists && missingEvents.isNotEmpty()) {
            issues.add("Hook not configured in settings.json for: ${missingEvents.joinToString(", ")}")
        }
        if (missingEvents.size < HOOK_EVENTS.size && !hookScriptExists) {
            issues.add("Hook configured in settings.json but script missing")
        }

        return IntegrationStatus(
            isInstalled = hookScriptExists && hookScriptCurrent && hookInSettings,
            hookScriptExists = hookScriptExists,
            hookScriptCurrent = hookScriptCurrent,
            hookInSettings = hookInSettings,
            hookScriptPath = hookScript.absolutePath,
            settingsPath = settingsFile.absolutePath,
            missingEvents = missingEvents,
            issues = issues
        )
    }

    /**
     * Install the hooks (script + settings.json entry).
     * Returns success and any error message.
     */
    fun installHooks(claudeHome: File = ClaudePathResolver.getClaudeHome()): Pair<Boolean, String?> {
        try {
            val settingsFile = File(claudeHome, "settings.json")
            val hooksDir = File(claudeHome, "hooks")
            val hookScript = File(hooksDir, HOOK_SCRIPT_NAME)

            // Create hooks directory
            if (!hooksDir.exists()) {
                hooksDir.mkdirs()
                logger.info("Created hooks directory: ${hooksDir.absolutePath}")
            }

            // Write hook script. Never through a symlink: writeText follows one, and a
            // link planted at this path would redirect a root-of-trust script anywhere.
            // The mode is set explicitly rather than inherited - setExecutable(true) only
            // adds the owner bit to whatever an existing file had.
            if (java.nio.file.Files.isSymbolicLink(hookScript.toPath())) {
                logger.warn("Refusing to write hook script: ${hookScript.absolutePath} is a symbolic link")
                return false to "Hook script path is a symbolic link; remove it and install again"
            }
            // Written to a sibling temp file and renamed into place: bash reads a script
            // incrementally, and PreToolUse runs this one on every tool call of every
            // live session, so a truncate-and-write could be executed half-written.
            val tmp = File(hooksDir, ".$HOOK_SCRIPT_NAME.${System.nanoTime()}.tmp")
            tmp.writeText(hookScriptContent)
            try {
                java.nio.file.Files.setPosixFilePermissions(
                    tmp.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
                )
            } catch (e: UnsupportedOperationException) {
                tmp.setExecutable(true)
            }
            java.nio.file.Files.move(
                tmp.toPath(), hookScript.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
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
    fun revertHooks(
        deleteScript: Boolean = false,
        claudeHome: File = ClaudePathResolver.getClaudeHome()
    ): Pair<Boolean, String?> {
        try {
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
     * Check if our hook is configured in settings.json for [event].
     */
    /**
     * The `hooks` object of settings.json, or null when the file is absent, has none, or
     * does not parse. Parsed once per status check (six events used to mean six reads
     * and parses, on the EDT), and a parse failure says so at INFO - it used to show as
     * "not installed" for every event with nothing in the log explaining why.
     */
    private fun readHooksObject(settingsFile: File): JsonObject? {
        if (!settingsFile.exists()) return null
        return try {
            Json.parseToJsonElement(settingsFile.readText()).jsonObject["hooks"]?.jsonObject
        } catch (e: Exception) {
            logger.info("Cannot read hooks from ${settingsFile.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * Add our entry to every event in [HOOK_EVENTS], leaving all other entries (other
     * tools hook the same events) untouched. Idempotent: events that already carry our
     * entry are not modified, so a second install is a no-op write.
     */
    private fun addHookToSettings(settingsFile: File): Boolean {
        try {
            val jsonFormat = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

            val existingContent = if (settingsFile.exists()) settingsFile.readText() else "{}"
            val rootObject = Json.parseToJsonElement(existingContent).jsonObject.toMutableMap()
            val hooksObject = (rootObject["hooks"]?.jsonObject?.toMutableMap() ?: mutableMapOf())

            var added = 0
            for ((event, matcher) in HOOK_EVENTS) {
                val entries = hooksObject[event]?.jsonArray?.toMutableList() ?: mutableListOf()
                if (entries.any { it.isOurEntry() }) continue
                entries.add(hookEntry(matcher))
                hooksObject[event] = JsonArray(entries)
                added++
            }

            if (added == 0) {
                logger.info("Hook already present for all ${HOOK_EVENTS.size} events in settings.json")
                return true
            }

            rootObject["hooks"] = JsonObject(hooksObject)
            settingsFile.writeText(jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(rootObject)))
            logger.info("Added hook to settings.json for $added event(s)")
            return true

        } catch (e: Exception) {
            logger.error("Failed to add hook to settings.json: ${e.message}", e)
            return false
        }
    }

    /**
     * Remove our entry from every event; empty arrays and an empty hooks object are
     * dropped, everything else is left as it was.
     */
    private fun removeHookFromSettings(settingsFile: File): Boolean {
        if (!settingsFile.exists()) return false

        try {
            val jsonFormat = Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

            val rootObject = Json.parseToJsonElement(settingsFile.readText()).jsonObject.toMutableMap()
            val hooksObject = rootObject["hooks"]?.jsonObject?.toMutableMap() ?: return false

            var removed = 0
            for ((event, _) in HOOK_EVENTS) {
                val entries = hooksObject[event]?.jsonArray ?: continue
                val owned = entries.count { it.isOurEntry() }
                if (owned == 0) continue
                removed += owned
                val filtered = entries.mapNotNull { it.withoutOurHook() }
                if (filtered.isEmpty()) hooksObject.remove(event) else hooksObject[event] = JsonArray(filtered)
            }

            if (removed == 0) {
                logger.info("Hook not found in settings.json")
                return false
            }

            if (hooksObject.isEmpty()) rootObject.remove("hooks") else rootObject["hooks"] = JsonObject(hooksObject)
            settingsFile.writeText(jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(rootObject)))
            logger.info("Removed $removed hook entr${if (removed == 1) "y" else "ies"} from settings.json")
            return true

        } catch (e: Exception) {
            logger.error("Failed to remove hook from settings.json: ${e.message}", e)
            return false
        }
    }
}
