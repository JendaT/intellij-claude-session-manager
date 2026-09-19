package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionNameValidatorTest {

    @Test
    fun `accepts ordinary tab names and quotes them`() {
        for (ok in listOf("Local (2)", "payments-bug", "Shop worktree1: BB-24417", "review #3 @home \"quoted\" [x] {y} ~ ! ? * = , . /")) {
            assertTrue(SessionNameValidator.isValid(ok), "should accept: $ok")
            assertEquals("'$ok'", SessionNameValidator.quoted(ok))
        }
        assertEquals("'x'", SessionNameValidator.quoted("  x  "))
    }

    @Test
    fun `rejects quotes, backslashes, control characters, blank and overlong names`() {
        for (bad in listOf("it's", "a\\b", "a\nb", "a\rb", "a\tb", "", "   ", "x".repeat(SessionNameValidator.MAX_LENGTH + 1),
            // Typed unquoted as /rename <name>; at a shell prompt these would chain or run commands.
            "a; rm -rf ~", "a | b", "R&D", "\$HOME", "a > b", "a < b", "`id`")) {
            assertFalse(SessionNameValidator.isValid(bad), "should reject: ${bad.take(10)}")
            assertNotNull(SessionNameValidator.rejectionReason(bad))
        }
        assertTrue(SessionNameValidator.isValid("x".repeat(SessionNameValidator.MAX_LENGTH)))
    }
}
