package com.github.jendalen.claudesessionmanager.util

import com.github.jendalen.claudesessionmanager.util.TitleSyncRules.Decision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TitleSyncRulesTest {

    private fun decide(
        current: String? = "Local",
        title: String = "acme-main",
        first: Boolean = false,
        userNamed: Boolean = false,
        profile: Boolean = false,
        taken: Boolean = false
    ) = TitleSyncRules.decide(current, title, first, userNamed, profile, taken)

    // The 2026-09-30 first start: sessions renamed days earlier must not overwrite tabs
    // the user named on purpose, but a default "Local" tab is better off with the title.
    @Test
    fun `first sighting renames only a default-named tab`() {
        assertEquals(Decision.RENAME, decide(current = "Local", first = true, userNamed = false))
        assertEquals(Decision.KEEP_USER_NAME, decide(current = "Kafka master", title = "WT1-PL-main", first = true, userNamed = true))
    }

    @Test
    fun `a later rename renames any tab`() {
        assertEquals(Decision.RENAME, decide(current = "Kafka master", title = "WT1-PL-main", first = false, userNamed = true))
    }

    @Test
    fun `profile tabs, taken names and equal names are never renamed`() {
        assertEquals(Decision.PROFILE_TAB, decide(current = "acme-worker1", profile = true, userNamed = true))
        assertEquals(Decision.NAME_TAKEN, decide(taken = true))
        assertEquals(Decision.ALREADY_NAMED, decide(current = "acme-main", title = "acme-main", profile = true))
    }
}
