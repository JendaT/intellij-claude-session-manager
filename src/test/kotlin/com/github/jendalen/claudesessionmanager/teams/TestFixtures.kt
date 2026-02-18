package com.github.jendalen.claudesessionmanager.teams

import java.io.File
import java.nio.file.Path

/**
 * Utility for loading test fixture files and creating temp directory structures
 * that mimic the ~/.claude/teams/ layout.
 */
object TestFixtures {

    private const val FIXTURES_DIR = "/fixtures/teams"

    /**
     * Loads a fixture file from test resources.
     * Returns the content as a String.
     */
    fun loadFixture(filename: String): String {
        val resource = TestFixtures::class.java.getResourceAsStream("$FIXTURES_DIR/$filename")
            ?: throw IllegalArgumentException("Fixture not found: $filename")
        return resource.bufferedReader().readText()
    }

    /**
     * Creates a mock ~/.claude/teams/{teamId} directory structure with config.json.
     *
     * @param baseDir the temp directory acting as ~/.claude
     * @param teamId the team directory name (e.g., "abc123")
     * @param configJson the content for config.json
     * @return the team directory
     */
    fun createTeamDir(baseDir: Path, teamId: String, configJson: String): File {
        val teamDir = baseDir.resolve("teams").resolve(teamId).toFile()
        teamDir.mkdirs()
        File(teamDir, "config.json").writeText(configJson)
        return teamDir
    }

    /**
     * Creates a mock inbox directory with message files for a team member.
     *
     * @param teamDir the team directory
     * @param agentName the agent's name (used as directory name)
     * @param messages list of pairs (filename, json content) for each message file
     * @return the inbox directory
     */
    fun createInbox(teamDir: File, agentName: String, messages: List<Pair<String, String>>): File {
        val inboxDir = File(teamDir, "inbox/$agentName")
        inboxDir.mkdirs()
        for ((filename, content) in messages) {
            File(inboxDir, filename).writeText(content)
        }
        return inboxDir
    }

    /**
     * Creates a tasks.json file in the team directory.
     */
    fun createTasksFile(teamDir: File, tasksJson: String): File {
        val tasksFile = File(teamDir, "tasks.json")
        tasksFile.writeText(tasksJson)
        return tasksFile
    }

    /**
     * Creates a complete team directory structure with config, inbox, and tasks.
     *
     * @param baseDir the temp directory acting as ~/.claude
     * @param teamId the team directory name
     * @return the team directory
     */
    fun createFullTeamStructure(baseDir: Path, teamId: String): File {
        val teamDir = createTeamDir(baseDir, teamId, loadFixture("valid_config.json"))
        createInbox(
            teamDir, "worker-1", listOf(
                "msg-001.json" to loadFixture("valid_inbox_message.json"),
                "msg-002.json" to loadFixture("broadcast_message.json")
            )
        )
        createTasksFile(teamDir, loadFixture("valid_tasks.json"))
        return teamDir
    }

    // ---- Inline JSON builders for programmatic test data ----

    fun buildConfigJson(
        name: String = "test-team",
        description: String? = null,
        leadAgentId: String = "lead@$name",
        createdAt: Long = 1706000000000L,
        members: List<String> = listOf(buildMemberJson(agentId = leadAgentId, name = "team-lead", agentType = "team-lead"))
    ): String {
        val descField = if (description != null) "\"description\": \"$description\"," else ""
        val membersStr = members.joinToString(",\n    ")
        return """
        {
          "name": "$name",
          $descField
          "leadAgentId": "$leadAgentId",
          "createdAt": $createdAt,
          "members": [
            $membersStr
          ]
        }
        """.trimIndent()
    }

    fun buildMemberJson(
        agentId: String = "agent@team",
        name: String = "agent",
        agentType: String = "Developer",
        model: String? = null,
        color: String? = null,
        planModeRequired: Boolean? = null,
        joinedAt: Long = 1706000000000L,
        cwd: String? = null,
        backendType: String = "in-process"
    ): String {
        val parts = mutableListOf<String>()
        parts.add("\"agentId\": \"$agentId\"")
        parts.add("\"name\": \"$name\"")
        parts.add("\"agentType\": \"$agentType\"")
        if (model != null) parts.add("\"model\": \"$model\"")
        if (color != null) parts.add("\"color\": \"$color\"")
        if (planModeRequired != null) parts.add("\"planModeRequired\": $planModeRequired")
        parts.add("\"joinedAt\": $joinedAt")
        if (cwd != null) parts.add("\"cwd\": \"$cwd\"")
        parts.add("\"backendType\": \"$backendType\"")
        return "{ ${parts.joinToString(", ")} }"
    }

    fun buildInboxMessageJson(
        type: String = "message",
        sender: String = "team-lead",
        recipient: String = "worker-1",
        content: String = "Hello",
        timestamp: Long = 1706000100000L,
        summary: String = ""
    ): String {
        val parts = mutableListOf<String>()
        parts.add("\"type\": \"$type\"")
        parts.add("\"sender\": \"$sender\"")
        parts.add("\"recipient\": \"$recipient\"")
        parts.add("\"content\": \"$content\"")
        parts.add("\"timestamp\": $timestamp")
        parts.add("\"summary\": \"$summary\"")
        return "{ ${parts.joinToString(", ")} }"
    }
}