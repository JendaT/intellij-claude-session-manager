package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SessionTitleReaderTest {

    private val id = "4e695d61-3fa2-40af-8f76-45977b1cddfa"

    private fun record(title: String) = """{"type":"custom-title","customTitle":"$title","sessionId":"$id"}"""

    private fun transcript(dir: File, vararg lines: String): File =
        File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun sidecar(dir: File, title: String) {
        File(dir, id).mkdirs()
        File(dir, "$id/custom-title.json").writeText("""{"customTitle":"$title"}""")
    }

    @Test
    fun `newest tail record wins over older records and the sidecar`(@TempDir dir: File) {
        val f = transcript(dir, """{"type":"user","cwd":"/x"}""", record("first"), """{"type":"assistant"}""", record("second"))
        sidecar(dir, "stale-sidecar")
        assertEquals("second", SessionTitleReader.read(f))
    }

    @Test
    fun `sidecar answers when the transcript has no record`(@TempDir dir: File) {
        val f = transcript(dir, """{"type":"user","cwd":"/x"}""")
        assertNull(SessionTitleReader.read(f))
        sidecar(dir, "from-sidecar")
        assertEquals("from-sidecar", SessionTitleReader.read(f))
    }

    // A blank newest record means "renamed to nothing"; it must not fall through to an
    // older non-blank record or to the sidecar.
    @Test
    fun `blank newest record is no title`(@TempDir dir: File) {
        val f = transcript(dir, record("old"), record(""))
        sidecar(dir, "sidecar")
        assertNull(SessionTitleReader.read(f))
    }

    @Test
    fun `garbage lines and a partial last line are skipped`(@TempDir dir: File) {
        val f = File(dir, "$id.jsonl")
        f.writeText(record("good") + "\n" + "{not json \"type\":\"custom-title\"\n" + record("partial").dropLast(5))
        assertEquals("good", SessionTitleReader.read(f))
    }

    @Test
    fun `a record older than the window is missed and the sidecar answers`(@TempDir dir: File) {
        val filler = """{"type":"assistant","message":"${"x".repeat(1000)}"}"""
        val lines = listOf(record("too-old")) + List((SessionTitleReader.TAIL_BYTES / 1000).toInt() + 5) { filler }
        val f = transcript(dir, *lines.toTypedArray())
        assertNull(SessionTitleReader.read(f))
        sidecar(dir, "sidecar")
        assertEquals("sidecar", SessionTitleReader.read(f))
    }

    // The window-start check must compare byte offsets: a decoded length differs from the
    // file length as soon as the transcript holds non-ASCII text, and a short transcript
    // then looked truncated and lost its first line - the only title record.
    @Test
    fun `non-ascii transcript shorter than the window keeps its first line`(@TempDir dir: File) {
        val f = transcript(dir, record("Platební brána"), """{"type":"assistant","message":"Příliš žluťoučký kůň"}""")
        assertEquals("Platební brána", SessionTitleReader.read(f))
    }

    @Test
    fun `missing and empty files read as no title`(@TempDir dir: File) {
        assertNull(SessionTitleReader.read(File(dir, "$id.jsonl")))
        assertNull(SessionTitleReader.read(File(dir, "$id.jsonl").apply { writeText("") }))
    }

    @Test
    fun `fingerprint follows the transcript and the sidecar`(@TempDir dir: File) {
        val f = transcript(dir, record("a"))
        val before = SessionTitleReader.fingerprint(f)
        f.appendText(record("b") + "\n")
        val grown = SessionTitleReader.fingerprint(f)
        assertNotEquals(before, grown)
        sidecar(dir, "c")
        File(dir, "$id/custom-title.json").setLastModified(before.sidecarModified + 10_000)
        assertNotEquals(grown, SessionTitleReader.fingerprint(f))
        assertEquals(File(dir, "$id/custom-title.json"), SessionTitleReader.sidecarFile(f))
    }
}
