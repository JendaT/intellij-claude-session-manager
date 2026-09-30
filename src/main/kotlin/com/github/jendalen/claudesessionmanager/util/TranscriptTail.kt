package com.github.jendalen.claudesessionmanager.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Bounded reads from the end of a Claude transcript (`<session>.jsonl`, often tens of MB).
 * Only complete lines are returned: a window that does not start at byte 0 begins
 * mid-line, and the last line may be one Claude is still writing.
 */
object TranscriptTail {

    const val DEFAULT_BYTES = 256L * 1024

    /** Complete lines of the last [bytes] bytes, oldest first; empty for a missing or empty file. */
    fun completeLines(file: File, bytes: Long = DEFAULT_BYTES): List<String> {
        if (!file.isFile) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return emptyList()
            val start = maxOf(0L, length - bytes)
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            var lines = String(buffer, Charsets.UTF_8).split('\n')
            // Byte offset, not decoded length: non-ASCII text makes them differ.
            if (start > 0L) lines = lines.drop(1)
            return lines.dropLast(1)
        }
    }

    private val cwdPattern = Regex("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * The `cwd` of the newest record in the window that has one: where the session last
     * ran. A resume from another directory keeps the session id and appends records with
     * the new cwd (verified 2026-09-30), so the first record's cwd is not the answer.
     */
    fun lastCwd(file: File, bytes: Long = DEFAULT_BYTES): String? {
        return try {
            for (line in completeLines(file, bytes).asReversed()) {
                val raw = cwdPattern.find(line)?.groupValues?.get(1) ?: continue
                val cwd = unescape(raw)
                if (cwd.isNotBlank()) return cwd
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun unescape(s: String): String =
        if (!s.contains('\\')) s
        else try {
            kotlinx.serialization.json.Json.parseToJsonElement("\"$s\"").let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            }
        } catch (e: Exception) {
            s
        }
}
