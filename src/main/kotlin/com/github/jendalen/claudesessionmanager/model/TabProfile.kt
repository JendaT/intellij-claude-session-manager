package com.github.jendalen.claudesessionmanager.model

/**
 * A named terminal tab that runs Claude in its own working directory with its own
 * launch arguments - a proxy for a git worktree outside the project folder, so a
 * worktree does not need an IDE project of its own.
 *
 * [workingDirectory] is kept as the user typed it (`~` allowed); expansion happens at
 * use. Blank [launchArgs] means the project default (`extraLaunchArgs`).
 */
data class TabProfile(
    val name: String,
    val workingDirectory: String,
    val launchArgs: String = "",
    val keepOpen: Boolean = false
)
