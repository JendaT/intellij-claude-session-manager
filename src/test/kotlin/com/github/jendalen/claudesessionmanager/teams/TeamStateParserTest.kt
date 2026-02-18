package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for TeamStateParser's parsing and filesystem discovery logic.
 *
 * Since TeamStateParser is an IntelliJ project service, we test its core logic
 * (JSON parsing + directory scanning) directly using the same Json configuration
 * and equivalent filesystem operations.
 */
class TeamStateParserTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var teamsDir: File
    private lateinit var tasksDir: File

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @BeforeEach
    fun setUp() {
        teamsDir = tempDir.resolve("teams").toFile()
        teamsDir.mkdirs()
        tasksDir = tempDir.resolve("tasks").toFile()
        tasksDir.mkdirs()
    }

    // --- Team Config Parsing ---

    @Test
    fun `parse valid team config`() {
        val configJson = TestFixtures.loadFixture("valid_config.json")
        val team = json.decodeFromString<TeamModel>(configJson)

        assertEquals("test-team", team.name)
        assertEquals("Test team for unit tests", team.description)
        assertEquals("lead@test-team", team.leadAgentId)
        assertEquals(1706000000000L, team.createdAt)
        assertEquals(2, team.members.size)
        assertEquals("team-lead", team.members[0].name)
        assertEquals("worker-1", team.members[1].name)
    }

    @Test
    fun `parse config with unknown fields does not fail`() {
        val configJson = TestFixtures.loadFixture("unknown_fields_config.json")
        val team = json.decodeFromString<TeamModel>(configJson)
        assertEquals("future-team", team.name)
    }

    @Test
    fun `parse malformed config returns null on catch`() {
        val malformedJson = TestFixtures.loadFixture("malformed_config.json")
        val result = try {
            json.decodeFromString<TeamModel>(malformedJson)
        } catch (e: Exception) {
            null
        }
        assertNull(result)
    }

    @Test
    fun `parse empty members config`() {
        val configJson = TestFixtures.loadFixture("empty_members_config.json")
        val team = json.decodeFromString<TeamModel>(configJson)
        assertTrue(team.members.isEmpty())
    }

    // --- Directory Discovery ---

    @Test
    fun `discover team ids finds directories with config json`() {
        // Create valid team directories
        createTeamWithConfig("team-1")
        createTeamWithConfig("team-2")

        // Create directory without config.json (should be skipped)
        File(teamsDir, "no-config").mkdirs()

        // Create a file (not a directory, should be skipped)
        File(teamsDir, "not-a-dir.txt").writeText("hello")

        val teamIds = discoverTeamIds()
        assertEquals(2, teamIds.size)
        assertTrue("team-1" in teamIds)
        assertTrue("team-2" in teamIds)
    }

    @Test
    fun `discover team ids returns empty for missing teams directory`() {
        teamsDir.deleteRecursively()
        val teamIds = discoverTeamIds()
        assertTrue(teamIds.isEmpty())
    }

    @Test
    fun `discover team ids returns empty for empty teams directory`() {
        val teamIds = discoverTeamIds()
        assertTrue(teamIds.isEmpty())
    }

    // --- Parse Team Config from Filesystem ---

    @Test
    fun `parse team config from filesystem`() {
        val teamDir = createTeamWithConfig("abc123")
        val team = parseTeamConfig("abc123")

        assertNotNull(team)
        assertEquals("test-team", team.name)
        assertEquals(teamDir.absolutePath, team.directoryPath)
    }

    @Test
    fun `parse team config sets directoryPath`() {
        createTeamWithConfig("my-team")
        val team = parseTeamConfig("my-team")

        assertNotNull(team)
        assertTrue(team.directoryPath.endsWith("my-team"))
    }

    @Test
    fun `parse team config returns null for missing directory`() {
        val team = parseTeamConfig("nonexistent")
        assertNull(team)
    }

    @Test
    fun `parse team config returns null for malformed json`() {
        val teamDir = File(teamsDir, "broken")
        teamDir.mkdirs()
        File(teamDir, "config.json").writeText("{ not valid json!!!")

        val team = parseTeamConfig("broken")
        assertNull(team)
    }

    @Test
    fun `parse team config returns null for empty file`() {
        val teamDir = File(teamsDir, "empty")
        teamDir.mkdirs()
        File(teamDir, "config.json").writeText("")

        val team = parseTeamConfig("empty")
        assertNull(team)
    }

    // --- Inbox Parsing ---

    @Test
    fun `parse inbox messages from file`() {
        val teamDir = File(teamsDir, "team-1")
        teamDir.mkdirs()
        val inboxesDir = File(teamDir, "inboxes")
        inboxesDir.mkdirs()

        val messages = listOf(
            TestFixtures.loadFixture("valid_inbox_message.json").let { "[$it]" }
        )
        File(inboxesDir, "worker-1.json").writeText(
            """[
                ${TestFixtures.loadFixture("valid_inbox_message.json")},
                ${TestFixtures.loadFixture("broadcast_message.json")}
            ]"""
        )

        val parsed = parseInboxMessages("team-1", "worker-1")
        assertEquals(2, parsed.size)
        assertEquals("message", parsed[0].type)
        assertEquals("team-lead", parsed[0].from)
        assertEquals("broadcast", parsed[1].type)
    }

    @Test
    fun `parse inbox returns empty for missing inbox file`() {
        val teamDir = File(teamsDir, "team-1")
        teamDir.mkdirs()

        val messages = parseInboxMessages("team-1", "nonexistent-agent")
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `parse inbox returns empty for malformed json`() {
        val teamDir = File(teamsDir, "team-1")
        teamDir.mkdirs()
        val inboxesDir = File(teamDir, "inboxes")
        inboxesDir.mkdirs()
        File(inboxesDir, "agent.json").writeText("{ broken json")

        val messages = parseInboxMessages("team-1", "agent")
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `parse all inboxes discovers all agent inbox files`() {
        val teamDir = File(teamsDir, "team-1")
        teamDir.mkdirs()
        val inboxesDir = File(teamDir, "inboxes")
        inboxesDir.mkdirs()

        File(inboxesDir, "agent-a.json").writeText(
            "[${TestFixtures.loadFixture("valid_inbox_message.json")}]"
        )
        File(inboxesDir, "agent-b.json").writeText(
            "[${TestFixtures.loadFixture("broadcast_message.json")}]"
        )
        // Non-json file should be skipped
        File(inboxesDir, "readme.txt").writeText("ignore me")

        val allInboxes = parseAllInboxes("team-1")
        assertEquals(2, allInboxes.size)
        assertTrue("agent-a" in allInboxes)
        assertTrue("agent-b" in allInboxes)
    }

    @Test
    fun `parse all inboxes returns empty for missing inboxes dir`() {
        File(teamsDir, "team-1").mkdirs()
        val result = parseAllInboxes("team-1")
        assertTrue(result.isEmpty())
    }

    // --- Task Parsing ---

    @Test
    fun `parse tasks from individual files`() {
        val teamTasksDir = File(tasksDir, "team-1")
        teamTasksDir.mkdirs()

        File(teamTasksDir, "task-1.json").writeText("""
            {"id": "1", "subject": "First task", "status": "pending"}
        """.trimIndent())
        File(teamTasksDir, "task-2.json").writeText("""
            {"id": "2", "subject": "Second task", "status": "completed", "completedAt": 1706000500000}
        """.trimIndent())
        // Non-json file should be skipped
        File(teamTasksDir, "notes.txt").writeText("not a task")

        val tasks = parseTasks("team-1")
        assertEquals(2, tasks.size)
    }

    @Test
    fun `parse tasks returns empty for missing tasks dir`() {
        val tasks = parseTasks("nonexistent")
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `parse tasks skips malformed files`() {
        val teamTasksDir = File(tasksDir, "team-1")
        teamTasksDir.mkdirs()

        File(teamTasksDir, "good.json").writeText("""{"id": "1"}""")
        File(teamTasksDir, "bad.json").writeText("not json at all")

        val tasks = parseTasks("team-1")
        assertEquals(1, tasks.size)
        assertEquals("1", tasks[0].id)
    }

    // --- Sanitization ---

    @Test
    fun `parse config with ANSI escape sequences`() {
        val esc = '\u001B'
        val configWithAnsi = """
        {
          "name": "${esc}[31mred-team${esc}[0m",
          "leadAgentId": "lead@t",
          "createdAt": 1706000000000,
          "members": []
        }
        """.trimIndent()

        val sanitized = sanitize(configWithAnsi)
        val team = json.decodeFromString<TeamModel>(sanitized)
        assertEquals("red-team", team.name)
    }

    @Test
    fun `sanitize strips control characters`() {
        val dirty = "hello\u0000world\u0007test\u001B[31mcolor\u001B[0m"
        val clean = sanitize(dirty)
        assertEquals("helloworldtestcolor", clean)
    }

    @Test
    fun `sanitize preserves normal whitespace`() {
        val input = "hello\tworld\nline two\r\n"
        val result = sanitize(input)
        assertEquals("hello\tworld\nline two\r\n", result)
    }

    // --- Helper methods that replicate TeamStateParser logic ---

    private fun discoverTeamIds(): List<String> {
        if (!teamsDir.exists() || !teamsDir.isDirectory) return emptyList()
        return teamsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "config.json").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    private fun parseTeamConfig(teamId: String): TeamModel? {
        val configFile = File(teamsDir, "$teamId/config.json")
        if (!configFile.exists() || !configFile.isFile) return null
        return try {
            val content = configFile.readText()
            val team = json.decodeFromString<TeamModel>(sanitize(content))
            team.directoryPath = configFile.parentFile.absolutePath
            team
        } catch (e: Exception) {
            null
        }
    }

    private fun parseInboxMessages(teamId: String, agentId: String): List<InboxMessage> {
        val inboxFile = File(teamsDir, "$teamId/inboxes/$agentId.json")
        if (!inboxFile.exists() || !inboxFile.isFile) return emptyList()
        return try {
            json.decodeFromString<List<InboxMessage>>(sanitize(inboxFile.readText()))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAllInboxes(teamId: String): Map<String, List<InboxMessage>> {
        val inboxesDir = File(teamsDir, "$teamId/inboxes")
        if (!inboxesDir.exists() || !inboxesDir.isDirectory) return emptyMap()
        val result = mutableMapOf<String, List<InboxMessage>>()
        inboxesDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.forEach { file ->
                val agentId = file.nameWithoutExtension
                try {
                    result[agentId] = json.decodeFromString<List<InboxMessage>>(sanitize(file.readText()))
                } catch (_: Exception) {}
            }
        return result
    }

    private fun parseTasks(teamId: String): List<TeamTask> {
        val tasksDirForTeam = File(tasksDir, teamId)
        if (!tasksDirForTeam.exists() || !tasksDirForTeam.isDirectory) return emptyList()
        return tasksDirForTeam.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<TeamTask>(sanitize(file.readText()))
                } catch (_: Exception) { null }
            }
            ?: emptyList()
    }

    private fun sanitize(content: String): String {
        return content.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
    }

    private fun createTeamWithConfig(teamId: String): File {
        val teamDir = File(teamsDir, teamId)
        teamDir.mkdirs()
        File(teamDir, "config.json").writeText(TestFixtures.loadFixture("valid_config.json"))
        return teamDir
    }
}
