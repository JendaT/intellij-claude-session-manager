package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LaunchCommandTest {

    private val id = "4e695d61-3fa2-40af-8f76-45977b1cddfa"

    @Test
    fun `resume without extra args has no double space`() {
        assertEquals("claude --resume $id", LaunchCommand.resume("claude", "", id))
        assertEquals("claude --resume $id", LaunchCommand.resume("claude", "   ", id))
    }

    @Test
    fun `resume inserts extra args between command and --resume`() {
        assertEquals(
            "claude-x --dangerously-load-development-channels plugin:a@b --resume $id",
            LaunchCommand.resume("claude-x", "--dangerously-load-development-channels plugin:a@b", id)
        )
    }

    @Test
    fun `start with and without extra args`() {
        assertEquals("claude", LaunchCommand.start("claude", ""))
        assertEquals("claude --verbose", LaunchCommand.start("claude ", " --verbose "))
    }

    @Test
    fun `resume appends a single-quoted name after the session id`() {
        assertEquals(
            "claude --channels plugin:a@b --resume $id --name 'payments bug (2)'",
            LaunchCommand.resume("claude", "--channels plugin:a@b", id, "payments bug (2)")
        )
        assertEquals("claude --resume $id", LaunchCommand.resume("claude", "", id, null))
    }

    @Test
    fun `rejects names that cannot be single-quoted`() {
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", id, "it's") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", id, "a\\b") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", id, "two\nlines") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", id, " ") }
    }

    // A tab profile's command changes directory first; && so a missing directory runs
    // nothing rather than Claude in the wrong place.
    @Test
    fun `profile launches cd into the quoted directory first`() {
        val home = System.getProperty("user.home")
        assertEquals(
            "cd '$home/Projects/Acme/worktrees/worker1' && claude --channels plugin:inbox-channel@local-tools --resume $id",
            LaunchCommand.resume("claude", "--channels plugin:inbox-channel@local-tools", id, workingDirectory = "~/Projects/Acme/worktrees/worker1")
        )
        assertEquals("cd '/w/my dir' && claude", LaunchCommand.start("claude", "", "/w/my dir/"))
        assertEquals("claude --resume $id", LaunchCommand.resume("claude", "", id, workingDirectory = null))
        assertThrows<IllegalArgumentException> { LaunchCommand.start("claude", "", "/a'b") }
        assertThrows<IllegalArgumentException> { LaunchCommand.start("claude", "", "relative") }
    }

    @Test
    fun `rejects invalid session ids and invalid args`() {
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", "not-a-uuid") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", "$id; rm -rf ~") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "--a; ls", id) }
        assertThrows<IllegalArgumentException> { LaunchCommand.start("claude", "--resume $id") }
    }
}
