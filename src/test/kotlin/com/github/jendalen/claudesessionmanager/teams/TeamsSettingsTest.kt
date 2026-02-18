package com.github.jendalen.claudesessionmanager.teams

import com.github.jendalen.claudesessionmanager.teams.model.TeamsSettings
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamsSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default settings have expected values`() {
        val settings = TeamsSettings.DEFAULT

        assertFalse(settings.enableTeamDetection)
        assertFalse(settings.createTabsForInProcessTeammates)
        assertFalse(settings.nativeTeammateSpawning)
        assertTrue(settings.groupTeammateTabs)
        assertFalse(settings.autoFocusNewTeammateTabs)
        assertTrue(settings.showDashboardOnTeamCreate)
        assertEquals(20, settings.maxMessagesInLog)
        assertEquals(80, settings.truncateMessageLength)
        assertTrue(settings.notifyOnTeamCreate)
        assertTrue(settings.notifyOnTaskComplete)
        assertTrue(settings.notifyOnTeammateError)
        assertFalse(settings.notifyOnTeammateShutdown)
    }

    @Test
    fun `validated corrects maxMessagesInLog below minimum`() {
        val settings = TeamsSettings(maxMessagesInLog = 1)
        val validated = settings.validated()
        assertEquals(TeamsSettings.MIN_MESSAGES_IN_LOG, validated.maxMessagesInLog)
    }

    @Test
    fun `validated corrects maxMessagesInLog above maximum`() {
        val settings = TeamsSettings(maxMessagesInLog = 999)
        val validated = settings.validated()
        assertEquals(TeamsSettings.MAX_MESSAGES_IN_LOG, validated.maxMessagesInLog)
    }

    @Test
    fun `validated corrects truncateMessageLength below minimum`() {
        val settings = TeamsSettings(truncateMessageLength = 5)
        val validated = settings.validated()
        assertEquals(TeamsSettings.MIN_TRUNCATE_LENGTH, validated.truncateMessageLength)
    }

    @Test
    fun `validated corrects truncateMessageLength above maximum`() {
        val settings = TeamsSettings(truncateMessageLength = 9999)
        val validated = settings.validated()
        assertEquals(TeamsSettings.MAX_TRUNCATE_LENGTH, validated.truncateMessageLength)
    }

    @Test
    fun `validated preserves values within range`() {
        val settings = TeamsSettings(maxMessagesInLog = 50, truncateMessageLength = 200)
        val validated = settings.validated()
        assertEquals(50, validated.maxMessagesInLog)
        assertEquals(200, validated.truncateMessageLength)
    }

    @Test
    fun `validated preserves other fields`() {
        val settings = TeamsSettings(
            enableTeamDetection = false,
            nativeTeammateSpawning = true,
            maxMessagesInLog = 1 // will be corrected
        )
        val validated = settings.validated()
        assertFalse(validated.enableTeamDetection)
        assertTrue(validated.nativeTeammateSpawning)
    }

    @Test
    fun `validated at boundary values`() {
        val atMin = TeamsSettings(
            maxMessagesInLog = TeamsSettings.MIN_MESSAGES_IN_LOG,
            truncateMessageLength = TeamsSettings.MIN_TRUNCATE_LENGTH
        )
        val validatedMin = atMin.validated()
        assertEquals(TeamsSettings.MIN_MESSAGES_IN_LOG, validatedMin.maxMessagesInLog)
        assertEquals(TeamsSettings.MIN_TRUNCATE_LENGTH, validatedMin.truncateMessageLength)

        val atMax = TeamsSettings(
            maxMessagesInLog = TeamsSettings.MAX_MESSAGES_IN_LOG,
            truncateMessageLength = TeamsSettings.MAX_TRUNCATE_LENGTH
        )
        val validatedMax = atMax.validated()
        assertEquals(TeamsSettings.MAX_MESSAGES_IN_LOG, validatedMax.maxMessagesInLog)
        assertEquals(TeamsSettings.MAX_TRUNCATE_LENGTH, validatedMax.truncateMessageLength)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = TeamsSettings(
            enableTeamDetection = false,
            createTabsForInProcessTeammates = false,
            nativeTeammateSpawning = true,
            groupTeammateTabs = false,
            autoFocusNewTeammateTabs = true,
            showDashboardOnTeamCreate = false,
            maxMessagesInLog = 50,
            truncateMessageLength = 120,
            notifyOnTeamCreate = false,
            notifyOnTaskComplete = false,
            notifyOnTeammateError = false,
            notifyOnTeammateShutdown = true
        )

        val serialized = json.encodeToString(TeamsSettings.serializer(), original)
        val deserialized = json.decodeFromString<TeamsSettings>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `deserialize with unknown fields`() {
        val input = """
        {
            "enableTeamDetection": true,
            "futureFeature": "value",
            "maxMessagesInLog": 30
        }
        """.trimIndent()
        val settings = json.decodeFromString<TeamsSettings>(input)
        assertTrue(settings.enableTeamDetection)
        assertEquals(30, settings.maxMessagesInLog)
    }
}
