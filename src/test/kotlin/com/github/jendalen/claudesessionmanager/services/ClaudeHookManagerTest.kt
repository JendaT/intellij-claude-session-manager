package com.github.jendalen.claudesessionmanager.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Installer merge semantics against a settings.json that already carries other tools'
 * hooks under the same events (the real ~/.claude/settings.json has aoe-sound and notchi
 * entries under SessionStart, Stop, PreToolUse, ...).
 */
class ClaudeHookManagerTest {

    private val foreignSettings = """
        {
          "model": "opus",
          "env": { "SOMETHING": "1" },
          "hooks": {
            "SessionStart": [
              { "matcher": "*", "hooks": [ { "type": "command", "command": "~/.claude/hooks/other.sh" } ] }
            ],
            "Stop": [
              { "hooks": [ { "type": "command", "command": "~/.claude/hooks/other.sh" } ] }
            ],
            "PreToolUse": [
              { "matcher": "Bash", "hooks": [ { "type": "command", "command": "~/.claude/hooks/other.sh" } ] }
            ],
            "PostToolUse": [
              { "hooks": [ { "type": "command", "command": "~/.claude/hooks/other.sh" } ] }
            ]
          }
        }
    """.trimIndent()

    private fun home(dir: File, settings: String? = foreignSettings): File {
        if (settings != null) File(dir, "settings.json").writeText(settings)
        return dir
    }

    private fun hooksOf(dir: File) = Json.parseToJsonElement(File(dir, "settings.json").readText())
        .jsonObject["hooks"]!!.jsonObject

    private fun commandsUnder(dir: File, event: String): List<String> =
        hooksOf(dir)[event]?.jsonArray?.flatMap { entry ->
            entry.jsonObject["hooks"]!!.jsonArray.map { it.jsonObject["command"]!!.jsonPrimitive.content }
        } ?: emptyList()

    @Test
    fun `install merges one entry per event and keeps foreign hooks`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        val (ok, error) = manager.installHooks(home(dir))
        assertTrue(ok, error)

        val hooks = hooksOf(dir)
        for ((event, _) in ClaudeHookManager.HOOK_EVENTS) {
            val commands = commandsUnder(dir, event)
            assertEquals(1, commands.count { it.contains("session-notify.sh") }, "one entry under $event")
        }
        // Foreign entries untouched, including under events we do not register for.
        assertTrue(commandsUnder(dir, "SessionStart").contains("~/.claude/hooks/other.sh"))
        assertTrue(commandsUnder(dir, "Stop").contains("~/.claude/hooks/other.sh"))
        assertTrue(commandsUnder(dir, "PreToolUse").contains("~/.claude/hooks/other.sh"))
        assertEquals(listOf("~/.claude/hooks/other.sh"), commandsUnder(dir, "PostToolUse"))
        assertEquals("Bash", hooks["PreToolUse"]!!.jsonArray[0].jsonObject["matcher"]!!.jsonPrimitive.content)
        // Notification carries the permission_prompt matcher; Stop has none.
        val notification = hooks["Notification"]!!.jsonArray.single().jsonObject
        assertEquals("permission_prompt", notification["matcher"]!!.jsonPrimitive.content)
        assertFalse(hooks["Stop"]!!.jsonArray.last().jsonObject.containsKey("matcher"))
        // Other top-level keys survive.
        val root = Json.parseToJsonElement(File(dir, "settings.json").readText()).jsonObject
        assertEquals("opus", root["model"]!!.jsonPrimitive.content)
        assertEquals("1", root["env"]!!.jsonObject["SOMETHING"]!!.jsonPrimitive.content)

        val script = File(dir, "hooks/session-notify.sh")
        assertTrue(script.canExecute())
        assertTrue(script.readText().contains("csm-hook-version: 2"))
        assertTrue(manager.getIntegrationStatus(dir).isInstalled)
    }

    @Test
    fun `install is idempotent`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        manager.installHooks(home(dir))
        val first = File(dir, "settings.json").readText()
        manager.installHooks(dir)
        assertEquals(first, File(dir, "settings.json").readText())
    }

    @Test
    fun `v1 install upgrades in place and reports outdated before`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        home(dir, """{"hooks":{"SessionStart":[{"matcher":"*","hooks":[{"type":"command","command":"${'$'}HOME/.claude/hooks/session-notify.sh"}]}]}}""")
        File(dir, "hooks").mkdirs()
        File(dir, "hooks/session-notify.sh").writeText("#!/bin/bash\n# old\necho ide-events\n")

        val before = manager.getIntegrationStatus(dir)
        assertTrue(before.hookScriptExists)
        assertFalse(before.hookScriptCurrent)
        assertFalse(before.isInstalled)
        assertEquals(listOf("UserPromptSubmit", "PreToolUse", "Notification", "Stop", "SessionEnd"), before.missingEvents)

        assertTrue(manager.installHooks(dir).first)
        // The v1 SessionStart entry is reused, not duplicated.
        assertEquals(1, commandsUnder(dir, "SessionStart").size)
        assertTrue(manager.getIntegrationStatus(dir).isInstalled)
    }

    @Test
    fun `install into a missing settings file creates it`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        assertTrue(manager.installHooks(home(dir, settings = null)).first)
        assertEquals(ClaudeHookManager.HOOK_EVENTS.size, hooksOf(dir).size)
    }

    @Test
    fun `revert removes only our entries and prunes empty containers`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        manager.installHooks(home(dir))
        val (ok, _) = manager.revertHooks(deleteScript = true, claudeHome = dir)
        assertTrue(ok)

        val hooks = hooksOf(dir)
        assertEquals(setOf("SessionStart", "Stop", "PreToolUse", "PostToolUse"), hooks.keys)
        for (event in hooks.keys) {
            assertEquals(listOf("~/.claude/hooks/other.sh"), commandsUnder(dir, event))
        }
        assertFalse(File(dir, "hooks/session-notify.sh").exists())
        assertFalse(manager.getIntegrationStatus(dir).hookInSettings)
    }

    @Test
    fun `revert on a file without our hooks reports not found`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        home(dir)
        assertFalse(manager.revertHooks(claudeHome = dir).first)
        assertEquals(foreignSettings, File(dir, "settings.json").readText())
    }

    // A third-party hook that happens to share our script's basename is not ours: install
    // must still add the plugin's entry, and revert must leave the foreign one alone.
    @Test
    fun `a foreign hook with the same basename is not treated as ours`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        home(dir, """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"/opt/other-tool/session-notify.sh"}]}]}}""")
        assertFalse(manager.getIntegrationStatus(dir).hookInSettings)

        assertTrue(manager.installHooks(dir).first)
        assertEquals(
            listOf("/opt/other-tool/session-notify.sh", "${'$'}HOME/.claude/hooks/session-notify.sh"),
            commandsUnder(dir, "Stop")
        )

        assertTrue(manager.revertHooks(claudeHome = dir).first)
        assertEquals(listOf("/opt/other-tool/session-notify.sh"), commandsUnder(dir, "Stop"))
    }

    // An entry whose hooks array holds both our command and another tool's must keep the
    // other tool's on revert; only an entry left empty is dropped.
    @Test
    fun `revert removes only our command from a shared entry`(@TempDir dir: File) {
        val manager = ClaudeHookManager()
        home(dir, """{"hooks":{"Stop":[{"hooks":[
            {"type":"command","command":"${'$'}HOME/.claude/hooks/session-notify.sh"},
            {"type":"command","command":"~/.claude/hooks/other.sh"}
        ]}]}}""")
        assertTrue(manager.revertHooks(claudeHome = dir).first)
        assertEquals(listOf("~/.claude/hooks/other.sh"), commandsUnder(dir, "Stop"))
        assertEquals(1, hooksOf(dir)["Stop"]!!.jsonArray.size, "the entry itself survives")
    }
}
