package com.github.jendalen.claudesessionmanager

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source guard for the 2026-09-30 WebStorm hang: restoring 26 tabs, the UI thread ran
 * project.save() from BindingService's debounce alarm (a modal progress under the
 * write-intent lock), every Dispatchers.Default worker parked on the lock, and two
 * title-sync threads were parked in invokeAndWait. None of these calls may come back.
 * The IDE cannot be started in these unit tests; the live check is in
 * docs/runbooks/build-install-diagnose.md ("Freeze check after a multi-tab restore").
 */
class NoBlockingUiWaitsTest {

    private val forbidden = listOf(
        Regex("""\.save\(\s*\)"""),                  // Project.save(): modal progress on the UI thread
        Regex("""\binvokeAndWait\b"""),              // parks the caller until the UI thread is free
        Regex("""\brunBlocking\b"""),                // same, from coroutine land
        Regex("""\bsaveSettingsUnderModalProgress\b"""),
        Regex("""\brunWithModalProgressBlocking\b"""),
        Regex("""\bsaveAll\(""")
    )

    @Test
    fun `main sources contain no synchronous saves or blocking waits for the UI thread`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the project root: ${File(".").absolutePath}")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            stripComments(file.readText()).lineSequence().mapIndexedNotNull { i, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { "${file.path}:${i + 1}: ${line.trim()}" }
            }
        }.toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the binding save goes through the platform save queue`() {
        val source = File("src/main/kotlin/com/github/jendalen/claudesessionmanager/services/BindingService.kt").readText()
        assertTrue(source.contains("SaveAndSyncHandler.getInstance().scheduleProjectSave(project)"))
        assertTrue(source.contains("Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)"))
    }

    /** Blanks comments, keeping line numbers. Good enough for this codebase's style. */
    private fun stripComments(text: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(text) { m -> m.value.replace(Regex("[^\n]"), " ") }
        return noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0 && !line.substring(0, idx).contains('"')) line.substring(0, idx) else line
        }
    }
}
