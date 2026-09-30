package com.github.jendalen.claudesessionmanager.util

import com.github.jendalen.claudesessionmanager.model.TabProfile
import java.io.File

/**
 * Pure rules for tab profiles: validation, lookup by tab name, and which directory a
 * tab's Claude sessions must run under. No IDE services, so every rule is unit tested.
 *
 * A tab is a profile tab while its persistent name (PRD D1) equals a profile's name
 * exactly; renaming the tab ends that and nothing else has to be undone.
 */
object TabProfiles {

    const val MAX_NAME_LENGTH = 100

    data class Validation(val errors: List<String>, val warnings: List<String>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /** `~` and `~/...` expanded against [home]; anything else returned trimmed. */
    fun expandHome(path: String, home: String = System.getProperty("user.home")): String {
        val trimmed = path.trim()
        return when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> home.trimEnd('/') + trimmed.substring(1)
            else -> trimmed
        }
    }

    /**
     * Why [name] cannot name a profile tab, or null. The name is compared with the tab's
     * persistent name, published to the registry and shown in menus; it never reaches a
     * shell.
     */
    fun nameRejection(name: String): String? {
        if (name.isBlank()) return "Tab name is blank"
        if (name != name.trim()) return "Tab name '$name' has leading or trailing spaces"
        if (name.length > MAX_NAME_LENGTH) return "Tab name '${name.take(20)}...' is longer than $MAX_NAME_LENGTH characters"
        if (name.any { it.isISOControl() }) return "Tab name contains a control character"
        return null
    }

    /**
     * Why [directory] cannot be a profile's working directory, or null. It is typed into
     * the shell as `cd '<dir>'`: inside single quotes only the quote itself is special in
     * sh, bash and zsh, and fish also honours a backslash; control characters would
     * submit half a command.
     */
    fun directoryRejection(directory: String, home: String = System.getProperty("user.home")): String? {
        if (directory.isBlank()) return "Working directory is blank"
        val expanded = expandHome(directory, home)
        if (!expanded.startsWith("/")) return "Working directory '$directory' is not an absolute path (or ~/...)"
        if (expanded.any { it == '\'' }) return "Working directory '$directory' contains a single quote"
        if (expanded.any { it == '\\' }) return "Working directory '$directory' contains a backslash"
        if (expanded.any { it.isISOControl() }) return "Working directory contains a control character"
        return null
    }

    /** `'<expanded dir>'` for a directory that passed [directoryRejection]. */
    fun shellQuotedDirectory(directory: String, home: String = System.getProperty("user.home")): String {
        require(directoryRejection(directory, home) == null) { "Invalid working directory: ${directoryRejection(directory, home)}" }
        return "'${expandHome(directory, home).trimEnd('/').ifEmpty { "/" }}'"
    }

    /**
     * Errors refuse the save (and make [usable] drop the profile on read); warnings are
     * shown and saved - a worktree may be created after its profile.
     */
    fun validate(profiles: List<TabProfile>, home: String = System.getProperty("user.home")): Validation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seenNames = mutableSetOf<String>()
        val dirOwners = mutableMapOf<String, String>()
        for (profile in profiles) {
            val label = profile.name.ifBlank { "(unnamed)" }
            nameRejection(profile.name)?.let { errors += it }
            if (profile.name.isNotBlank() && !seenNames.add(profile.name)) {
                errors += "Tab name '${profile.name}' is used by more than one profile"
            }
            val dirError = directoryRejection(profile.workingDirectory, home)
            if (dirError != null) {
                errors += "$label: $dirError"
            } else {
                val dir = File(expandHome(profile.workingDirectory, home))
                when {
                    !dir.isDirectory -> warnings += "$label: directory ${dir.path} does not exist"
                    !isInsideGitWorkTree(dir) -> warnings += "$label: ${dir.path} is not inside a git work tree"
                }
                val key = dir.path.trimEnd('/')
                val other = dirOwners.putIfAbsent(key, label)
                if (other != null) warnings += "$label and $other use the same directory"
            }
            LaunchArgsValidator.rejectionReason(profile.launchArgs)?.let { errors += "$label: $it" }
        }
        return Validation(errors, warnings)
    }

    /**
     * The profiles that pass every per-profile error check, first one wins on a duplicate
     * name. Applied on read because the list is loaded from `workspace.xml`, which a
     * cloned repository can carry.
     */
    fun usable(profiles: List<TabProfile>, home: String = System.getProperty("user.home")): List<TabProfile> {
        val seen = mutableSetOf<String>()
        return profiles.filter { p ->
            nameRejection(p.name) == null &&
                directoryRejection(p.workingDirectory, home) == null &&
                LaunchArgsValidator.isValid(p.launchArgs) &&
                seen.add(p.name)
        }
    }

    /** The profile whose name is exactly [tabName], or null. */
    fun find(profiles: List<TabProfile>, tabName: String?): TabProfile? =
        if (tabName == null) null else profiles.firstOrNull { it.name == tabName }

    /**
     * The directory this tab's sessions must run under: the profile's directory, else
     * the project folder. With no profiles this is `project.basePath` for every tab,
     * which is exactly the check the detection channels made before profiles existed.
     */
    fun rootFor(basePath: String?, profile: TabProfile?, home: String = System.getProperty("user.home")): String? =
        profile?.let { expandHome(it.workingDirectory, home) } ?: basePath

    /**
     * True when [cwd] is [root] or below it (directory-boundary aware). Also compares the
     * canonical forms: Claude reports the physical path (`getcwd`), while a configured
     * directory may go through a symlink (`/tmp` -> `/private/tmp` on macOS).
     */
    fun isUnder(root: String, cwd: String): Boolean {
        if (ClaudePathResolver.isPathMatch(root, cwd)) return true
        val canonicalRoot = canonical(root) ?: return false
        val canonicalCwd = canonical(cwd) ?: return false
        return ClaudePathResolver.isPathMatch(canonicalRoot, canonicalCwd)
    }

    /** Project-level filter: the project folder or any profile directory. */
    fun isUnderAnyRoot(basePath: String?, profiles: List<TabProfile>, cwd: String, home: String = System.getProperty("user.home")): Boolean {
        val roots = listOfNotNull(basePath) + profiles.map { expandHome(it.workingDirectory, home) }
        return roots.any { isUnder(it, cwd) }
    }

    /** A `.git` file (linked worktree) or directory (main clone) in [dir] or an ancestor. */
    fun isInsideGitWorkTree(dir: File): Boolean {
        var current: File? = dir.absoluteFile
        while (current != null) {
            if (File(current, ".git").exists()) return true
            current = current.parentFile
        }
        return false
    }

    private fun canonical(path: String): String? =
        try {
            File(path).canonicalPath
        } catch (e: Exception) {
            null
        }
}
