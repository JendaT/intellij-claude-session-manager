package com.github.jendalen.claudesessionmanager.util

/**
 * Validates the per-project extra launch arguments that are inserted verbatim into the
 * shell command the plugin types into a terminal (`claude <extraArgs> --resume <id>`).
 *
 * The value ends up in a shell, so the allowlist is deliberately narrow: no quoting, no
 * expansion, no command separators or redirections. It is wide enough for the real use
 * (`--channels plugin:<name>@<marketplace>`), which is why `:` and `@` are allowed here
 * although the global `claudeCommand` allowlist rejects them.
 *
 * Session selection stays with the plugin: flags that would pick or fork a session are
 * rejected as whole tokens, so extra args can never redirect which session is resumed.
 */
object LaunchArgsValidator {

    private val ALLOWED = Regex("[A-Za-z0-9_.:=/ ,@-]*")

    private val RESERVED_FLAGS = setOf("--resume", "-r", "--continue", "-c", "--session-id")

    private val COMBINED_SHORT_RESERVED = Regex("^-[a-zA-Z]*[rc][a-zA-Z]*$")

    /**
     * Returns the normalised value (trimmed, runs of spaces collapsed; empty for blank
     * input) or null when the input is rejected.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return ""
        if (raw.any { it == '\n' || it == '\r' || it == '\t' }) return null
        val collapsed = raw.trim().replace(Regex(" {2,}"), " ")
        if (collapsed.isEmpty()) return ""
        if (!ALLOWED.matches(collapsed)) return null
        val reserved = collapsed.split(' ').any { token ->
            token in RESERVED_FLAGS || token.substringBefore('=') in RESERVED_FLAGS ||
                // Combined short flags: `-cd` is `--continue --debug`, `-vr` carries
                // `--resume`. Anything single-dash containing r or c selects a session.
                COMBINED_SHORT_RESERVED.matches(token)
        }
        return if (reserved) null else collapsed
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** Human-readable reason for the settings page; null when the value is accepted. */
    fun rejectionReason(raw: String): String? {
        if (normalize(raw) != null) return null
        if (raw.any { it == '\n' || it == '\r' || it == '\t' }) return "Must be a single line"
        val offending = raw.trim().firstOrNull { !ALLOWED.matches(it.toString()) }
        if (offending != null) return "Character '$offending' is not allowed (letters, digits, and _ . : = / , @ - only)"
        return "Session selection flags (--resume, -r, --continue, -c, --session-id) are managed by the plugin"
    }
}
