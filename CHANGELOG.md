# Changelog

All notable changes to Claude Session Manager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
