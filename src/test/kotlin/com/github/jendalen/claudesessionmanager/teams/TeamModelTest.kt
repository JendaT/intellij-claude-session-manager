package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize valid config with all fields`() {
        val input = TestFixtures.loadFixture("valid_config.json")
        val team = json.decodeFromString<TeamModel>(input)

        assertEquals("test-team", team.name)
        assertEquals("Test team for unit tests", team.description)
        assertEquals("lead@test-team", team.leadAgentId)
        assertEquals(1706000000000L, team.createdAt)
        assertEquals(2, team.members.size)
    }

    @Test
    fun `deserialize minimal config with defaults`() {
        val input = TestFixtures.loadFixture("minimal_config.json")
        val team = json.decodeFromString<TeamModel>(input)

        assertEquals("minimal-team", team.name)
        assertEquals("", team.description)
        assertEquals("lead@minimal", team.leadAgentId)
        assertEquals(1, team.members.size)
    }

    @Test
    fun `deserialize config with unknown fields`() {
        val input = TestFixtures.loadFixture("unknown_fields_config.json")
        val team = json.decodeFromString<TeamModel>(input)

        assertEquals("future-team", team.name)
        assertEquals(1, team.members.size)
        assertEquals("team-lead", team.members[0].name)
    }

    @Test
    fun `deserialize empty members list`() {
        val input = TestFixtures.loadFixture("empty_members_config.json")
        val team = json.decodeFromString<TeamModel>(input)

        assertEquals("empty-team", team.name)
        assertTrue(team.members.isEmpty())
    }

    @Test
    fun `deserialize large team config`() {
        val input = TestFixtures.loadFixture("large_team_config.json")
        val team = json.decodeFromString<TeamModel>(input)

        assertEquals("large-team", team.name)
        assertEquals(5, team.members.size)
    }

    @Test
    fun `leadMember returns lead when present`() {
        val lead = TeamMember(agentId = "lead@t", name = "lead")
        val worker = TeamMember(agentId = "w@t", name = "worker")
        val team = TeamModel(name = "t", leadAgentId = "lead@t", members = listOf(lead, worker))

        val result = team.leadMember()
        assertNotNull(result)
        assertEquals("lead@t", result.agentId)
    }

    @Test
    fun `leadMember returns null when lead not in members`() {
        val worker = TeamMember(agentId = "w@t", name = "worker")
        val team = TeamModel(name = "t", leadAgentId = "missing@t", members = listOf(worker))

        assertNull(team.leadMember())
    }

    @Test
    fun `teammates excludes lead`() {
        val lead = TeamMember(agentId = "lead@t", name = "lead")
        val w1 = TeamMember(agentId = "w1@t", name = "w1")
        val w2 = TeamMember(agentId = "w2@t", name = "w2")
        val team = TeamModel(name = "t", leadAgentId = "lead@t", members = listOf(lead, w1, w2))

        val teammates = team.teammates()
        assertEquals(2, teammates.size)
        assertTrue(teammates.all { it.agentId != "lead@t" })
    }

    @Test
    fun `teammates returns all when lead not in members`() {
        val w1 = TeamMember(agentId = "w1@t", name = "w1")
        val w2 = TeamMember(agentId = "w2@t", name = "w2")
        val team = TeamModel(name = "t", leadAgentId = "missing@t", members = listOf(w1, w2))

        assertEquals(2, team.teammates().size)
    }

    @Test
    fun `teamId extracts directory name from unix path`() {
        val team = TeamModel(name = "t")
        team.directoryPath = "/home/user/.claude/teams/abc123"
        assertEquals("abc123", team.teamId())
    }

    @Test
    fun `teamId extracts directory name from windows path`() {
        val team = TeamModel(name = "t")
        team.directoryPath = "C:\\Users\\user\\.claude\\teams\\abc123"
        assertEquals("abc123", team.teamId())
    }

    @Test
    fun `teamId returns empty string when directoryPath is empty`() {
        val team = TeamModel(name = "t")
        assertEquals("", team.teamId())
    }

    @Test
    fun `directoryPath is transient and not serialized`() {
        val team = TeamModel(name = "t", leadAgentId = "l@t")
        team.directoryPath = "/some/path"

        val serialized = json.encodeToString(TeamModel.serializer(), team)
        val deserialized = json.decodeFromString<TeamModel>(serialized)

        assertEquals("t", deserialized.name)
        assertEquals("", deserialized.directoryPath)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = TeamModel(
            name = "round-trip",
            description = "Testing round trip",
            leadAgentId = "lead@rt",
            createdAt = 1706000000000L,
            members = listOf(
                TeamMember(agentId = "lead@rt", name = "lead"),
                TeamMember(agentId = "w@rt", name = "worker", model = "sonnet")
            )
        )

        val serialized = json.encodeToString(TeamModel.serializer(), original)
        val deserialized = json.decodeFromString<TeamModel>(serialized)

        assertEquals(original.name, deserialized.name)
        assertEquals(original.description, deserialized.description)
        assertEquals(original.leadAgentId, deserialized.leadAgentId)
        assertEquals(original.createdAt, deserialized.createdAt)
        assertEquals(original.members, deserialized.members)
    }
}
