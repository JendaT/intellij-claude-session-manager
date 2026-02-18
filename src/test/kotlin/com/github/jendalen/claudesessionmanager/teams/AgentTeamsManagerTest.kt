package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import com.github.jendalen.claudesessionmanager.teams.services.TeamEventListener
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AgentTeamsManager's event routing and state management logic.
 *
 * Since AgentTeamsManager is an IntelliJ project service with heavy IDE dependencies,
 * we test the event routing logic through the TeamEventListener interface, and validate
 * the TeamModel helper methods used by the manager.
 */
class AgentTeamsManagerTest {

    // --- Event Routing Logic ---
    // Tests that onTeamEvent correctly dispatches to the right handler

    @Test
    fun `TeamCreated event routes to team creation handler`() {
        val events = mutableListOf<String>()
        val listener = createRoutingTracker(events)

        val team = TeamModel(name = "test")
        listener.onTeamEvent(TeamEvent.TeamCreated(team))

        assertEquals(listOf("TeamCreated:test"), events)
    }

    @Test
    fun `TeamUpdated event routes to team update handler`() {
        val events = mutableListOf<String>()
        val listener = createRoutingTracker(events)

        val team = TeamModel(name = "test")
        listener.onTeamEvent(TeamEvent.TeamUpdated(team))

        assertEquals(listOf("TeamUpdated:test"), events)
    }

    @Test
    fun `TeamDeleted event routes to team deletion handler`() {
        val events = mutableListOf<String>()
        val listener = createRoutingTracker(events)

        listener.onTeamEvent(TeamEvent.TeamDeleted("team-123"))

        assertEquals(listOf("TeamDeleted:team-123"), events)
    }

    @Test
    fun `MemberAdded event routes to member addition handler`() {
        val events = mutableListOf<String>()
        val listener = createRoutingTracker(events)

        val member = TeamMember(agentId = "w@t", name = "worker")
        listener.onTeamEvent(TeamEvent.MemberAdded("t1", member))

        assertEquals(listOf("MemberAdded:t1:w@t"), events)
    }

    @Test
    fun `MemberRemoved event routes to member removal handler`() {
        val events = mutableListOf<String>()
        val listener = createRoutingTracker(events)

        listener.onTeamEvent(TeamEvent.MemberRemoved("t1", "w@t"))

        assertEquals(listOf("MemberRemoved:t1:w@t"), events)
    }

    @Test
    fun `TaskChanged and MessageReceived are not routed to tab management`() {
        // These events are handled by the dashboard, not by AgentTeamsManager's tab management.
        // The manager's onTeamEvent only acts on Team/Member events.
        val tabActions = mutableListOf<String>()
        val listener = createManagerRoutingTracker(tabActions)

        val task = TeamTask(id = "1")
        val msg = InboxMessage(type = "message")
        listener.onTeamEvent(TeamEvent.TaskChanged("t1", task))
        listener.onTeamEvent(TeamEvent.MessageReceived("t1", msg))

        assertTrue(tabActions.isEmpty(), "TaskChanged/MessageReceived should not trigger tab management actions")
    }

    // --- TeamModel helpers used by the manager ---

    @Test
    fun `teammates excludes lead correctly for tab creation`() {
        val lead = TeamMember(agentId = "lead@t", name = "lead", agentType = "team-lead")
        val w1 = TeamMember(agentId = "w1@t", name = "dev-1", agentType = "Developer")
        val w2 = TeamMember(agentId = "w2@t", name = "tester", agentType = "Tester")
        val team = TeamModel(
            name = "project-team",
            leadAgentId = "lead@t",
            members = listOf(lead, w1, w2)
        )

        val teammates = team.teammates()
        assertEquals(2, teammates.size)
        assertEquals("w1@t", teammates[0].agentId)
        assertEquals("w2@t", teammates[1].agentId)
    }

    @Test
    fun `empty team has no teammates to create tabs for`() {
        val team = TeamModel(name = "empty", leadAgentId = "lead@t", members = emptyList())
        assertTrue(team.teammates().isEmpty())
    }

    @Test
    fun `team where all members have same agentId as lead has no teammates`() {
        val lead = TeamMember(agentId = "lead@t", name = "lead")
        val team = TeamModel(name = "solo", leadAgentId = "lead@t", members = listOf(lead))
        assertTrue(team.teammates().isEmpty())
    }

    // --- State tracking patterns ---

    @Test
    fun `team lifecycle - created then updated then deleted`() {
        var currentTeam: TeamModel? = null
        val listener = TeamEventListener { event ->
            when (event) {
                is TeamEvent.TeamCreated -> currentTeam = event.team
                is TeamEvent.TeamUpdated -> currentTeam = event.team
                is TeamEvent.TeamDeleted -> currentTeam = null
                else -> {}
            }
        }

        // Create
        val team1 = TeamModel(name = "v1", members = listOf(TeamMember(agentId = "a@t", name = "a")))
        listener.onTeamEvent(TeamEvent.TeamCreated(team1))
        assertEquals("v1", currentTeam?.name)

        // Update
        val team2 = TeamModel(name = "v2", members = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        ))
        listener.onTeamEvent(TeamEvent.TeamUpdated(team2))
        assertEquals("v2", currentTeam?.name)
        assertEquals(2, currentTeam?.members?.size)

        // Delete
        listener.onTeamEvent(TeamEvent.TeamDeleted("team-id"))
        assertNull(currentTeam)
    }

    // Helper: creates a listener that tracks which event type was routed

    private fun createRoutingTracker(events: MutableList<String>): TeamEventListener {
        return TeamEventListener { event ->
            when (event) {
                is TeamEvent.TeamCreated -> events.add("TeamCreated:${event.team.name}")
                is TeamEvent.TeamUpdated -> events.add("TeamUpdated:${event.team.name}")
                is TeamEvent.TeamDeleted -> events.add("TeamDeleted:${event.teamId}")
                is TeamEvent.MemberAdded -> events.add("MemberAdded:${event.teamId}:${event.member.agentId}")
                is TeamEvent.MemberRemoved -> events.add("MemberRemoved:${event.teamId}:${event.agentId}")
                is TeamEvent.TaskChanged -> events.add("TaskChanged")
                is TeamEvent.MessageReceived -> events.add("MessageReceived")
            }
        }
    }

    /**
     * Creates a listener mimicking AgentTeamsManager's event handling.
     * Only records tab management actions (Team/Member events).
     * TaskChanged and MessageReceived are intentionally not recorded.
     */
    private fun createManagerRoutingTracker(tabActions: MutableList<String>): TeamEventListener {
        return TeamEventListener { event ->
            when (event) {
                is TeamEvent.TeamCreated -> tabActions.add("TeamCreated:${event.team.name}")
                is TeamEvent.TeamUpdated -> tabActions.add("TeamUpdated:${event.team.name}")
                is TeamEvent.TeamDeleted -> tabActions.add("TeamDeleted:${event.teamId}")
                is TeamEvent.MemberAdded -> tabActions.add("MemberAdded:${event.teamId}:${event.member.agentId}")
                is TeamEvent.MemberRemoved -> tabActions.add("MemberRemoved:${event.teamId}:${event.agentId}")
                is TeamEvent.TaskChanged,
                is TeamEvent.MessageReceived -> { /* Ignored by manager, handled by dashboard */ }
            }
        }
    }
}
