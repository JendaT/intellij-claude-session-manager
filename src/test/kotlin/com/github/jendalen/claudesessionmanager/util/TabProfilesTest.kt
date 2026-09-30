package com.github.jendalen.claudesessionmanager.util

import com.github.jendalen.claudesessionmanager.model.TabProfile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabProfilesTest {

    private val home = "/Users/me"

    private fun worktree(dir: File, name: String): File =
        File(dir, name).apply { mkdirs(); File(this, ".git").writeText("gitdir: /x/.git/worktrees/$name\n") }

    @Test
    fun `expands the home directory only at the start`() {
        assertEquals("/Users/me", TabProfiles.expandHome("~", home))
        assertEquals("/Users/me/Projects/Acme/worktrees/worker1", TabProfiles.expandHome(" ~/Projects/Acme/worktrees/worker1 ", home))
        assertEquals("/abs/~/x", TabProfiles.expandHome("/abs/~/x", home))
        assertEquals("~user/x", TabProfiles.expandHome("~user/x", home))
    }

    @Test
    fun `the Acme entries are valid and quote safely`(@TempDir dir: File) {
        val profiles = listOf(
            TabProfile("acme-worker1", "${dir.path}/worker1", "--channels plugin:inbox-channel@local-tools", keepOpen = true),
            TabProfile("acme-worker2", "${dir.path}/worker2", "--channels plugin:inbox-channel@local-tools", keepOpen = true)
        )
        worktree(dir, "worker1"); worktree(dir, "worker2")
        val v = TabProfiles.validate(profiles, home)
        assertEquals(emptyList(), v.errors)
        assertEquals(emptyList(), v.warnings)
        assertEquals("'/Users/me/Projects/My Worktree'", TabProfiles.shellQuotedDirectory("~/Projects/My Worktree/", home))
    }

    // The hand-off: refuse duplicate tab names.
    @Test
    fun `duplicate names are an error and usable keeps the first`() {
        val a = TabProfile("w", "/a")
        val b = TabProfile("w", "/b")
        assertTrue(TabProfiles.validate(listOf(a, b), home).errors.any { it.contains("more than one profile") })
        assertEquals(listOf(a), TabProfiles.usable(listOf(a, b), home))
    }

    @Test
    fun `names, directories and arguments that are refused`() {
        for (bad in listOf("", " w", "w ", "a\tb", "x".repeat(TabProfiles.MAX_NAME_LENGTH + 1))) {
            assertFalse(TabProfiles.validate(listOf(TabProfile(bad, "/a")), home).isValid, "name should be refused: '$bad'")
        }
        for (bad in listOf("", "relative/dir", "~user/x", "/a'b", "/a\\b", "/a\nb")) {
            assertFalse(TabProfiles.validate(listOf(TabProfile("w", bad)), home).isValid, "dir should be refused: '$bad'")
        }
        for (bad in listOf("--resume x", "--mcp-config x.json", "--a; rm -rf ~", "--name x")) {
            assertFalse(TabProfiles.validate(listOf(TabProfile("w", "/a", bad)), home).isValid, "args should be refused: '$bad'")
        }
        assertTrue(TabProfiles.usable(listOf(TabProfile("w", "/a'b")), home).isEmpty())
        assertThrows<IllegalArgumentException> { TabProfiles.shellQuotedDirectory("/a'b", home) }
    }

    // Warnings are saved: a worktree may be created after its profile.
    @Test
    fun `missing directory, non-git directory and a shared directory are warnings`(@TempDir dir: File) {
        val plain = File(dir, "plain").apply { mkdirs() }
        val v = TabProfiles.validate(
            listOf(
                TabProfile("missing", "${dir.path}/nope"),
                TabProfile("plain", plain.path),
                TabProfile("again", plain.path)
            ),
            home
        )
        assertTrue(v.isValid)
        assertTrue(v.warnings.any { it.startsWith("missing:") && it.contains("does not exist") })
        assertTrue(v.warnings.any { it.startsWith("plain:") && it.contains("not inside a git work tree") })
        assertTrue(v.warnings.any { it.contains("same directory") })
    }

    @Test
    fun `a subdirectory of a work tree counts as inside it`(@TempDir dir: File) {
        val sub = File(worktree(dir, "worker1"), "packages/api").apply { mkdirs() }
        assertTrue(TabProfiles.isInsideGitWorkTree(sub))
        assertFalse(TabProfiles.isInsideGitWorkTree(File(dir, "elsewhere").apply { mkdirs() }))
    }

    @Test
    fun `find matches the exact tab name only`() {
        val profiles = listOf(TabProfile("acme-worker1", "/w1"))
        assertEquals("acme-worker1", TabProfiles.find(profiles, "acme-worker1")?.name)
        assertNull(TabProfiles.find(profiles, "ACME-worker1"))
        assertNull(TabProfiles.find(profiles, "acme-worker1 "))
        assertNull(TabProfiles.find(profiles, null))
    }

    // Hand-off rule 5: without a profile every tab's root is the project folder, which is
    // the check the detection channels made before profiles existed.
    @Test
    fun `root is the profile directory for a profile tab and the project folder otherwise`() {
        val base = "/Users/me/Projects/Acme/source/acme-server"
        val w1 = TabProfile("acme-worker1", "~/Projects/Acme/worktrees/worker1")
        assertEquals(base, TabProfiles.rootFor(base, null, home))
        assertEquals("/Users/me/Projects/Acme/worktrees/worker1", TabProfiles.rootFor(base, w1, home))

        val root1 = TabProfiles.rootFor(base, w1, home)!!
        assertTrue(TabProfiles.isUnder(root1, "/Users/me/Projects/Acme/worktrees/worker1"))
        assertTrue(TabProfiles.isUnder(root1, "/Users/me/Projects/Acme/worktrees/worker1/packages/api"))
        assertFalse(TabProfiles.isUnder(root1, "/Users/me/Projects/Acme/worktrees/worker10"))
        assertFalse(TabProfiles.isUnder(root1, base))
        assertFalse(TabProfiles.isUnder(base, "/Users/me/Projects/Acme/worktrees/worker1"))

        assertTrue(TabProfiles.isUnderAnyRoot(base, listOf(w1), "/Users/me/Projects/Acme/worktrees/worker1", home))
        assertTrue(TabProfiles.isUnderAnyRoot(base, emptyList(), "$base/src", home))
        assertFalse(TabProfiles.isUnderAnyRoot(base, emptyList(), "/Users/me/Projects/Acme/worktrees/worker1", home))
    }

    @Test
    fun `symlinked roots match the physical cwd Claude reports`(@TempDir dir: File) {
        val real = File(dir, "real").apply { mkdirs() }
        val link = File(dir, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(TabProfiles.isUnder(link.path, real.canonicalPath))
    }
}
