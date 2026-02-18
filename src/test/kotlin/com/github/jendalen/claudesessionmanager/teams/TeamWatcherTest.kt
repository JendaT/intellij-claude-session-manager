package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamMember
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel
import com.github.jendalen.claudesessionmanager.teams.services.TeamEventListener
import com.github.jendalen.claudesessionmanager.teams.services.TeamWatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for TeamWatcher event emission patterns and listener management.
 *
 * Uses a test implementation of TeamWatcher to verify the event listener
 * contract, concurrency safety, and member diff logic that
 * FileSystemTeamWatcher uses internally.
 */
class TeamWatcherTest {

    private lateinit var watcher: TestTeamWatcher
    private lateinit var collectedEvents: MutableList<TeamEvent>

    @BeforeEach
    fun setUp() {
        watcher = TestTeamWatcher()
        collectedEvents = mutableListOf()
    }

    // --- Listener Management ---

    @Test
    fun `addListener receives subsequent events`() {
        watcher.addListener { collectedEvents.add(it) }

        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))

        assertEquals(1, collectedEvents.size)
        assertTrue(collectedEvents[0] is TeamEvent.TeamCreated)
    }

    @Test
    fun `removeListener stops receiving events`() {
        val listener = TeamEventListener { collectedEvents.add(it) }
        watcher.addListener(listener)

        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))
        assertEquals(1, collectedEvents.size)

        watcher.removeListener(listener)
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 2L))
        assertEquals(1, collectedEvents.size) // no new events
    }

    @Test
    fun `multiple listeners all receive events`() {
        val events1 = mutableListOf<TeamEvent>()
        val events2 = mutableListOf<TeamEvent>()

        watcher.addListener { events1.add(it) }
        watcher.addListener { events2.add(it) }

        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))

        assertEquals(1, events1.size)
        assertEquals(1, events2.size)
    }

    @Test
    fun `listener exception does not prevent other listeners from receiving events`() {
        val goodEvents = mutableListOf<TeamEvent>()

        watcher.addListener { throw RuntimeException("bad listener") }
        watcher.addListener { goodEvents.add(it) }

        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))

        assertEquals(1, goodEvents.size) // second listener still received the event
    }

    @Test
    fun `no listeners does not throw`() {
        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))
        // No exception thrown
    }

    // --- Active Teams Tracking ---

    @Test
    fun `getActiveTeams returns empty initially`() {
        assertTrue(watcher.getActiveTeams().isEmpty())
    }

    @Test
    fun `getActiveTeams returns teams after creation events`() {
        val team1 = TeamModel(name = "team-1")
        val team2 = TeamModel(name = "team-2")

        watcher.trackTeam("t1", team1)
        watcher.trackTeam("t2", team2)

        assertEquals(2, watcher.getActiveTeams().size)
    }

    @Test
    fun `getActiveTeams removes team after deletion`() {
        val team = TeamModel(name = "team-1")
        watcher.trackTeam("t1", team)
        assertEquals(1, watcher.getActiveTeams().size)

        watcher.untrackTeam("t1")
        assertTrue(watcher.getActiveTeams().isEmpty())
    }

    // --- Member Diff Logic ---

    @Test
    fun `diff detects added members`() {
        val oldMembers = listOf(
            TeamMember(agentId = "a@t", name = "a")
        )
        val newMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )

        val events = diffMembers("team1", oldMembers, newMembers)

        assertEquals(1, events.size)
        assertTrue(events[0] is TeamEvent.MemberAdded)
        assertEquals("b@t", (events[0] as TeamEvent.MemberAdded).member.agentId)
    }

    @Test
    fun `diff detects removed members`() {
        val oldMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )
        val newMembers = listOf(
            TeamMember(agentId = "a@t", name = "a")
        )

        val events = diffMembers("team1", oldMembers, newMembers)

        assertEquals(1, events.size)
        assertTrue(events[0] is TeamEvent.MemberRemoved)
        assertEquals("b@t", (events[0] as TeamEvent.MemberRemoved).agentId)
    }

    @Test
    fun `diff detects both added and removed members`() {
        val oldMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )
        val newMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "c@t", name = "c")
        )

        val events = diffMembers("team1", oldMembers, newMembers)

        assertEquals(2, events.size)
        val added = events.filterIsInstance<TeamEvent.MemberAdded>()
        val removed = events.filterIsInstance<TeamEvent.MemberRemoved>()
        assertEquals(1, added.size)
        assertEquals(1, removed.size)
        assertEquals("c@t", added[0].member.agentId)
        assertEquals("b@t", removed[0].agentId)
    }

    @Test
    fun `diff with no changes returns empty`() {
        val members = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )

        val events = diffMembers("team1", members, members)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `diff with empty old members treats all as added`() {
        val newMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )

        val events = diffMembers("team1", emptyList(), newMembers)
        assertEquals(2, events.size)
        assertTrue(events.all { it is TeamEvent.MemberAdded })
    }

    @Test
    fun `diff with empty new members treats all as removed`() {
        val oldMembers = listOf(
            TeamMember(agentId = "a@t", name = "a"),
            TeamMember(agentId = "b@t", name = "b")
        )

        val events = diffMembers("team1", oldMembers, emptyList())
        assertEquals(2, events.size)
        assertTrue(events.all { it is TeamEvent.MemberRemoved })
    }

    // --- Concurrent Listener Operations ---

    @Test
    fun `concurrent add and fire does not throw`() {
        val latch = CountDownLatch(100)

        // Add listeners from multiple threads
        val threads = (1..10).map { _ ->
            Thread {
                for (i in 1..10) {
                    watcher.addListener { latch.countDown() }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Fire event to all listeners
        val team = TeamModel(name = "t")
        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    // --- Event Type Coverage ---

    @Test
    fun `all event types can be dispatched`() {
        watcher.addListener { collectedEvents.add(it) }

        val team = TeamModel(name = "t")
        val member = TeamMember(agentId = "a@t", name = "a")
        val task = com.github.jendalen.claudesessionmanager.teams.model.TeamTask(id = "1")
        val msg = com.github.jendalen.claudesessionmanager.teams.model.InboxMessage(type = "message")

        watcher.simulateEvent(TeamEvent.TeamCreated(team, timestamp = 1L))
        watcher.simulateEvent(TeamEvent.TeamUpdated(team, timestamp = 2L))
        watcher.simulateEvent(TeamEvent.TeamDeleted("t1", timestamp = 3L))
        watcher.simulateEvent(TeamEvent.MemberAdded("t1", member, timestamp = 4L))
        watcher.simulateEvent(TeamEvent.MemberRemoved("t1", "a@t", timestamp = 5L))
        watcher.simulateEvent(TeamEvent.TaskChanged("t1", task, timestamp = 6L))
        watcher.simulateEvent(TeamEvent.MessageReceived("t1", msg, timestamp = 7L))

        assertEquals(7, collectedEvents.size)
    }

    // --- Helper: Replicates FileSystemTeamWatcher's diffMembers logic ---

    private fun diffMembers(
        teamId: String,
        oldMembers: List<TeamMember>,
        newMembers: List<TeamMember>
    ): List<TeamEvent> {
        val events = mutableListOf<TeamEvent>()
        val oldIds = oldMembers.map { it.agentId }.toSet()
        val newIds = newMembers.map { it.agentId }.toSet()

        val added = newIds - oldIds
        val removed = oldIds - newIds

        for (agentId in added) {
            val member = newMembers.find { it.agentId == agentId } ?: continue
            events.add(TeamEvent.MemberAdded(teamId, member))
        }
        for (agentId in removed) {
            events.add(TeamEvent.MemberRemoved(teamId, agentId))
        }
        return events
    }

    /**
     * Test implementation of TeamWatcher that allows manual event simulation.
     */
    private class TestTeamWatcher : TeamWatcher {
        private val listeners = CopyOnWriteArrayList<TeamEventListener>()
        private val activeTeams = java.util.concurrent.ConcurrentHashMap<String, TeamModel>()
        private var running = false

        override fun start() { running = true }
        override fun stop() { running = false }

        override fun getActiveTeams(): List<TeamModel> = activeTeams.values.toList()

        override fun addListener(listener: TeamEventListener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: TeamEventListener) {
            listeners.remove(listener)
        }

        fun trackTeam(id: String, team: TeamModel) {
            activeTeams[id] = team
        }

        fun untrackTeam(id: String) {
            activeTeams.remove(id)
        }

        fun simulateEvent(event: TeamEvent) {
            for (listener in listeners) {
                try {
                    listener.onTeamEvent(event)
                } catch (_: Exception) {
                    // Swallow, like FileSystemTeamWatcher does
                }
            }
        }
    }
}
