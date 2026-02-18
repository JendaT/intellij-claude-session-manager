package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TaskStatus
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamTaskTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize task with all fields`() {
        val input = """
        {
          "id": "1",
          "subject": "Implement authentication",
          "owner": "worker-1",
          "status": "in_progress",
          "blockedBy": ["0"],
          "blocks": ["2"],
          "activeForm": "Implementing auth",
          "createdAt": 1706000000000,
          "completedAt": null
        }
        """.trimIndent()

        val task = json.decodeFromString<TeamTask>(input)

        assertEquals("1", task.id)
        assertEquals("Implement authentication", task.description)
        assertEquals("worker-1", task.assignee)
        assertEquals(TaskStatus.IN_PROGRESS, task.status)
        assertEquals(listOf("0"), task.dependencies)
        assertEquals(listOf("2"), task.blocks)
        assertEquals("Implementing auth", task.activeForm)
        assertEquals(1706000000000L, task.createdAt)
        assertNull(task.completedAt)
    }

    @Test
    fun `deserialize completed task`() {
        val input = """
        {
          "id": "3",
          "subject": "Deploy",
          "status": "completed",
          "completedAt": 1706000500000
        }
        """.trimIndent()

        val task = json.decodeFromString<TeamTask>(input)
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(1706000500000L, task.completedAt)
    }

    @Test
    fun `deserialize minimal task with defaults`() {
        val input = """{"id": "99"}"""
        val task = json.decodeFromString<TeamTask>(input)

        assertEquals("99", task.id)
        assertEquals("", task.description)
        assertEquals("", task.assignee)
        assertEquals(TaskStatus.PENDING, task.status)
        assertTrue(task.dependencies.isEmpty())
        assertTrue(task.blocks.isEmpty())
        assertEquals("", task.activeForm)
        assertEquals(0L, task.createdAt)
        assertNull(task.completedAt)
    }

    @Test
    fun `deserialize task list from fixture`() {
        val input = TestFixtures.loadFixture("valid_tasks.json")
        val tasks = json.decodeFromString<List<TeamTask>>(input)

        assertEquals(3, tasks.size)
        assertEquals("1", tasks[0].id)
        assertEquals(TaskStatus.IN_PROGRESS, tasks[0].status)
        assertEquals("Implementing authentication", tasks[0].activeForm)
        assertEquals("2", tasks[1].id)
        assertEquals(TaskStatus.PENDING, tasks[1].status)
        assertEquals(listOf("1"), tasks[1].dependencies)
        assertEquals("3", tasks[2].id)
        assertEquals(TaskStatus.COMPLETED, tasks[2].status)
    }

    @Test
    fun `isUnblocked returns true when no dependencies`() {
        val task = TeamTask(id = "1")
        assertTrue(task.isUnblocked(emptySet()))
    }

    @Test
    fun `isUnblocked returns true when all dependencies completed`() {
        val task = TeamTask(id = "3", dependencies = listOf("1", "2"))
        assertTrue(task.isUnblocked(setOf("1", "2", "0")))
    }

    @Test
    fun `isUnblocked returns false when some dependencies not completed`() {
        val task = TeamTask(id = "3", dependencies = listOf("1", "2"))
        assertFalse(task.isUnblocked(setOf("1")))
    }

    @Test
    fun `isUnblocked returns false when no dependencies completed`() {
        val task = TeamTask(id = "3", dependencies = listOf("1", "2"))
        assertFalse(task.isUnblocked(emptySet()))
    }

    @Test
    fun `displayLabel combines id and description`() {
        val task = TeamTask(id = "5", description = "Implement login page")
        assertEquals("#5 Implement login page", task.displayLabel())
    }

    @Test
    fun `displayLabel truncates long description`() {
        val longDesc = "A".repeat(100)
        val task = TeamTask(id = "1", description = longDesc)
        val label = task.displayLabel()
        assertTrue(label.startsWith("#1 "))
        assertTrue(label.length <= 63) // "#1 " + 60 chars
    }

    @Test
    fun `displayLabel uses first line of multiline description`() {
        val task = TeamTask(id = "2", description = "First line\nSecond line\nThird line")
        assertEquals("#2 First line", task.displayLabel())
    }

    @Test
    fun `displayLabel with empty description`() {
        val task = TeamTask(id = "7", description = "")
        assertEquals("#7 ", task.displayLabel())
    }

    @Test
    fun `all TaskStatus values can be deserialized with lowercase names`() {
        val statusToJson = mapOf(
            TaskStatus.PENDING to "pending",
            TaskStatus.IN_PROGRESS to "in_progress",
            TaskStatus.COMPLETED to "completed",
            TaskStatus.BLOCKED to "blocked"
        )
        for ((status, jsonValue) in statusToJson) {
            val input = """{"id": "1", "status": "$jsonValue"}"""
            val task = json.decodeFromString<TeamTask>(input)
            assertEquals(status, task.status)
        }
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = TeamTask(
            id = "42",
            description = "Test task",
            assignee = "tester",
            status = TaskStatus.IN_PROGRESS,
            dependencies = listOf("1", "2"),
            blocks = listOf("3"),
            activeForm = "Testing",
            createdAt = 1706000000000L,
            completedAt = null
        )
        val serialized = json.encodeToString(TeamTask.serializer(), original)
        val deserialized = json.decodeFromString<TeamTask>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialize uses SerialName values`() {
        val task = TeamTask(
            id = "1",
            description = "desc",
            assignee = "the-owner",
            status = TaskStatus.IN_PROGRESS,
            dependencies = listOf("0")
        )
        val serialized = json.encodeToString(TeamTask.serializer(), task)
        // Check that JSON keys use @SerialName values
        assertTrue(serialized.contains("\"subject\""), "Expected 'subject' key, got: $serialized")
        assertTrue(serialized.contains("\"owner\""), "Expected 'owner' key, got: $serialized")
        assertTrue(serialized.contains("\"blockedBy\""), "Expected 'blockedBy' key, got: $serialized")
        assertTrue(serialized.contains("\"in_progress\""), "Expected 'in_progress' status value, got: $serialized")
        // Check that Kotlin property names are NOT in the JSON
        assertFalse(serialized.contains("\"assignee\""), "Did not expect 'assignee' key, got: $serialized")
        assertFalse(serialized.contains("\"description\""), "Did not expect 'description' key, got: $serialized")
        assertFalse(serialized.contains("\"dependencies\""), "Did not expect 'dependencies' key, got: $serialized")
    }

    @Test
    fun `serialize and deserialize completed task round trip`() {
        val original = TeamTask(
            id = "10",
            status = TaskStatus.COMPLETED,
            completedAt = 1706000500000L
        )
        val serialized = json.encodeToString(TeamTask.serializer(), original)
        val deserialized = json.decodeFromString<TeamTask>(serialized)
        assertEquals(original, deserialized)
    }
}
