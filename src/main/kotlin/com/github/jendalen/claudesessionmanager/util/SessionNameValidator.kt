package com.github.jendalen.claudesessionmanager.util

/**
 * Validates a tab name before the plugin hands it to Claude as a session name - typed
 * as `/rename <name>` into the session, or appended as `--name '<name>'` to a resume
 * command that goes through a shell.
 *
 * The shell case sets the rules: inside single quotes nothing is special in sh, bash
 * and zsh except the quote itself, and fish additionally honours a backslash. Control
 * characters are rejected because a CR or LF would submit half a command. The typed
 * `/rename` is not quoted at all, and the plugin cannot prove the tab still runs Claude
 * rather than a shell prompt (the registry only proves the session runs somewhere), so
 * the characters that would chain or run a command at a shell - `; | & $ < >` and the
 * backtick - are rejected too; a stray `/rename foo (2)` at a prompt is then at worst a
 * parse error. The name is what the user typed into the IDE's rename dialog, so a
 * rejection is reported, not repaired.
 */
object SessionNameValidator {

    const val MAX_LENGTH = 200

    private const val SHELL_CONTROL = ";|&$<>`"

    fun rejectionReason(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name is blank"
        if (trimmed.length > MAX_LENGTH) return "Name is longer than $MAX_LENGTH characters"
        if (trimmed.any { it == '\'' }) return "Name contains a single quote"
        if (trimmed.any { it == '\\' }) return "Name contains a backslash"
        if (trimmed.any { it.isISOControl() }) return "Name contains a control character"
        trimmed.firstOrNull { it in SHELL_CONTROL }?.let { return "Name contains '$it', which a shell would act on" }
        return null
    }

    fun isValid(name: String): Boolean = rejectionReason(name) == null

    /** `'<name>'` - only valid for names that pass [isValid]. */
    fun quoted(name: String): String {
        require(isValid(name)) { "Invalid session name: ${rejectionReason(name)}" }
        return "'${name.trim()}'"
    }
}
