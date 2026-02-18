package com.github.jendalen.claudesessionmanager.teams.model

/**
 * Sealed class representing events in the team lifecycle.
 *
 * Events are emitted by the file watcher when team state changes on disk,
 * and consumed by the dashboard and notification system.
 */
sealed class TeamEvent {
    /** Timestamp when the event was detected. */
    abstract val timestamp: Long

    data class TeamCreated(
        val team: TeamModel,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class TeamUpdated(
        val team: TeamModel,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class TeamDeleted(
        val teamId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class MemberAdded(
        val teamId: String,
        val member: TeamMember,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class MemberRemoved(
        val teamId: String,
        val agentId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class TaskChanged(
        val teamId: String,
        val task: TeamTask,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()

    data class MessageReceived(
        val teamId: String,
        val message: InboxMessage,
        override val timestamp: Long = System.currentTimeMillis()
    ) : TeamEvent()
}
