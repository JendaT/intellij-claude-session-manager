package com.github.jendalen.claudesessionmanager.util

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionIdValidatorTest {

    @Test
    fun `accepts canonical uuids in either case`() {
        assertTrue(SessionIdValidator.isValid("4e695d61-3fa2-40af-8f76-45977b1cddfa"))
        assertTrue(SessionIdValidator.isValid("4E695D61-3FA2-40AF-8F76-45977B1CDDFA"))
        assertNotNull(SessionIdValidator.parseOrNull("4e695d61-3fa2-40af-8f76-45977b1cddfa"))
    }

    @Test
    fun `rejects everything that is not exactly a uuid`() {
        for (bad in listOf(null, "", " ", "4e695d61", "4e695d61-3fa2-40af-8f76-45977b1cddfa ",
            "4e695d61-3fa2-40af-8f76-45977b1cddfa; ls", "../4e695d61-3fa2-40af-8f76-45977b1cddfa",
            "4e695d61_3fa2_40af_8f76_45977b1cddfa", "g4695d61-3fa2-40af-8f76-45977b1cddfa")) {
            assertFalse(SessionIdValidator.isValid(bad), "should reject: '$bad'")
            assertNull(SessionIdValidator.parseOrNull(bad))
        }
    }
}
