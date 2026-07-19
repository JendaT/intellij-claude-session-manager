package com.github.jendalen.claudesessionmanager.model

import kotlinx.serialization.Serializable

/**
 * Composite identifier for terminal tabs.
 *
 * PRIMARY identity is [tabUuid] - a plugin-owned UUID stamped into the terminal tab's
 * Content via UserData (see TerminalHelper.getOrCreateTabId). It is stable for the life
 * of the Content object, surviving tab renames - including the OSC-0 title takeover that
 * Claude Code performs (which JetBrains terminals honor on recent builds, overwriting the
 * tab's displayName to "Claude Code"). Matching on the volatile [name] alone caused lost
 * bindings and cross-tab collisions once every Claude tab was renamed to the same title.
 *
 * The Content (and therefore the UserData UUID) does NOT survive an IDE restart. Persisted
 * bindings carry the last-known [tabUuid] but it won't match freshly-created Content after
 * restart. Cross-restart identity relies on [persistentName]: the name the terminal plugin
 * itself persists per tab (TerminalTitle.userDefinedTitle ?: defaultTitle). Unlike the
 * volatile displayName - which Claude Code overwrites via OSC-0 ("Claude Code", "* Doing X")
 * - the persistent name is never polluted by the application title, and restored tabs come
 * back carrying exactly this name. BindingMigrationService re-attaches bindings to restored
 * tabs by persistent name and re-stamps fresh UUIDs on first run after restart.
 *
 * Matching strategy (see [matchConfidence]):
 * 1. Both have tabUuid and they match           -> 1.0 (authoritative)
 * 2. Both have tabUuid and they differ          -> 0.0 (definitely different tabs)
 * 3. tabUuid missing on either side             -> fall back to persistent-name/time/cwd scoring
 */
@Serializable
data class TabIdentifier(
    val name: String,
    val createdAt: Long,           // Epoch millis when tab was created
    val shellPid: Long? = null,    // Shell process ID (optional)
    val workingDirectory: String? = null,  // Initial CWD (optional)
    val tabUuid: String? = null,   // Stable per-tab UUID (Content UserData); primary identity
    val persistentName: String? = null  // Terminal-persisted tab name; survives IDE restart
) {
    /**
     * The name to use for cross-restart matching: the terminal-persisted name when known,
     * else the (possibly volatile) display name snapshot.
     */
    val effectiveName: String
        get() = persistentName ?: name
    /**
     * Calculates match confidence against another TabIdentifier.
     * Returns value between 0.0 and 1.0.
     *
     * The stable [tabUuid] takes absolute precedence: when both sides carry one it is the
     * sole decider (equal -> 1.0, different -> 0.0). Name/timestamp/cwd scoring is only used
     * as a fallback when at least one side has no UUID (legacy bindings, cross-restart).
     */
    fun matchConfidence(other: TabIdentifier): Double {
        if (this == other) return 1.0

        // Stable UUID is authoritative when present on both sides.
        if (tabUuid != null && other.tabUuid != null) {
            return if (tabUuid == other.tabUuid) 1.0 else 0.0
        }

        // Fallback: persistent name must match (case-sensitive). Uses the terminal-persisted
        // name when available so Claude Code's OSC display-name takeover cannot break matching.
        if (effectiveName != other.effectiveName) {
            return 0.0
        }

        // Name matches - this is sufficient for basic matching (0.7 base score)
        var score = 0.7

        // Bonus for timestamp match (useful within same session)
        val timeDelta = kotlin.math.abs(createdAt - other.createdAt)
        if (timeDelta < 1000) {
            score += 0.2
        } else if (timeDelta < 5000) {
            score += 0.1
        }

        // Shell PID match bonus
        if (shellPid != null && other.shellPid != null && shellPid == other.shellPid) {
            score += 0.05
        }

        // Working directory match bonus
        if (workingDirectory != null && other.workingDirectory != null &&
            workingDirectory == other.workingDirectory) {
            score += 0.05
        }

        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.8
        const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5
    }
}
