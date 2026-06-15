package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File

/**
 * Toggles Claude Code's terminal-title takeover via the `CLAUDE_CODE_DISABLE_TERMINAL_TITLE`
 * environment variable in `~/.claude/settings.json`.
 *
 * Claude Code emits an OSC-0 escape sequence that recent JetBrains builds honor, renaming the
 * terminal tab (to "Claude Code", a session name, etc.). The plugin no longer depends on the
 * tab name for binding identity, but users who want to keep their own custom tab names can
 * disable the takeover entirely. Setting the env var to "1" disables it; clearing it restores
 * Claude Code's default behavior.
 *
 * This is an all-or-nothing switch exposed by Claude Code itself (there is no per-format option).
 */
@Service(Service.Level.APP)
class ClaudeTitleSettingsService {

    private val logger = Logger.getInstance(ClaudeTitleSettingsService::class.java)

    companion object {
        const val ENV_KEY = "CLAUDE_CODE_DISABLE_TERMINAL_TITLE"

        fun getInstance(): ClaudeTitleSettingsService {
            return ApplicationManager.getApplication().getService(ClaudeTitleSettingsService::class.java)
        }
    }

    private fun settingsFile(): File = File(ClaudePathResolver.getClaudeHome(), "settings.json")

    /**
     * Returns true if the env var is currently set to "1" in ~/.claude/settings.json.
     */
    fun isTitleDisabled(): Boolean {
        return try {
            val file = settingsFile()
            if (!file.exists()) return false
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val value = root["env"]?.jsonObject?.get(ENV_KEY)?.jsonPrimitive?.contentOrNull
            value == "1"
        } catch (e: Exception) {
            logger.debug("Could not read $ENV_KEY from settings.json: ${e.message}")
            false
        }
    }

    /**
     * Sets or clears the env var, preserving all other content in settings.json.
     * When [disabled] is false the key is removed (and an emptied `env` object is dropped).
     *
     * @return success flag and an optional error message.
     */
    fun setTitleDisabled(disabled: Boolean): Pair<Boolean, String?> {
        return try {
            val file = settingsFile()
            val jsonFormat = Json { prettyPrint = true; ignoreUnknownKeys = true }

            val existing = if (file.exists()) file.readText() else "{}"
            val root = Json.parseToJsonElement(existing).jsonObject.toMutableMap()
            val env = root["env"]?.jsonObject?.toMutableMap() ?: mutableMapOf()

            if (disabled) {
                env[ENV_KEY] = JsonPrimitive("1")
            } else {
                env.remove(ENV_KEY)
            }

            if (env.isEmpty()) {
                root.remove("env")
            } else {
                root["env"] = JsonObject(env)
            }

            file.parentFile?.mkdirs()
            file.writeText(jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(root)))
            logger.info("Set $ENV_KEY=${if (disabled) "1" else "(removed)"} in ${file.absolutePath}")
            Pair(true, null)
        } catch (e: Exception) {
            logger.warn("Failed to update $ENV_KEY in settings.json: ${e.message}", e)
            Pair(false, e.message)
        }
    }
}
