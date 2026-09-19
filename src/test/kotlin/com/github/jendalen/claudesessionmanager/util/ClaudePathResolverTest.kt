package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudePathResolverTest {

    // Real transcripts open with meta records that carry cwd: null; the first message
    // record has the path. The reader must skip the nulls, not stop at them.
    @Test
    fun `readSessionCwd skips meta records and returns the first real cwd`(@TempDir dir: File) {
        val f = File(dir, "s.jsonl")
        f.writeText(
            """{"type":"mode","cwd":null,"sessionId":"s"}""" + "\n" +
                """{"type":"summary","summary":"x"}""" + "\n" +
                """{"type":"user","cwd":"/Users/x/Projects/Work/Shop-worktree1","message":{}}""" + "\n" +
                """{"type":"assistant","cwd":"/somewhere/else"}""" + "\n"
        )
        assertEquals("/Users/x/Projects/Work/Shop-worktree1", ClaudePathResolver.readSessionCwd(f))
    }

    @Test
    fun `readSessionCwd gives up after maxLines and on garbage`(@TempDir dir: File) {
        val f = File(dir, "s.jsonl")
        f.writeText((1..30).joinToString("\n") { """{"type":"meta","cwd":null}""" } + "\n" + """{"cwd":"/late"}""")
        assertNull(ClaudePathResolver.readSessionCwd(f, maxLines = 20))
        assertEquals("/late", ClaudePathResolver.readSessionCwd(f, maxLines = 40))

        f.writeText("not json at all\n{broken\n" + """{"cwd":"/after/garbage"}""")
        assertEquals("/after/garbage", ClaudePathResolver.readSessionCwd(f))

        assertNull(ClaudePathResolver.readSessionCwd(File(dir, "missing.jsonl")))
    }

    // The trap the output-channel guard exists to avoid: a sibling worktree is NOT under
    // the project, even though its encoded transcript directory is a prefix match.
    @Test
    fun `isPathMatch respects directory boundaries`() {
        assertTrue(ClaudePathResolver.isPathMatch("/p/Shop", "/p/Shop"))
        assertTrue(ClaudePathResolver.isPathMatch("/p/Shop", "/p/Shop/Shop/adonis-api"))
        assertTrue(ClaudePathResolver.isPathMatch("/p/Shop/", "/p/Shop"))
        assertFalse(ClaudePathResolver.isPathMatch("/p/Shop", "/p/Shop-worktree1"))
        assertFalse(ClaudePathResolver.isPathMatch("/p/Shop-worktree1", "/p/Shop"))
        assertFalse(ClaudePathResolver.isPathMatch("/p/Shop", "/q/Shop"))
    }
}
