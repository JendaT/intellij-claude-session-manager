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
}
