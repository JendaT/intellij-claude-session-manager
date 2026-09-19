package com.github.jendalen.claudesessionmanager.util

/**
 * Assembles the shell commands the plugin types into terminals. Pure functions over
 * already-validated inputs so the assembly can be unit tested without the IDE; the
 * callers in TerminalHelper supply the configured command and the project's extra args.
 */
object LaunchCommand {

    /**
     * `claude [extraArgs] --resume <sessionId> [--name '<name>']`. The name is a tab name
     * the user gave in the IDE while the session could not be typed into; Claude writes
     * it as the session's title on start, which is how a pending rename catches up.
     */
    fun resume(claudeCommand: String, extraArgs: String, sessionId: String, name: String? = null): String {
        // SECURITY: every input reaches a shell. The session id must be a UUID, the
        // extra args must pass the allowlist and the name must be single-quotable -
        // callers re-validate on read, this is the last line.
        require(SessionIdValidator.isValid(sessionId)) { "Invalid session ID format: $sessionId" }
        val rest = if (name != null) arrayOf("--resume $sessionId", "--name ${SessionNameValidator.quoted(name)}")
        else arrayOf("--resume $sessionId")
        return join(claudeCommand, extraArgs, *rest)
    }

    /** `claude [extraArgs]` - a fresh session. */
    fun start(claudeCommand: String, extraArgs: String): String = join(claudeCommand, extraArgs)

    private fun join(claudeCommand: String, extraArgs: String, vararg rest: String): String {
        val args = LaunchArgsValidator.normalize(extraArgs)
        require(args != null) { "Extra launch args rejected by validator" }
        return (listOf(claudeCommand.trim(), args) + rest).filter { it.isNotBlank() }.joinToString(" ")
    }
}
