package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTailTest {

    // A session resumed from another directory keeps its id and appends records with the
    // new cwd; "where it last ran" is the newest cwd, not the first.
    @Test
    fun `lastCwd is the newest recorded cwd`(@TempDir dir: File) {
        val f = File(dir, "s.jsonl")
        f.writeText(
            """{"type":"mode","cwd":null}""" + "\n" +
                """{"type":"user","cwd":"/Users/me/Projects/Acme/source/acme-server"}""" + "\n" +
                """{"type":"assistant","cwd":"/Users/me/Projects/Acme/worktrees/worker1"}""" + "\n" +
                """{"type":"custom-title","customTitle":"x"}""" + "\n"
        )
        assertEquals("/Users/me/Projects/Acme/worktrees/worker1", TranscriptTail.lastCwd(f))
    }

    @Test
    fun `lastCwd unescapes and tolerates missing files and a partial last line`(@TempDir dir: File) {
        val f = File(dir, "s.jsonl")
        f.writeText("""{"cwd":"/a \"quoted\" dir"}""" + "\n" + """{"cwd":"/being/writ""")
        assertEquals("/a \"quoted\" dir", TranscriptTail.lastCwd(f))
        assertNull(TranscriptTail.lastCwd(File(dir, "missing.jsonl")))
    }

    @Test
    fun `completeLines drops the partial first line of a window`(@TempDir dir: File) {
        val f = File(dir, "s.jsonl")
        f.writeText("aaaaaaaaaa\nbbb\nccc\n")
        assertEquals(listOf("bbb", "ccc"), TranscriptTail.completeLines(f, bytes = 12))
        assertEquals(listOf("aaaaaaaaaa", "bbb", "ccc"), TranscriptTail.completeLines(f))
    }
}
