package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchArgsValidatorTest {

    private val channelFlag = "--dangerously-load-development-channels plugin:my-plugin@my-marketplace"

    @Test
    fun `accepts the development channel flag line`() {
        assertEquals(channelFlag, LaunchArgsValidator.normalize(channelFlag))
        assertNull(LaunchArgsValidator.rejectionReason(channelFlag))
    }

    @Test
    fun `accepts server channel syntax and key=value flags`() {
        assertTrue(LaunchArgsValidator.isValid("--dangerously-load-development-channels server:foo"))
        assertTrue(LaunchArgsValidator.isValid("--model=opus --verbose"))
        assertTrue(LaunchArgsValidator.isValid("--add-dir /Users/me/proj,../other"))
    }

    @Test
    fun `blank and null normalise to empty`() {
        assertEquals("", LaunchArgsValidator.normalize(null))
        assertEquals("", LaunchArgsValidator.normalize(""))
        assertEquals("", LaunchArgsValidator.normalize("   "))
    }

    @Test
    fun `trims and collapses runs of spaces`() {
        assertEquals("--a --b", LaunchArgsValidator.normalize("  --a    --b  "))
    }

    @Test
    fun `rejects shell metacharacters`() {
        for (bad in listOf("--a; rm -rf ~", "\$(whoami)", "`id`", "--a | cat", "--a && ls",
            "--a > out", "< in", "--a 'quoted'", "--a \"quoted\"", "\$HOME", "--a\\b")) {
            assertFalse(LaunchArgsValidator.isValid(bad), "should reject: $bad")
            assertNotNull(LaunchArgsValidator.rejectionReason(bad))
        }
    }

    @Test
    fun `rejects newlines and tabs`() {
        assertNull(LaunchArgsValidator.normalize("--a\n--b"))
        assertNull(LaunchArgsValidator.normalize("--a\r\n"))
        assertNull(LaunchArgsValidator.normalize("--a\t--b"))
        assertEquals("Must be a single line", LaunchArgsValidator.rejectionReason("--a\n--b"))
    }

    @Test
    fun `rejects session selection flags in every spelling`() {
        for (bad in listOf("--resume x", "-r x", "--continue", "-c", "--session-id x",
            "--resume=abc", "--verbose --resume abc")) {
            assertNull(LaunchArgsValidator.normalize(bad), "should reject: $bad")
        }
        // Prefix matches are not the reserved flag.
        assertTrue(LaunchArgsValidator.isValid("--resume-something"))
    }
}
