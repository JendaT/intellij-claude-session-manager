package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.TabProfile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectSettingsServiceTest {

    // The list is loaded from workspace.xml, which a cloned repository can carry; the
    // directory and the arguments are typed into a shell. What the settings page would
    // refuse must not come back in through loadState().
    @Test
    fun `invalid stored profiles are kept for the page but never used`() {
        val service = ProjectSettingsService()
        val state = ProjectSettingsService.State().apply {
            tabProfiles = mutableListOf(
                entry("acme-worker1", "~/Projects/Acme/worktrees/worker1", "--channels plugin:inbox-channel@local-tools"),
                entry("evil-dir", "/tmp/x'; rm -rf ~; '", ""),
                entry("evil-args", "/tmp", "--mcp-config /tmp/x.json"),
                entry("acme-worker1", "/somewhere/else", "")
            )
        }
        service.loadState(state)
        assertEquals(4, service.storedTabProfiles.size)
        assertEquals(listOf("acme-worker1"), service.tabProfiles.map { it.name })
        assertEquals("~/Projects/Acme/worktrees/worker1", service.profileFor("acme-worker1")?.workingDirectory)
        assertNull(service.profileFor("evil-dir"))
        assertNull(service.profileFor(null))
    }

    @Test
    fun `stored profiles round-trip with normalised arguments`() {
        val service = ProjectSettingsService()
        service.storedTabProfiles = listOf(TabProfile("w", " /w ", "--channels   plugin:a@b ", keepOpen = true))
        assertEquals(listOf(TabProfile("w", "/w", "--channels plugin:a@b", keepOpen = true)), service.storedTabProfiles)
        assertEquals(1, service.state.tabProfiles.size)
    }

    private fun entry(name: String, dir: String, args: String) = ProjectSettingsService.TabProfileEntry().apply {
        this.name = name
        workingDirectory = dir
        launchArgs = args
    }
}
