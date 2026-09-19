package com.github.jendalen.claudesessionmanager.util

/**
 * Assembles the shell commands the plugin types into terminals. Pure functions over
 * already-validated inputs so the assembly can be unit tested without the IDE; the
 * callers in TerminalHelper supply the configured command and the project's extra args.
 */
object LaunchCommand {

    /** `claude [extraArgs] --resume <sessionId>` */
    fun resume(claudeCommand: String, extraArgs: String, sessionId: String): String {
        // SECURITY: both inputs reach a shell. The session id must be a UUID and the
        // extra args must pass the allowlist - callers re-validate on read, this is the
        // last line.
        require(SessionIdValidator.isValid(sessionId)) { "Invalid session ID format: $sessionId" }
        return join(claudeCommand, extraArgs, "--resume $sessionId")
    }

    /** `claude [extraArgs]` - a fresh session. */
    fun start(claudeCommand: String, extraArgs: String): String = join(claudeCommand, extraArgs)

    private fun join(claudeCommand: String, extraArgs: String, vararg rest: String): String {
        val args = LaunchArgsValidator.normalize(extraArgs)
        require(args != null) { "Extra launch args rejected by validator" }
        return (listOf(claudeCommand.trim(), args) + rest).filter { it.isNotBlank() }.joinToString(" ")
    }
}
