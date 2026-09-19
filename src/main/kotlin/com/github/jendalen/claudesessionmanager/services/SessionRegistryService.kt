package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.SessionIdValidator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The plugin's side of the session registry: one file per live Claude session under
 * `~/.claude/ide-events/sessions/<session_id>.json`, shared with the hook script.
 *
 * The hook script owns `state`, `pid`, `cwd` and the file's lifecycle (created on
 * SessionStart, deleted on SessionEnd). The plugin owns `project` and `tab` - the only
 * component that knows which tab a session runs in - and merges them in without
 * touching the hook's fields. Every write goes through a temp file and an atomic rename
 * so an external reader never sees a torn file. Files are addressed by session id only;
 * the plugin never reads the directory to learn about other processes' sessions.
 */
@Service(Service.Level.APP)
class SessionRegistryService {

    private val logger = Logger.getInstance(SessionRegistryService::class.java)

    private val json = Json { prettyPrint = true }

    private val swept = AtomicBoolean(false)

    companion object {
        const val SWEEP_AGE_MS = 24L * 60 * 60 * 1000
        private const val MAX_FILE_BYTES = 64 * 1024

        fun getInstance(): SessionRegistryService {
            return ApplicationManager.getApplication().getService(SessionRegistryService::class.java)
        }

        /** Same location the hook script uses; independent of the custom Claude home. */
        fun defaultDirectory(): File = File(System.getProperty("user.home"), ".claude/ide-events/sessions")

        /**
         * `kill -0` without a shell. A pid whose process has exited reads as dead; a reused
         * pid reads as alive, which is accepted - it errs on the side of not resuming.
         */
        fun isProcessAlive(pid: Long): Boolean =
            try {
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            } catch (e: Exception) {
                false
            }
    }

    /**
     * Records that [sessionId] runs in [tab] of [project]. Creates the file when the hook
     * has not written it yet (the output channel binds a resumed session from the typed
     * command line, before Claude even starts), otherwise merges into it. No write when
     * nothing changes - the output channel re-confirms every binding every few seconds.
     *
     * [title] is Claude's own session title (`/rename`); null leaves an existing value
     * alone because most callers (bind, tab rename) do not know it, and a title is
     * never cleared - Claude keeps the last one too.
     */
    fun upsert(
        sessionId: String,
        project: String,
        tab: String?,
        cwd: String? = null,
        title: String? = null,
        dir: File = defaultDirectory()
    ) {
        if (!SessionIdValidator.isValid(sessionId)) return
        // Read-modify-write from pooled threads (a bind confirmation and a rename can
        // land together for one session); serialize within the process. Cross-process
        // overlap with the hook script is accepted (F6).
        synchronized(this) {
            upsertLocked(sessionId, project, tab, cwd, title, dir)
        }
    }

    private fun upsertLocked(sessionId: String, project: String, tab: String?, cwd: String?, title: String?, dir: File) {
        try {
            val file = File(dir, "${sessionId.lowercase()}.json")
            val existing = readObject(file)
            val fields = existing.toMutableMap()
            fields["session_id"] = JsonPrimitive(sessionId.lowercase())
            fields["project"] = JsonPrimitive(project)
            if (tab != null) fields["tab"] = JsonPrimitive(tab)
            if (!title.isNullOrBlank()) fields["title"] = JsonPrimitive(title)
            if (cwd != null && "cwd" !in fields) fields["cwd"] = JsonPrimitive(cwd)
            if (fields == existing) return
            fields["updated_at"] = JsonPrimitive(now())
            writeAtomically(file, JsonObject(fields))
        } catch (e: Exception) {
            logger.warn("Session registry upsert failed for $sessionId: ${e.message}")
        }
    }

    fun remove(sessionId: String, dir: File = defaultDirectory()) {
        if (!SessionIdValidator.isValid(sessionId)) return
        try {
            val file = File(dir, "${sessionId.lowercase()}.json")
            if (file.delete()) logger.info("Session registry: removed $sessionId")
        } catch (e: Exception) {
            logger.warn("Session registry remove failed for $sessionId: ${e.message}")
        }
    }

    fun removeAll(sessionIds: Collection<String>, dir: File = defaultDirectory()) {
        sessionIds.forEach { remove(it, dir) }
    }

    /**
     * What the registry knows about one session. [cwd], [state] and [pid] come from the
     * hook script, [project], [tab] and [title] from a plugin; any of them may be absent.
     */
    data class Entry(
        val sessionId: String,
        val cwd: String?,
        val state: String?,
        val pid: Long?,
        val project: String?,
        val tab: String?,
        val title: String? = null
    ) {
        /** True when the hook recorded a Claude process for this session and it is still running. */
        fun isLive(): Boolean = pid != null && isProcessAlive(pid)
    }

    /**
     * Reads the entry for [sessionId], or null when there is none or it is unreadable.
     *
     * This is the one read the plugin does, and it stays inside the addressing rule:
     * only a file named by a session id the caller already holds, never a scan of the
     * directory to discover other processes' sessions. Added after WebStorm resumed a
     * session IntelliJ IDEA was still running - the file already said so (`pid` alive,
     * `cwd` another project) and nothing looked.
     */
    fun read(sessionId: String, dir: File = defaultDirectory()): Entry? {
        if (!SessionIdValidator.isValid(sessionId)) return null
        val file = File(dir, "${sessionId.lowercase()}.json")
        if (!file.isFile) return null
        val obj = readObject(file)
        if (obj.isEmpty()) return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val pid = (obj["pid"] as? JsonPrimitive)?.content?.toLongOrNull()
        return Entry(
            sessionId = sessionId.lowercase(),
            cwd = str("cwd"),
            state = str("state"),
            pid = pid,
            project = str("project"),
            tab = str("tab"),
            title = str("title")
        )
    }

    /**
     * Deletes files older than [olderThanMs] (by modification time) whose recorded
     * process is no longer running - backstop for sessions whose SessionEnd never fired
     * (killed IDE). A file whose `pid` is alive is kept however old it is: the hook only
     * rewrites on events, so a session left idle for a day has an old file and a live
     * process, and deleting it would let [read] report "not running" to a resume that
     * then starts a second copy. Runs once per IDE process when called through
     * [sweepOnce]; can be re-run explicitly (hook reinstall).
     */
    fun sweep(olderThanMs: Long = SWEEP_AGE_MS, dir: File = defaultDirectory()): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return 0
        var removed = 0
        var keptLive = 0
        for (file in files) {
            try {
                if (file.lastModified() >= cutoff) continue
                val pid = (readObject(file)["pid"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (pid != null && isProcessAlive(pid)) {
                    keptLive++
                    continue
                }
                if (file.delete()) removed++
            } catch (e: Exception) {
                logger.debug("Sweep could not delete ${file.name}: ${e.message}")
            }
        }
        if (removed > 0 || keptLive > 0) {
            logger.info("Session registry: swept $removed stale file(s), kept $keptLive with a live process")
        }
        return removed
    }

    fun sweepOnce() {
        if (swept.compareAndSet(false, true)) sweep()
    }

    private fun readObject(file: File): Map<String, kotlinx.serialization.json.JsonElement> {
        if (!file.isFile) return emptyMap()
        return try {
            // Same trust level as current-session.json, same bounded read: the cap cannot
            // be raced by the file growing between a length check and the read.
            val bytes = file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }
            if (bytes.size > MAX_FILE_BYTES) {
                logger.warn("Session registry: ${file.name} exceeds $MAX_FILE_BYTES bytes, treating as unreadable")
                return emptyMap()
            }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            // A torn or foreign file counts as empty: upsert then replaces it (as the
            // hook does), read() reports nothing, sweep() sees no pid.
            logger.info("Session registry: ${file.name} is not a JSON object, treating as empty")
            emptyMap()
        }
    }

    private fun writeAtomically(file: File, content: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(json.encodeToString(JsonObject.serializer(), content))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

}
