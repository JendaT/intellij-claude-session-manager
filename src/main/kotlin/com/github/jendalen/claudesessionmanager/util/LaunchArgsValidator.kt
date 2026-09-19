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
 * Session selection and naming stay with the plugin: flags that would pick or fork a
 * session, or name it, are rejected as whole tokens, so extra args can never redirect
 * which session is resumed or override a tab-name push.
 */
object LaunchArgsValidator {

    private val ALLOWED = Regex("[A-Za-z0-9_.:=/ ,@-]*")

    private val RESERVED_FLAGS = setOf(
        // Session selection and naming stay with the plugin (the plugin appends --name
        // itself when a tab rename is pending).
        "--resume", "-r", "--continue", "-c", "--session-id", "--name", "-n",
        // The value is read from .idea/workspace.xml inside the checkout, and nothing
        // forces that file to be gitignored. These flags load a file, run a command at
        // launch or widen what Claude may do without asking - a cloned repository must
        // not be able to ship them. The launch profile exists for --channels.
        "--mcp-config", "--strict-mcp-config", "--settings", "--permission-mode",
        "--dangerously-skip-permissions", "--allowedTools", "--allowed-tools",
        "--disallowedTools", "--disallowed-tools", "--append-system-prompt-file",
        "--system-prompt-file", "--add-dir", "--plugin-dir"
    )

    private val COMBINED_SHORT_RESERVED = Regex("^-[a-zA-Z]*[rcn][a-zA-Z]*$")

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
                // `--resume`, `-dn` carries `--name`. Anything single-dash containing
                // r, c or n selects or names a session.
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
        return "Session selection and naming flags are managed by the plugin, and flags that load files or change permissions " +
            "(--mcp-config, --settings, --permission-mode, --dangerously-skip-permissions, --allowedTools, " +
            "--append-system-prompt-file, --add-dir) are not allowed in a launch profile"
    }
}
