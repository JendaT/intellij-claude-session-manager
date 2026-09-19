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
    fun `rejects invalid session ids and invalid args`() {
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", "not-a-uuid") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "", "$id; rm -rf ~") }
        assertThrows<IllegalArgumentException> { LaunchCommand.resume("claude", "--a; ls", id) }
        assertThrows<IllegalArgumentException> { LaunchCommand.start("claude", "--resume $id") }
    }
}
