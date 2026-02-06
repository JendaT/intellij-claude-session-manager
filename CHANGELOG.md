# Changelog

All notable changes to Claude Session Manager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
