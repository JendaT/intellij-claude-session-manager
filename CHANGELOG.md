# Changelog

All notable changes to Claude Session Manager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2026-09-30

### Added
- Tab profiles (Settings > Tools > Claude Session Manager > Launch Profile, per project):
  a terminal tab named exactly as a profile starts Claude in the profile's working
  directory - for example a git worktree outside the project folder - with the profile's
  launch arguments (blank: the project default):
  `cd '<dir>' && claude <arguments> [--resume <id>]`. Other tabs are unchanged.
- "Open Profile Tabs" (Tools menu and terminal tab menu), an "Open Profile Tab" submenu,
  and "Claude: <profile>" entries in the terminal's new-tab dropdown. A new profile tab
  resumes the last session of that tab name when it ran in the profile's directory,
  otherwise starts a new session there.
- "Keep open" profile tabs are reopened at project open (with auto-resume on).
- The page warns when a profile's directory is missing or not inside a git work tree and
  refuses duplicate tab names. Renaming a profile tab makes it an ordinary tab.

### Changed
- Every detection channel checks a session's working directory against the tab it would
  bind to: the profile directory for a profile tab, the project folder otherwise. A
  SessionStart whose directory belongs to exactly one profile is bound to that profile's
  tab rather than the selected tab. Projects without profiles behave as before.
- Registry files of profile-tab sessions carry the profile name as `tab` and the
  worktree as `cwd` (the hook script is unchanged).
- Inbound title sync does not rename profile tabs.

### Known limitations
- A tab that is already open when its profile is created keeps running what it ran; the
  plugin says so and never types into an open tab. Close it and open the profile tab.
- "Bind to Session..." on a profile tab lists the project folder's sessions.

## [0.8.0] - 2026-09-19

### Added
- Session title sync, both directions. A session renamed with `/rename` (or started with
  `--name`) renames its bound tab within a few seconds; the name is the tab's persistent
  name, so it survives IDE restarts. A tab renamed in the IDE renames its Claude session:
  `/rename <name>` is typed into the session while it is idle, and a session that is busy,
  waiting on a permission prompt or not running gets the name at its next auto-resume
  (`--name`) or through the new tab-menu action "Push Tab Name to Claude Session".
  A title the session already had when the plugin first sees it renames only a tab
  that still has a default name ("Local", "Local (2)"); tabs you named keep their names
  until the next `/rename`.
  The "Sync Claude /rename to terminal tab name" checkbox, present since 0.1.0, now does
  something; "Push IDE tab renames to the Claude session" is new (both default on).
- Registry files carry `title`: the session's Claude-side title, next to the IDE `tab`.

### Changed
- The launch profile rejects `--name` and `-n` (also inside combined short flags such as
  `-dn`): session naming belongs to the plugin, like session selection.

### Known limitations
- A `/rename` typed into a session whose prompt box holds an unsent draft submits the
  draft together with the command. The push happens only right after an IDE rename of
  an idle session; turn off "Push IDE tab renames" to avoid it entirely.
- The title is read from the transcript's last 256 KiB and the `custom-title.json`
  sidecar; a session whose only record is older than that window and that was never
  renamed interactively shows no title until it writes the next record.
- A tab name containing `'`, `\`, `;`, `|`, `&`, `$`, `<`, `>` or a backtick is not
  pushed (a balloon says why): `/rename` is typed unquoted and the plugin cannot prove
  the tab still runs Claude rather than a shell prompt.

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
- A session already running in another IDE or terminal is no longer resumed a second
  time. Bindings are per project per IDE, so a stale binding elsewhere used to start a
  second process on the same transcript; auto-resume now reads the session's registry
  file first and leaves the tab alone when its `pid` is alive. The 24 h registry sweep
  likewise keeps files whose process is alive, however old.
- The terminal-output channel no longer binds a tab to any session id that merely
  appears in its output. Printing another session's id (`cat` of a registry file, a log
  grep, an orchestrator listing its workers) used to rebind the tab and then write that
  tab into the wrong session's registry file. The channel now checks the session's
  working directory - from the index, the registry, or the transcript itself - against
  the project, the same guard the hook channel already had.
- Registry `project`/`tab` are published for a session whose only binding confirmation
  lands inside the auto-resume suppression window; previously that path returned early
  and the fields stayed absent.
- Project close no longer deletes registry files of sessions that are live in another IDE
  or terminal: only files this project published are removed, and never one whose process
  is alive elsewhere. Deleting by binding id took away the double-resume guard's only input.
- Auto-resume no longer resumes the same transcript twice when two bindings' histories
  share it (the fallback id was added to the handled set without checking the result).
- The hook-event watcher seeds its watermark from the event already on disk, so the last
  SessionStart on the machine is not replayed into every project that opens.
- The launch profile rejects flags that load files, run commands at launch or change
  permissions (`--mcp-config`, `--settings`, `--permission-mode`,
  `--dangerously-skip-permissions`, `--allowedTools`, `--append-system-prompt-file`,
  `--add-dir`, `--plugin-dir`) and combined short flags carrying `-r`/`-c`. The value is
  stored in `.idea/workspace.xml` inside the checkout; a cloned repository must not be able
  to ship one that alters what Claude may do.
- Two cross-model review passes (2026-09-19): notification and tooltip text built from
  OSC-settable tab names goes through the HTML-escaping choke point; hook-script ownership
  is matched on the exact command and revert removes only our hook from a shared entry; the
  hook script is installed atomically, never through a symlink, with an explicit mode; the
  session-history resume uses the stable tab id; the terminal-output scan no longer
  re-filters and re-sorts the session index on the EDT per output chunk; detection
  failures log at WARN instead of DEBUG; and a set of leaks, swallowed exceptions and dead
  code across the services.

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
