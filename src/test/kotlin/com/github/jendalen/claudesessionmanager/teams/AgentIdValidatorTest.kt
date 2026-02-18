package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.util.AgentIdValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentIdValidatorTest {

    // --- isValid ---

    @Test
    fun `valid simple agent ID`() {
        assertTrue(AgentIdValidator.isValid("worker-1"))
    }

    @Test
    fun `valid agent ID with @ sign`() {
        assertTrue(AgentIdValidator.isValid("lead@test-team"))
    }

    @Test
    fun `valid agent ID with dots`() {
        assertTrue(AgentIdValidator.isValid("agent.1.test"))
    }

    @Test
    fun `valid agent ID with underscores`() {
        assertTrue(AgentIdValidator.isValid("agent_name"))
    }

    @Test
    fun `valid agent ID with mixed characters`() {
        assertTrue(AgentIdValidator.isValid("dev-1@my.team_v2"))
    }

    @Test
    fun `null agent ID is invalid`() {
        assertFalse(AgentIdValidator.isValid(null))
    }

    @Test
    fun `empty agent ID is invalid`() {
        assertFalse(AgentIdValidator.isValid(""))
    }

    @Test
    fun `blank agent ID is invalid`() {
        assertFalse(AgentIdValidator.isValid("   "))
    }

    @Test
    fun `agent ID with path separator is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent/../../etc/passwd"))
    }

    @Test
    fun `agent ID with backslash is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent\\name"))
    }

    @Test
    fun `agent ID with double dots is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent..name"))
    }

    @Test
    fun `agent ID with shell metacharacters is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent;rm -rf /"))
        assertFalse(AgentIdValidator.isValid("agent|cat /etc/passwd"))
        assertFalse(AgentIdValidator.isValid("agent`id`"))
        assertFalse(AgentIdValidator.isValid("agent\$(whoami)"))
    }

    @Test
    fun `agent ID with spaces is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent name"))
    }

    @Test
    fun `agent ID with ANSI escape is invalid`() {
        assertFalse(AgentIdValidator.isValid("agent\u001B[31m"))
    }

    @Test
    fun `agent ID exceeding max length is invalid`() {
        val longId = "a".repeat(257)
        assertFalse(AgentIdValidator.isValid(longId))
    }

    @Test
    fun `agent ID at max length is valid`() {
        val exactId = "a".repeat(256)
        assertTrue(AgentIdValidator.isValid(exactId))
    }

    // --- sanitizeForDisplay ---

    @Test
    fun `sanitize removes special characters`() {
        assertEquals("agentname", AgentIdValidator.sanitizeForDisplay("agent;name"))
    }

    @Test
    fun `sanitize preserves valid characters`() {
        assertEquals("dev-1@team.v2", AgentIdValidator.sanitizeForDisplay("dev-1@team.v2"))
    }

    @Test
    fun `sanitize strips path separators but keeps dots`() {
        val sanitized = AgentIdValidator.sanitizeForDisplay("../../etc/passwd")
        assertFalse(sanitized.contains("/"))
        // Dots are preserved since they're in the allowed set
        assertTrue(sanitized.contains("etc"))
        assertTrue(sanitized.contains("passwd"))
    }

    @Test
    fun `sanitize truncates to max length`() {
        val longInput = "a".repeat(300)
        val sanitized = AgentIdValidator.sanitizeForDisplay(longInput)
        assertEquals(256, sanitized.length)
    }

    @Test
    fun `sanitize preserves spaces`() {
        assertEquals("agent name", AgentIdValidator.sanitizeForDisplay("agent name"))
    }

    @Test
    fun `sanitize strips ANSI escape sequences`() {
        val withAnsi = "agent\u001B[31mname"
        val sanitized = AgentIdValidator.sanitizeForDisplay(withAnsi)
        assertFalse(sanitized.contains("\u001B"))
    }
}
