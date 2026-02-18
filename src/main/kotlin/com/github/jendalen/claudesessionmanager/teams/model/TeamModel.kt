package com.github.jendalen.claudesessionmanager.teams.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents an agent team as stored in ~/.claude/teams/{team-id}/config.json.
 *
 * A team consists of a lead agent and one or more teammate agents.
 * The plugin watches for team directories and parses config.json to discover members.
 */
@Serializable
data class TeamModel(
    val name: String,
    val description: String = "",
    val leadAgentId: String = "",
    val leadSessionId: String = "",
    val createdAt: Long = 0,
    val members: List<TeamMember> = emptyList()
) {
    /**
     * Filesystem path to the team directory. Not serialized from config.json,
     * set by the parser after reading.
     */
    @Transient
    var directoryPath: String = ""

    /**
     * Returns the team directory name (last path segment), which serves as the team ID.
     */
    fun teamId(): String {
        return directoryPath.substringAfterLast('/').substringAfterLast('\\')
    }

    /**
     * Returns the lead member, or null if not found among members.
     */
    fun leadMember(): TeamMember? {
        return members.find { it.agentId == leadAgentId }
    }

    /**
     * Returns all non-lead members.
     */
    fun teammates(): List<TeamMember> {
        return members.filter { it.agentId != leadAgentId }
    }
}