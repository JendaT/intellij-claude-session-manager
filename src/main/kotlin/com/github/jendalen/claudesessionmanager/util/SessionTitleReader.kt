package com.github.jendalen.claudesessionmanager.util

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the title a Claude session was given with `/rename` or `--name`.
 *
 * Claude Code appends `{"type":"custom-title","customTitle":"...","sessionId":"..."}` to
 * the transcript and re-emits it every assistant message (measured: every 15-25 lines),
 * and `/rename` also rewrites `<projects>/<slug>/<sessionId>/custom-title.json`. The CLI's
 * own reader takes the newest transcript record first and the sidecar second; this
 * reader mirrors that order so the plugin never disagrees with the `/resume` picker.
 * `sessions-index.json` is not consulted: no entry on the development machine ever
 * carried a title, and the index is unreliable for gating anyway (PRD D7).
 *
 * Pure file access, no IDE services, so it is unit tested with real files.
 */
object SessionTitleReader {

    private val logger = Logger.getInstance(SessionTitleReader::class.java)

    /**
     * Bytes read from the end of the transcript. One 12 MB transcript re-emitted the
     * record every ~2 KB, so a 256 KiB window is plenty unless a single tool result
     * line is larger than that - the sidecar covers the rest.
     */
    const val TAIL_BYTES = 256L * 1024

    private const val RECORD_MARKER = "\"type\":\"custom-title\""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class TitleRecord(val type: String? = null, val customTitle: String? = null)

    /** Cheap change detector: the transcript grows on every message, the sidecar on every `/rename`. */
    data class Fingerprint(val transcriptLength: Long, val sidecarModified: Long)

    /** `<projects>/<slug>/<sessionId>/custom-title.json` next to `<sessionId>.jsonl`. */
    fun sidecarFile(sessionFile: File): File =
        File(sessionFile.parentFile, sessionFile.name.removeSuffix(".jsonl") + "/custom-title.json")

    fun fingerprint(sessionFile: File): Fingerprint =
        Fingerprint(sessionFile.length(), sidecarFile(sessionFile).lastModified())

    /**
     * The session's current title, or null when it has none (or it cannot be read).
     * A blank title counts as none - Claude treats it that way too.
     */
    fun read(sessionFile: File): String? {
        val fromTail = readFromTail(sessionFile)
        if (fromTail != null) return fromTail.takeUnless { it.isBlank() }
        return readSidecar(sidecarFile(sessionFile))?.takeUnless { it.isBlank() }
    }

    /**
     * Newest `custom-title` record in the last [TAIL_BYTES] of the transcript. Returns
     * the raw value (possibly blank) so the caller can distinguish "renamed to nothing"
     * from "no record in the window"; null means no record.
     */
    private fun readFromTail(sessionFile: File): String? {
        if (!sessionFile.isFile) return null
        return try {
            val (start, tail) = readTail(sessionFile) ?: return null
            var lines = tail.split('\n')
            // A window that does not start at byte 0 begins mid-line; the last element is
            // whatever Claude is still writing. Neither is a complete record. (Byte
            // offset, not decoded length: non-ASCII text makes them differ.)
            if (start > 0L) lines = lines.drop(1)
            lines = lines.dropLast(1)
            for (line in lines.asReversed()) {
                if (!line.contains(RECORD_MARKER)) continue
                val record = try {
                    json.decodeFromString(TitleRecord.serializer(), line)
                } catch (e: Exception) {
                    continue
                }
                if (record.type == "custom-title") return record.customTitle ?: ""
            }
            null
        } catch (e: Exception) {
            logger.info("Could not read the title from ${sessionFile.name}: ${e.message}")
            null
        }
    }

    /** The last [TAIL_BYTES] of the file as (start byte offset, decoded text). */
    private fun readTail(file: File): Pair<Long, String>? {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return null
            val start = maxOf(0L, length - TAIL_BYTES)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            return start to String(bytes, Charsets.UTF_8)
        }
    }

    private fun readSidecar(file: File): String? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(TitleRecord.serializer(), file.readText()).customTitle
        } catch (e: Exception) {
            logger.info("Could not read ${file.path}: ${e.message}")
            null
        }
    }
}
