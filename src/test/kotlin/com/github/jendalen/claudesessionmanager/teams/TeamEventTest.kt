package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import com.github.jendalen.claudesessionmanager.teams.model.TaskStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamEventTest {

    private val sampleTeam = TeamModel(name = "test", leadAgentId = "lead@t")
    private val sampleMember = TeamMember(agentId = "w@t", name = "worker")
    private val sampleTask = TeamTask(id = "1", description = "task", status = TaskStatus.PENDING)
    private val sampleMessage = InboxMessage(type = "message", from = "a", to = "b", content = "hello")

    @Test
    fun `TeamCreated stores team and timestamp`() {
        val event = TeamEvent.TeamCreated(team = sampleTeam, timestamp = 100L)
        assertEquals(sampleTeam, event.team)
        assertEquals(100L, event.timestamp)
    }

    @Test
    fun `TeamCreated default timestamp is current time`() {
        val before = System.currentTimeMillis()
        val event = TeamEvent.TeamCreated(team = sampleTeam)
        val after = System.currentTimeMillis()
        assertTrue(event.timestamp in before..after)
    }

    @Test
    fun `TeamUpdated stores team`() {
        val event = TeamEvent.TeamUpdated(team = sampleTeam, timestamp = 200L)
        assertEquals(sampleTeam, event.team)
        assertEquals(200L, event.timestamp)
    }

    @Test
    fun `TeamDeleted stores teamId`() {
        val event = TeamEvent.TeamDeleted(teamId = "abc123", timestamp = 300L)
        assertEquals("abc123", event.teamId)
        assertEquals(300L, event.timestamp)
    }

    @Test
    fun `MemberAdded stores teamId and member`() {
        val event = TeamEvent.MemberAdded(teamId = "t1", member = sampleMember, timestamp = 400L)
        assertEquals("t1", event.teamId)
        assertEquals(sampleMember, event.member)
        assertEquals(400L, event.timestamp)
    }

    @Test
    fun `MemberRemoved stores teamId and agentId`() {
        val event = TeamEvent.MemberRemoved(teamId = "t1", agentId = "w@t", timestamp = 500L)
        assertEquals("t1", event.teamId)
        assertEquals("w@t", event.agentId)
    }

    @Test
    fun `TaskChanged stores teamId and task`() {
        val event = TeamEvent.TaskChanged(teamId = "t1", task = sampleTask, timestamp = 600L)
        assertEquals("t1", event.teamId)
        assertEquals(sampleTask, event.task)
    }

    @Test
    fun `MessageReceived stores teamId and message`() {
        val event = TeamEvent.MessageReceived(teamId = "t1", message = sampleMessage, timestamp = 700L)
        assertEquals("t1", event.teamId)
        assertEquals(sampleMessage, event.message)
    }

    @Test
    fun `all event types are TeamEvent subtypes`() {
        val events: List<TeamEvent> = listOf(
            TeamEvent.TeamCreated(team = sampleTeam, timestamp = 1L),
            TeamEvent.TeamUpdated(team = sampleTeam, timestamp = 2L),
            TeamEvent.TeamDeleted(teamId = "x", timestamp = 3L),
            TeamEvent.MemberAdded(teamId = "x", member = sampleMember, timestamp = 4L),
            TeamEvent.MemberRemoved(teamId = "x", agentId = "y", timestamp = 5L),
            TeamEvent.TaskChanged(teamId = "x", task = sampleTask, timestamp = 6L),
            TeamEvent.MessageReceived(teamId = "x", message = sampleMessage, timestamp = 7L)
        )
        assertEquals(7, events.size)
        events.forEachIndexed { i, event ->
            assertEquals((i + 1).toLong(), event.timestamp)
        }
    }

    @Test
    fun `event data classes support equality and copy`() {
        val event1 = TeamEvent.TeamDeleted(teamId = "abc", timestamp = 100L)
        val event2 = TeamEvent.TeamDeleted(teamId = "abc", timestamp = 100L)
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())

        val event3 = event1.copy(teamId = "xyz")
        assertEquals("xyz", event3.teamId)
        assertEquals(100L, event3.timestamp)
    }
}
