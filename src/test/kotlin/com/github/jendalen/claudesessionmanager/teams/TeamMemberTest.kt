package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TeamMemberTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize full member with all fields`() {
        val input = """
        {
          "agentId": "worker-1@test",
          "name": "worker-1",
          "agentType": "Security Reviewer",
          "model": "sonnet",
          "color": "#D94A4A",
          "backendType": "in-process",
          "cwd": "/tmp/project",
          "joinedAt": 1706000001000,
          "planModeRequired": false
        }
        """.trimIndent()

        val member = json.decodeFromString<TeamMember>(input)

        assertEquals("worker-1@test", member.agentId)
        assertEquals("worker-1", member.name)
        assertEquals("Security Reviewer", member.agentType)
        assertEquals("sonnet", member.model)
        assertEquals("#D94A4A", member.color)
        assertEquals("in-process", member.backendType)
        assertEquals("/tmp/project", member.cwd)
        assertEquals(1706000001000L, member.joinedAt)
        assertFalse(member.planModeRequired)
    }

    @Test
    fun `deserialize minimal member with defaults`() {
        val input = """
        {
          "agentId": "lead@team",
          "name": "team-lead"
        }
        """.trimIndent()

        val member = json.decodeFromString<TeamMember>(input)

        assertEquals("lead@team", member.agentId)
        assertEquals("team-lead", member.name)
        assertEquals("agent", member.agentType)
        assertEquals("", member.model)
        assertEquals("", member.color)
        assertEquals("", member.backendType)
        assertEquals("", member.cwd)
        assertEquals(0L, member.joinedAt)
        assertFalse(member.planModeRequired)
    }

    @Test
    fun `deserialize with unknown fields is tolerated`() {
        val input = """
        {
          "agentId": "a@b",
          "name": "agent",
          "futureField": "value",
          "extraNumber": 42
        }
        """.trimIndent()

        val member = json.decodeFromString<TeamMember>(input)
        assertEquals("a@b", member.agentId)
        assertEquals("agent", member.name)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = TeamMember(
            agentId = "dev@team",
            name = "developer",
            agentType = "Developer",
            model = "opus",
            color = "#AABBCC",
            backendType = "in-process",
            cwd = "/home/dev/project",
            joinedAt = 1706000000000L,
            planModeRequired = true
        )

        val serialized = json.encodeToString(TeamMember.serializer(), original)
        val deserialized = json.decodeFromString<TeamMember>(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `data class equality`() {
        val a = TeamMember(agentId = "x@y", name = "x")
        val b = TeamMember(agentId = "x@y", name = "x")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class copy`() {
        val original = TeamMember(agentId = "a@b", name = "original", model = "sonnet")
        val copied = original.copy(name = "modified")
        assertEquals("modified", copied.name)
        assertEquals("a@b", copied.agentId)
        assertEquals("sonnet", copied.model)
    }
}
