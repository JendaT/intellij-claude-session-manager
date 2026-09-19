package com.github.jendalen.claudesessionmanager.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the bundled hook script under bash with fixture stdin for every event. Skipped
 * where bash or jq are missing (no CI exists; on the development Mac both are present).
 */
class HookScriptTest {

    private val id = "4e695d61-3fa2-40af-8f76-45977b1cddfa"

    @TempDir
    lateinit var events: File

    private lateinit var script: File

    @BeforeEach
    fun setUp() {
        assumeTrue(File("/bin/bash").canExecute(), "bash missing")
        assumeTrue(System.getenv("PATH").split(File.pathSeparator).any { File(it, "jq").canExecute() }, "jq missing")
        script = File(events, "session-notify.sh")
        script.writeText(ClaudeHookManager().hookScriptContent)
        script.setExecutable(true)
    }

    private fun run(payload: String): Pair<Int, String> {
        val process = ProcessBuilder("/bin/bash", script.absolutePath)
            .apply { environment()["CLAUDE_IDE_EVENTS_DIR"] = events.absolutePath }
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(payload.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "hook script hung")
        return process.exitValue() to output
    }

    private fun sessionFile() = File(events, "sessions/$id.json")

    private fun session() = Json.parseToJsonElement(sessionFile().readText()).jsonObject

    private fun event(name: String, extra: String = "") =
        """{"hook_event_name":"$name","session_id":"$id","cwd":"/tmp/proj"$extra}"""

    @Test
    fun `full lifecycle idle busy waiting idle and removal`() {
        val (rc, out) = run(event("SessionStart", ""","source":"resume""""))
        assertEquals(0, rc)
        assertEquals("", out, "hook must not print to stdout")
        val current = Json.parseToJsonElement(File(events, "current-session.json").readText()).jsonObject
        assertEquals(id, current["sessionId"]!!.jsonPrimitive.content)
        assertEquals("resume", current["source"]!!.jsonPrimitive.content)
        assertTrue(current["timestamp"]!!.jsonPrimitive.content.toLong() > 1_700_000_000_000L, "millisecond timestamp")
        var obj = session()
        assertEquals("idle", obj["state"]!!.jsonPrimitive.content)
        assertEquals("/tmp/proj", obj["cwd"]!!.jsonPrimitive.content)
        assertTrue(obj["updated_at"]!!.jsonPrimitive.content.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}")))

        // The plugin adds its fields; the script must keep them from now on.
        SessionRegistryService().upsert(id, "proj", "Local", null, File(events, "sessions"))

        run(event("UserPromptSubmit"))
        obj = session()
        assertEquals("busy", obj["state"]!!.jsonPrimitive.content)
        assertEquals("proj", obj["project"]!!.jsonPrimitive.content)
        assertEquals("Local", obj["tab"]!!.jsonPrimitive.content)

        run(event("Notification", ""","notification_type":"permission_prompt""""))
        assertEquals("waiting", session()["state"]!!.jsonPrimitive.content)

        run(event("Notification", ""","notification_type":"idle_prompt""""))
        assertEquals("waiting", session()["state"]!!.jsonPrimitive.content, "other notifications do not change state")

        run(event("PreToolUse", ""","tool_name":"Bash""""))
        assertEquals("busy", session()["state"]!!.jsonPrimitive.content)

        run(event("Stop"))
        assertEquals("idle", session()["state"]!!.jsonPrimitive.content)
        assertEquals("Local", session()["tab"]!!.jsonPrimitive.content)

        run(event("SessionEnd", ""","reason":"other""""))
        assertFalse(sessionFile().exists())
        assertTrue(File(events, "sessions").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `state events create the file when SessionStart was missed`() {
        run(event("UserPromptSubmit"))
        assertEquals("busy", session()["state"]!!.jsonPrimitive.content)
        assertEquals(id, session()["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `corrupt file is rewritten`() {
        File(events, "sessions").mkdirs()
        sessionFile().writeText("garbage")
        run(event("Stop"))
        assertEquals("idle", session()["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid session ids and garbage input are ignored with exit 0`() {
        assertEquals(0, run("""{"hook_event_name":"SessionStart","session_id":"../evil"}""").first)
        assertEquals(0, run("not json at all").first)
        assertEquals(0, run("").first)
        assertNull(File(events, "sessions").listFiles()?.firstOrNull { it.name.endsWith(".json") })
        assertFalse(File(events, "current-session.json").exists())
    }
}
