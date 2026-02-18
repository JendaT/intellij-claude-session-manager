package com.github.jendalen.claudesessionmanager.teams.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status of a task in the team task list.
 * Claude Code writes lowercase values: "pending", "in_progress", "completed", "blocked".
 */
@Serializable
enum class TaskStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("blocked") BLOCKED
}

/**
 * Represents a task in a team's task list.
 *
 * Tasks are tracked within the team's state and can be assigned to specific members.
 * Dependencies between tasks determine execution order.
 *
 * Claude Code uses "subject", "owner", "blockedBy", "blocks" in its JSON format.
 * We use @SerialName to map while keeping idiomatic Kotlin property names.
 */
@Serializable
data class TeamTask(
    val id: String,
    @SerialName("subject") val description: String = "",
    @SerialName("owner") val assignee: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    @SerialName("blockedBy") val dependencies: List<String> = emptyList(),
    val blocks: List<String> = emptyList(),
    val activeForm: String = "",
    val createdAt: Long = 0,
    val completedAt: Long? = null
) {
    /**
     * Returns true if all dependencies are in the provided set of completed task IDs.
     */
    fun isUnblocked(completedTaskIds: Set<String>): Boolean {
        return dependencies.all { it in completedTaskIds }
    }

    /**
     * Returns a short display label combining ID and first line of description.
     */
    fun displayLabel(): String {
        val firstLine = description.lineSequence().firstOrNull()?.take(60) ?: ""
        return "#$id $firstLine"
    }
}
