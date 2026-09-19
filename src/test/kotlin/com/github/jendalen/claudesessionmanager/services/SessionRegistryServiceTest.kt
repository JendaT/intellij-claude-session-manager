package com.github.jendalen.claudesessionmanager.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryServiceTest {

    private val id = "4e695d61-3fa2-40af-8f76-45977b1cddfa"

    private fun read(dir: File) = Json.parseToJsonElement(File(dir, "$id.json").readText()).jsonObject

    @Test
    fun `upsert creates the file with project and tab when the hook has not yet`(@TempDir dir: File) {
        SessionRegistryService().upsert(id, "Shop", "Local (2)", "/tmp/Shop", dir)
        val obj = read(dir)
        assertEquals(id, obj["session_id"]!!.jsonPrimitive.content)
        assertEquals("Shop", obj["project"]!!.jsonPrimitive.content)
        assertEquals("Local (2)", obj["tab"]!!.jsonPrimitive.content)
        assertEquals("/tmp/Shop", obj["cwd"]!!.jsonPrimitive.content)
        assertNull(obj["state"])
        assertTrue(obj["updated_at"]!!.jsonPrimitive.content.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-Z].*")))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `upsert merges into the hook's file and keeps its fields`(@TempDir dir: File) {
        File(dir, "$id.json").writeText("""{"session_id":"$id","cwd":"/real/cwd","state":"busy","pid":123,"updated_at":"x"}""")
        SessionRegistryService().upsert(id, "Shop", "Local", "/plugin/cwd", dir)
        val obj = read(dir)
        assertEquals("busy", obj["state"]!!.jsonPrimitive.content)
        assertEquals(123, obj["pid"]!!.jsonPrimitive.content.toInt())
        assertEquals("/real/cwd", obj["cwd"]!!.jsonPrimitive.content)
        assertEquals("Shop", obj["project"]!!.jsonPrimitive.content)
        assertEquals("Local", obj["tab"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert does not rewrite an unchanged file`(@TempDir dir: File) {
        val service = SessionRegistryService()
        service.upsert(id, "P", "T", null, dir)
        val file = File(dir, "$id.json")
        val before = file.readText()
        file.setLastModified(1_000_000_000_000L)
        service.upsert(id, "P", "T", null, dir)
        assertEquals(before, file.readText())
        assertEquals(1_000_000_000_000L, file.lastModified())
    }

    @Test
    fun `corrupt file is replaced`(@TempDir dir: File) {
        File(dir, "$id.json").writeText("not json")
        SessionRegistryService().upsert(id, "P", "T", null, dir)
        assertEquals("P", read(dir)["project"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid ids never touch the disk`(@TempDir dir: File) {
        SessionRegistryService().upsert("../evil", "P", "T", null, dir)
        SessionRegistryService().remove("../evil", dir)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `remove and removeAll`(@TempDir dir: File) {
        val service = SessionRegistryService()
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        service.upsert(id, "P", "T", null, dir)
        service.upsert(other, "P", "T", null, dir)
        service.removeAll(listOf(id, other, "not-an-id"), dir)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `sweep deletes only files older than the cutoff`(@TempDir dir: File) {
        val service = SessionRegistryService()
        val old = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        service.upsert(id, "P", "T", null, dir)
        service.upsert(old, "P", "T", null, dir)
        File(dir, "$old.json").setLastModified(System.currentTimeMillis() - 2 * SessionRegistryService.SWEEP_AGE_MS)
        assertEquals(1, service.sweep(dir = dir))
        assertTrue(File(dir, "$id.json").exists())
        assertFalse(File(dir, "$old.json").exists())
        assertEquals(0, service.sweep(dir = File(dir, "missing")))
    }

    // A session left idle for a day has an old file and a live process. Deleting it
    // would let read() say "not running" to a resume that then starts a second copy.
    @Test
    fun `sweep keeps an old file whose process is alive`(@TempDir dir: File) {
        val service = SessionRegistryService()
        val alivePid = ProcessHandle.current().pid()
        val deadPid = exitedProcessPid()
        val alive = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val dead = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"
        val noPid = "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee"
        File(dir, "$alive.json").writeText("""{"session_id":"$alive","pid":$alivePid,"state":"idle"}""")
        File(dir, "$dead.json").writeText("""{"session_id":"$dead","pid":$deadPid,"state":"idle"}""")
        File(dir, "$noPid.json").writeText("""{"session_id":"$noPid","project":"P"}""")
        val stale = System.currentTimeMillis() - 2 * SessionRegistryService.SWEEP_AGE_MS
        dir.listFiles()!!.forEach { it.setLastModified(stale) }

        assertEquals(2, service.sweep(dir = dir))
        assertTrue(File(dir, "$alive.json").exists(), "live process must survive the sweep")
        assertFalse(File(dir, "$dead.json").exists())
        assertFalse(File(dir, "$noPid.json").exists())
    }

    @Test
    fun `read returns the hook's and the plugin's fields`(@TempDir dir: File) {
        File(dir, "$id.json").writeText(
            """{"session_id":"$id","cwd":"/w/1","state":"busy","pid":4321,"updated_at":"x","project":"Shop-worktree1","tab":"Local"}"""
        )
        val entry = SessionRegistryService().read(id, dir)!!
        assertEquals(id, entry.sessionId)
        assertEquals("/w/1", entry.cwd)
        assertEquals("busy", entry.state)
        assertEquals(4321L, entry.pid)
        assertEquals("Shop-worktree1", entry.project)
        assertEquals("Local", entry.tab)
    }

    @Test
    fun `read tolerates absent fields and absent files`(@TempDir dir: File) {
        val service = SessionRegistryService()
        assertNull(service.read(id, dir))
        assertNull(service.read("not-an-id", dir))
        File(dir, "$id.json").writeText("""{"session_id":"$id","project":"P"}""")
        val entry = service.read(id, dir)!!
        assertNull(entry.cwd)
        assertNull(entry.pid)
        assertFalse(entry.isLive(), "no pid means no known process")
        File(dir, "$id.json").writeText("not json")
        assertNull(service.read(id, dir))
    }

    @Test
    fun `isLive follows the recorded process`(@TempDir dir: File) {
        val service = SessionRegistryService()
        File(dir, "$id.json").writeText("""{"session_id":"$id","pid":${ProcessHandle.current().pid()}}""")
        assertTrue(service.read(id, dir)!!.isLive())
        File(dir, "$id.json").writeText("""{"session_id":"$id","pid":${exitedProcessPid()}}""")
        assertFalse(service.read(id, dir)!!.isLive())
    }

    /** A pid that belonged to a process which has already exited. */
    private fun exitedProcessPid(): Long {
        val p = ProcessBuilder("true").start()
        p.waitFor()
        return p.pid()
    }
}
