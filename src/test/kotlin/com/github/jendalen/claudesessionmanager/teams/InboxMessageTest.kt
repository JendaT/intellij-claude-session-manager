package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InboxMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize valid message`() {
        val input = TestFixtures.loadFixture("valid_inbox_message.json")
        val msg = json.decodeFromString<InboxMessage>(input)

        assertEquals("message", msg.type)
        assertEquals("team-lead", msg.from)
        assertEquals("worker-1", msg.to)
        assertEquals("Please review the authentication module", msg.content)
        assertEquals(1706000100000L, msg.timestamp)
        assertEquals("Review auth module", msg.summary)
    }

    @Test
    fun `deserialize broadcast message`() {
        val input = TestFixtures.loadFixture("broadcast_message.json")
        val msg = json.decodeFromString<InboxMessage>(input)

        assertEquals("broadcast", msg.type)
        assertEquals("team-lead", msg.from)
        assertEquals("", msg.to)
        assertEquals("Pull latest changes", msg.summary)
    }

    @Test
    fun `deserialize shutdown request`() {
        val input = TestFixtures.loadFixture("shutdown_request.json")
        val msg = json.decodeFromString<InboxMessage>(input)

        assertEquals("shutdown_request", msg.type)
        assertEquals("team-lead", msg.from)
        assertEquals("worker-1", msg.to)
    }

    @Test
    fun `deserialize minimal message with defaults`() {
        val input = """{"type": "message", "sender": "a", "recipient": "b"}"""
        val msg = json.decodeFromString<InboxMessage>(input)

        assertEquals("message", msg.type)
        assertEquals("a", msg.from)
        assertEquals("b", msg.to)
        assertEquals("", msg.content)
        assertEquals(0L, msg.timestamp)
        assertEquals("", msg.summary)
    }

    @Test
    fun `contentPreview returns summary when available`() {
        val msg = InboxMessage(
            type = "message",
            from = "a",
            to = "b",
            content = "This is a very long content that should not be used when summary is available",
            summary = "Short summary"
        )
        assertEquals("Short summary", msg.contentPreview())
    }

    @Test
    fun `contentPreview falls back to content when summary is blank`() {
        val msg = InboxMessage(
            type = "message",
            from = "a",
            to = "b",
            content = "Content here",
            summary = ""
        )
        assertEquals("Content here", msg.contentPreview())
    }

    @Test
    fun `contentPreview truncates long text`() {
        val longText = "A".repeat(100)
        val msg = InboxMessage(content = longText)
        val preview = msg.contentPreview(maxLength = 80)
        assertEquals(83, preview.length) // 80 + "..."
        assert(preview.endsWith("..."))
    }

    @Test
    fun `contentPreview does not truncate text at limit`() {
        val exactText = "A".repeat(80)
        val msg = InboxMessage(content = exactText)
        val preview = msg.contentPreview(maxLength = 80)
        assertEquals(80, preview.length)
        assertFalse(preview.endsWith("..."))
    }

    @Test
    fun `contentPreview with custom max length does not truncate short text`() {
        val msg = InboxMessage(content = "Hi")
        assertEquals("Hi", msg.contentPreview(maxLength = 5))
    }

    @Test
    fun `contentPreview custom max length truncates long text`() {
        val msg = InboxMessage(content = "Hello World!")
        val preview = msg.contentPreview(maxLength = 5)
        assertEquals("Hello...", preview)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = InboxMessage(
            type = "message",
            from = "sender",
            to = "receiver",
            content = "Test content",
            timestamp = 1706000000000L,
            summary = "Test"
        )
        val serialized = json.encodeToString(InboxMessage.serializer(), original)
        val deserialized = json.decodeFromString<InboxMessage>(serialized)
        assertEquals(original, deserialized)
    }

    private fun assertFalse(condition: Boolean) {
        kotlin.test.assertFalse(condition)
    }
}
