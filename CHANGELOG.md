# Changelog

All notable changes to Claude Session Manager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-09-17

### Added
- Per-project launch profile (Settings > Tools > Claude Session Manager > Launch Profile):
  extra arguments inserted on every launch in that project - auto-resume, context-menu
  resume, session history, new Claude tabs. Validated against a strict allowlist; session
  selection flags are rejected.
- Session registry for external tools: the hook script maintains
  `~/.claude/ide-events/sessions/<session_id>.json` with `state` (`idle` / `busy` /
  `waiting` on a permission prompt), `cwd`, `pid`, `updated_at`, and the IDE `project` and
  `tab` the plugin bound the session to. Files are written atomically, removed on
  SessionEnd and on project close, swept after 24 h.
- Hook installer registers the script for SessionStart, UserPromptSubmit, PreToolUse,
  Notification (permission_prompt), Stop and SessionEnd, merging next to other tools'
  entries. The settings page reports an outdated script and offers the upgrade.
- `docs/` (PRD, roadmap, plans, runbooks) and `scripts/audit-bindings.py`.

### Fixed
- After an IDE restart every bound tab launches Claude, not only the selected tab of each
  project. The reworked terminal starts restored tabs' shells lazily on first display and
  buffers sent text until then; the plugin now shows each restored tab once.

### Removed
- The experimental Agent Teams module (dashboard, teammate tabs, settings page, actions).
- The `sessions.jsonl` append in the hook script (never pruned, never read).

## [0.6.6] - 2026-08-26

### Fixed
- A tab that reuses an existing binding (reopened terminal matched by name, or a session
  change in an already bound tab) now gets the Claude icon and tab-title protection too.

## [0.6.5] - 2026-08-05

### Fixed
- Hook events can no longer move a session away from the live tab that owns it
  (multi-project resume storms fired each tab's SessionStart event at the selected tab).
- Duplicate-session cleanup reverts the losing binding to its own history instead of
  deleting it.

## [0.6.4] - 2026-08-05

### Fixed
- Resume falls back through the binding's session history to the newest id with a
  transcript instead of reporting an unpersisted current id as purged.

## [0.6.3] - 2026-08-05

### Fixed
- "Start new session" binds the new session even when clicked during the startup
  suppression window.
- Title tracking flag resolved reflectively (removed from the platform in 2026.2).

## [0.6.2] - 2026-08-04

### Added
- Claude's live progress surfaced as an activity badge on the tab icon and the tab
  tooltip; the tab keeps its name and colored icon.

### Fixed
- Duplicate bindings no longer send the resume command twice on startup.
- Purged sessions are not blindly resumed; a notification offers a fresh session or
  removing the binding.

## [0.6.1] - 2026-08-04

### Fixed
- "Protect bound tab names" works on the reworked terminal (2026.1).

## [0.6.0] - 2026-08-04

### Added
- Restart-stable tab identity via the terminal-persisted tab name; auto-resume waits for
  tab restoration; "Protect bound tab names" setting.

## [0.5.0] - 2026-06-15

### Fixed
- **Tab matching survives Claude Code's terminal-title takeover.** Recent JetBrains builds honor
  the OSC escape sequence Claude Code emits to rename terminal tabs (commonly to "Claude Code"),
  which collapsed distinct tabs to the same name and broke name-based binding matching - causing
  lost bindings and cross-tab contamination. Bindings are now keyed on a stable per-tab id stored
  in the tab's `Content` UserData, independent of the displayed name.

### Added
- One-time, idempotent migration that attaches stable ids to existing (name-only) bindings by
  matching them to currently-open tabs. Unmatched bindings keep working via name fallback and are
  retried on later startups.
- "Keep my custom tab names" setting that toggles `CLAUDE_CODE_DISABLE_TERMINAL_TITLE` in
  `~/.claude/settings.json`, disabling Claude Code's tab renaming for users who prefer their own
  tab names. Session binding works whether the renaming is on or off.

## [0.2.0] - 2025-02-06

### Added
- **Claude Code Integration Manager** - Install and manage required Claude hooks directly from settings
  - Shows integration status (installed/not installed)
  - One-click hook installation with transparency about what gets modified
  - Ability to revert hooks to original state
  - Displays exact hook content for security review
- Context menu item "Settings..." with "(action required)" indicator when hooks not configured
- CHANGELOG.md for tracking version history
- Release scripts for GitHub releases

### Changed
- Improved coroutine lifecycle management with SupervisorJob
- Replaced raw Thread usage with kotlinx.coroutines for retry logic
- Added @Volatile to shared mutable state for thread safety
- Extracted magic numbers to named constants

### Fixed
- Memory leaks from improperly disposed Alarm instances
- Potential crash from malformed date parsing in session metadata
- Thread safety issues in cache state management

## [0.1.0] - 2025-02-04

### Added
- Initial release
- Bind Claude sessions to terminal tabs
- Auto-resume sessions on IDE restart
- Track session changes from `/clear` and `/compact`
- Session history per tab with restore capability
- Auto-detection of Claude sessions via file watching and terminal output monitoring
- Claude Code hook integration for reliable session detection
- Settings page under Tools menu
- Terminal tab context menu integration
- Predefined terminal session provider ("Claude Session" in new terminal dropdown)
