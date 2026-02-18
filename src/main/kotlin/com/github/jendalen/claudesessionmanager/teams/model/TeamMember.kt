package com.github.jendalen.claudesessionmanager.teams.model

import kotlinx.serialization.Serializable

/**
 * Represents a member of an agent team.
 *
 * Parsed from the "members" array in a team's config.json file.
 * Each member corresponds to a Claude agent instance that gets its own terminal tab.
 */
@Serializable
data class TeamMember(
    val agentId: String,
    val name: String,
    val agentType: String = "agent",
    val model: String = "",
    val color: String = "",
    val backendType: String = "",
    val cwd: String = "",
    val joinedAt: Long = 0,
    val planModeRequired: Boolean = false,
    val tmuxPaneId: String = "",
    val subscriptions: List<String> = emptyList()
)