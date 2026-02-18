package com.github.jendalen.claudesessionmanager.teams.services

import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import com.github.jendalen.claudesessionmanager.teams.util.AgentIdValidator
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Parses Claude Code team filesystem artifacts into data models.
 *
 * Team data is stored under ~/.claude/teams/{team-id}/:
 * - config.json: team configuration and member list
 * - inboxes/{agent-id}.json: messages for each agent
 *
 * Task data may be stored under ~/.claude/tasks/{team-id}/ as individual JSON files.
 */
@Service(Service.Level.PROJECT)
class TeamStateParser(private val project: Project) {

    private val logger = Logger.getInstance(TeamStateParser::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Returns the teams root directory (~/.claude/teams/).
     */
    fun getTeamsDirectory(): File {
        return File(ClaudePathResolver.getClaudeHome(), "teams")
    }

    /**
     * Discovers all team directories under ~/.claude/teams/.
     * Returns directory names (team IDs) that contain a config.json.
     */
    fun discoverTeamIds(): List<String> {
        val teamsDir = getTeamsDirectory()
        if (!teamsDir.exists() || !teamsDir.isDirectory) {
            return emptyList()
        }

        return teamsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "config.json").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Parses a team's config.json into a TeamModel.
     * Returns null if the file is missing or malformed.
     * Validates agent IDs to prevent path traversal.
     */
    fun parseTeamConfig(teamId: String): TeamModel? {
        val configFile = File(getTeamsDirectory(), "$teamId/config.json")
        if (!configFile.exists() || !configFile.isFile) {
            logger.debug("Team config not found: ${configFile.absolutePath}")
            return null
        }

        return try {
            val content = configFile.readText()
            val team = json.decodeFromString<TeamModel>(sanitize(content))
            team.directoryPath = configFile.parentFile.absolutePath

            // Validate agent IDs to prevent path traversal in inbox file paths
            val validMembers = team.members.filter { member ->
                if (AgentIdValidator.isValid(member.agentId)) {
                    true
                } else {
                    logger.warn("Rejecting member with invalid agentId '${member.agentId}' in team '$teamId'")
                    false
                }
            }

            if (validMembers.size != team.members.size) {
                team.copy(members = validMembers).also {
                    it.directoryPath = team.directoryPath
                }
            } else {
                team
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse team config for '$teamId': ${e.message}", e)
            null
        }
    }

    /**
     * Parses all teams from the teams directory.
     */
    fun parseAllTeams(): List<TeamModel> {
        return discoverTeamIds().mapNotNull { parseTeamConfig(it) }
    }

    /**
     * Parses inbox messages for a specific agent in a team.
     * Messages are stored in ~/.claude/teams/{team-id}/inboxes/{agent-id}.json.
     * Agent ID is validated before use in file path.
     */
    fun parseInboxMessages(teamId: String, agentId: String): List<InboxMessage> {
        if (!AgentIdValidator.isValid(agentId)) {
            logger.warn("Rejecting inbox parse for invalid agentId: '$agentId'")
            return emptyList()
        }

        val inboxFile = File(getTeamsDirectory(), "$teamId/inboxes/$agentId.json")
        if (!inboxFile.exists() || !inboxFile.isFile) {
            return emptyList()
        }

        return try {
            val content = inboxFile.readText()
            json.decodeFromString<List<InboxMessage>>(sanitize(content))
        } catch (e: Exception) {
            logger.warn("Failed to parse inbox for agent '$agentId' in team '$teamId': ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parses all inbox messages for all agents in a team.
     * Returns a map of agentId -> messages.
     */
    fun parseAllInboxes(teamId: String): Map<String, List<InboxMessage>> {
        val inboxesDir = File(getTeamsDirectory(), "$teamId/inboxes")
        if (!inboxesDir.exists() || !inboxesDir.isDirectory) {
            return emptyMap()
        }

        val result = mutableMapOf<String, List<InboxMessage>>()
        inboxesDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.forEach { file ->
                val agentId = file.nameWithoutExtension
                if (!AgentIdValidator.isValid(agentId)) {
                    logger.warn("Skipping inbox file with invalid agent ID: '${file.name}'")
                    return@forEach
                }
                try {
                    val content = file.readText()
                    val messages = json.decodeFromString<List<InboxMessage>>(sanitize(content))
                    result[agentId] = messages
                } catch (e: Exception) {
                    logger.warn("Failed to parse inbox file '${file.name}' in team '$teamId': ${e.message}", e)
                }
            }

        return result
    }

    /**
     * Parses task files from ~/.claude/tasks/{team-id}/ directory.
     * Each file is a JSON-encoded TeamTask.
     */
    fun parseTasks(teamId: String): List<TeamTask> {
        val tasksDir = File(ClaudePathResolver.getClaudeHome(), "tasks/$teamId")
        if (!tasksDir.exists() || !tasksDir.isDirectory) {
            return emptyList()
        }

        return tasksDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val content = file.readText()
                    json.decodeFromString<TeamTask>(sanitize(content))
                } catch (e: Exception) {
                    logger.warn("Failed to parse task file '${file.name}' in team '$teamId': ${e.message}", e)
                    null
                }
            }
            ?: emptyList()
    }

    /**
     * Sanitizes JSON content to prevent terminal escape sequence injection.
     * Strips ANSI escape codes and other control characters that could affect
     * terminal rendering when displayed in the UI.
     */
    private fun sanitize(content: String): String {
        return content.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
    }

    companion object {
        fun getInstance(project: Project): TeamStateParser {
            return project.getService(TeamStateParser::class.java)
        }
    }
}
