package com.github.jendalen.claudesessionmanager.util

/**
 * Assembles the shell commands the plugin types into terminals. Pure functions over
 * already-validated inputs so the assembly can be unit tested without the IDE; the
 * callers in TerminalHelper supply the configured command and the project's extra args.
 */
object LaunchCommand {

    /**
     * `claude [extraArgs] --resume <sessionId> [--name '<name>']`, prefixed with
     * `cd '<dir>' && ` for a tab profile's [workingDirectory]. The name is a tab name the
     * user gave in the IDE while the session could not be typed into; Claude writes it as
     * the session's title on start, which is how a pending rename catches up.
     */
    fun resume(
        claudeCommand: String,
        extraArgs: String,
        sessionId: String,
        name: String? = null,
        workingDirectory: String? = null
    ): String {
        // SECURITY: every input reaches a shell. The session id must be a UUID, the
        // extra args must pass the allowlist, the name and the directory must be
        // single-quotable - callers re-validate on read, this is the last line.
        require(SessionIdValidator.isValid(sessionId)) { "Invalid session ID format: $sessionId" }
        val rest = if (name != null) arrayOf("--resume $sessionId", "--name ${SessionNameValidator.quoted(name)}")
        else arrayOf("--resume $sessionId")
        return inDirectory(workingDirectory, join(claudeCommand, extraArgs, *rest))
    }

    /** `claude [extraArgs]` - a fresh session, in [workingDirectory] when given. */
    fun start(claudeCommand: String, extraArgs: String, workingDirectory: String? = null): String =
        inDirectory(workingDirectory, join(claudeCommand, extraArgs))

    /**
     * A profile tab's command always changes directory first, even when the shell already
     * starts there: a restored tab can come back in another directory, and the user may
     * have `cd`-ed away. `&&` so that a missing directory runs nothing - Claude started in
     * the wrong directory would publish the wrong cwd to the registry.
     */
    private fun inDirectory(workingDirectory: String?, command: String): String =
        if (workingDirectory == null) command
        else "cd ${TabProfiles.shellQuotedDirectory(workingDirectory)} && $command"

    private fun join(claudeCommand: String, extraArgs: String, vararg rest: String): String {
        val args = LaunchArgsValidator.normalize(extraArgs)
        require(args != null) { "Extra launch args rejected by validator" }
        return (listOf(claudeCommand.trim(), args) + rest).filter { it.isNotBlank() }.joinToString(" ")
    }
}
