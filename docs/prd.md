---
type: prd
reflects_version: 0.7.0
last_updated: 2026-09-17
---

# Claude Session Manager - product and design reference

Canonical description of the shipped plugin. Written 2026-09-17 against v0.6.6
(`cad8f97`) by reading the code and the restart logs of the 2026-08/09 stabilisation
work, then revised for v0.7.0 (external session control, same day; sections 4, 5, 7, 8
and 10). Earlier history is in `CHANGELOG.md` and the `plugin.xml` change-notes.

## 1. Purpose

A JetBrains terminal tab is where a Claude Code session lives. The plugin makes that
relationship durable: each tab remembers its session, the session is resumed in the same
tab after an IDE restart, and session changes (`/clear`, `/compact`, `--resume`) follow
the tab. It is the only component on the machine that knows which tab holds which
session; everything else (Claude Code itself, external tooling) sees only session ids and
working directories.

Scope: session binding, auto-resume, session history per tab, tab decoration (icon,
title protection, live progress badge), Claude Code hook installation, a per-project
launch profile, and a file-based session registry (which session is alive in which
directory and tab, and whether it is idle) that external tooling can read. Out of scope:
anything that talks to Claude Code over a network, orchestrating prompts, queues or
inboxes, reading transcripts for content, or naming any particular orchestrator.

## 2. Decisions log (binding)

Each rule below was paid for by a live failure. Overriding one needs a dated record in
`docs/decisions/`.

- **D1 Identity is two-tier.** Runtime identity is a plugin-owned UUID in the tab
  `Content` UserData (`STABLE_TAB_KEY`); it dies with the IDE process. Restart-stable
  identity is the *terminal-persisted* tab name (`TerminalTitle.userDefinedTitle ?:
  defaultTitle`), never the displayed name. Claude Code rewrites the display name via
  OSC-0 ("Claude Code", "* Doing X"), which collapsed every tab to the same name and
  cross-contaminated bindings (v0.4 and earlier).
- **D2 One write path.** `TabBindingCoordinator.bindOrUpdate` is the only code that
  creates, adopts or updates a binding. Three services once carried drifting copies;
  the unmigrated one caused cross-tab contamination.
- **D3 Hook events never move a session off a live tab.** `SessionStart` events carry
  `session_id` and `cwd` but no terminal identity; they are attributed to the selected
  tab. During a resume storm (~20 sessions across 6 projects) events arrive 60-90 s after
  launch and landed on whichever tab was focused. Only the tab-accurate output channel or
  an explicit user action may move a session (v0.6.5).
- **D4 Never treat "current id has no transcript" as purged.** Fresh sessions write their
  `.jsonl` only after the first user message, and misattributed startup events plant
  phantom ids. Resume falls back through the binding's session history to the newest id
  that has a transcript (v0.6.4). `claude --resume X` reports X itself in the hook.
- **D5 Duplicate bindings for one session are reverted, not deleted.** The loser keeps
  its tab and is reverted to its newest history session with a transcript and no other
  owner; only then deleted. Deleting lost a tab's whole history once.
- **D6 Suppression window, with a user-action bypass.** For 15 s after auto-resume
  starts, bindings are neither updated nor adopted. Explicit user actions
  (`expectFreshSession`) pre-authorise the next fresh session on their tab through the
  window - the "Start new session" click 8 s after startup was otherwise swallowed
  (v0.6.3).
- **D7 Do not gate on `sessions-index.json`.** Many sessions never appear there (after
  `/clear`, `/compact`) or appear minutes later. Bindings are created from the hook event
  or the transcript file existing; the index only enriches notifications.
- **D8 Do not auto-rebind to the "most recent session".** When a bound session vanishes
  from the index, leave the binding alone.
- **D9 `ToolWindow.isVisible` is not a gate.** It is false whenever another tool window is
  active; `contentManager.selectedContent` is still the user's last terminal.
- **D10 Guard clauses log at INFO.** DEBUG lines are absent from production logs.
- **D11 The plugin's own icon wins.** Tab decoration keeps the colored Claude icon and
  strips the IDE's application-title asterisk; Claude's live status is surfaced as a
  live-indicator badge plus the tab tooltip instead (v0.6.2).
- **D12 Plugin Verifier is a release gate.** 262 removed
  `TerminalTitle.State.trackTerminalApplicationTitleChanges`; it is resolved reflectively
  and every release verifies against 261 and 262.
- **D13 Files written outside the project** are limited to
  `~/.claude/hooks/session-notify.sh`, hook entries and `env` in `~/.claude/settings.json`
  (merged, never overwritten) and `~/.claude/ide-events/`.
- **D14 The plugin owns session selection and session naming.** Per-project extra
  launch args are inserted between the command and `--resume <id>`; the validator
  rejects `--resume`, `-r`, `--continue`, `-c`, `--session-id` as tokens and every
  shell metacharacter (v0.7.0), and `--name`, `-n` since v0.8.0 (the plugin appends
  `--name` itself for a pending tab-name push - amendment 2026-09-19).
- **D15 Registry liveness belongs to the hook, placement to the plugin.** The hook
  script owns `state`, `pid`, `cwd` and the file lifecycle (create on SessionStart,
  delete on SessionEnd); the plugin owns `project` and `tab` (persistent name) and only
  merges. Both write atomically. Unbinding a tab does not remove the file - the session
  is still alive; project close does, because the terminals die with it (v0.7.0).

- **D16 A tab's sessions run under its root.** The root is the tab's profile directory
  (a tab whose persistent name equals a tab profile's name), else the project folder.
  Every detection channel, the registry publish and the close listener check a cwd
  against the target tab's root; the project-level filter accepts the folder or any
  profile directory. Without profiles this is exactly the project-folder check of
  v0.7 (amendment 2026-09-30).

## 3. Identity model

`SessionBinding` (persisted per project in `.idea/workspace.xml`, component
`ClaudeSessionBindings`):

| field | meaning |
|---|---|
| `id` | binding UUID |
| `currentSessionId` | session the tab currently runs |
| `sessionHistory[]` | `{sessionId, replacedAt}`, newest first, capped by `maxHistoryPerBinding` |
| `tabIdentifier.tabUuid` | runtime tab id (Content UserData); stale after restart |
| `tabIdentifier.persistentName` | terminal-persisted tab name; restart-stable |
| `tabIdentifier.name` | display name at bind time (informational) |
| `lastResumedAt`, `resumeCount`, `createdAt` | bookkeeping; drive dedup keeper choice |
| `sessionTitle` | last `/rename` title observed for `currentSessionId` (v0.8.0) |
| `pendingTitlePush` | IDE tab name not yet delivered to the session; sent as `--name` on the next auto-resume (v0.8.0) |

Content UserData keys (`TerminalHelper`): `STABLE_TAB_KEY` (tab UUID),
`TITLE_PROTECTED_KEY`, `DECORATED_KEY` (side effects applied once per IDE session),
(the `TEAM_TAB_KEY` of the removed teams module is gone since v0.7.0).

`bindOrUpdate` branches, in order: validate id and tab; ownership guard (D3); reuse an
existing binding for this tab (self-heal identity, apply decoration, update session);
adopt a restart-stale binding by persistent name (only outside the suppression window);
create.

## 4. Platform notes

- Reworked terminal only (`TerminalToolWindowTabsManager`, `TerminalView`,
  `TerminalTitle`); classic `ShellTerminalWidget` paths remain as fallbacks.
  `sinceBuild = 261` because `TerminalTabsManagerListener` gained default methods after
  253 (NoSuchMethodError on 2025.3).
- 262 (2026.2) removed `trackTerminalApplicationTitleChanges`; the setter is looked up
  reflectively and skipped when absent. The 262 "Use application title as tab name"
  setting is tri-state; title protection clears `applicationTitle` on every change
  instead of relying on the flag.
- Restored tabs start their shell session lazily: `TransferableTerminalSessionImpl`
  schedules `startSession` with `initOnShow(view.component)` (NonCancellable), so a tab's
  process starts only when its component is first shown. `TerminalView.sendText` is
  buffered until then. Before v0.7.0 only the selected tab of each project launched
  Claude after a restart; the others launched when clicked (observed 2026-09-15: hook
  events for non-selected tabs arrived 3-25 min after the resume command was sent).
  Since v0.7.0 `AutoResumeService.startShellsForRestoredTabs` selects each not-started
  tab once (`TerminalView.sessionState` is `NotStarted`), restores the selection and the
  previously active tool window, and waits for the states to change.
- The IDE flushes `workspace.xml` lazily; file-based audits during a session are stale.
  `idea.log` is the source of truth.
- macOS NIO `WatchService` misses events; every watcher has a periodic fallback poll.

## 5. Detection channels

| channel | service | identity accuracy | may create |
|---|---|---|---|
| `SessionStart` hook -> `~/.claude/ide-events/current-session.json` | `ClaudeHookEventService` | selected tab (weak); a cwd inside exactly one tab profile's directory goes to that profile's tab (v0.9.0) | yes, subject to D3/D6/D16 |
| terminal output scan for UUIDs, confirmed by transcript file or index | `TerminalOutputMonitorService` | exact tab | yes |
| `sessions-index.json` watch | `SessionWatcherService` | none (reconciles existing) | only genuinely new sessions |

The hook script (`src/main/resources/hooks/session-notify.sh`, marker
`# csm-hook-version: 2`) is installed by `ClaudeHookManager` for six events -
`SessionStart` (matcher `*`), `UserPromptSubmit`, `PreToolUse`, `Notification` (matcher
`permission_prompt`), `Stop`, `SessionEnd` - each entry with `timeout: 5`, merged next to
other tools' entries under the same events; idempotent, and a v1 install is reported as
outdated. The v1 `sessions.jsonl` append is gone.

### Session registry (v0.7.0)

`~/.claude/ide-events/sessions/<session_id>.json`:

| field | writer | meaning |
|---|---|---|
| `session_id`, `cwd` | hook | from the hook payload |
| `state` | hook | `idle` (SessionStart, Stop), `busy` (UserPromptSubmit, PreToolUse), `waiting` (Notification permission_prompt; cleared by the next PreToolUse or Stop) |
| `pid` | hook | nearest ancestor of the hook shell whose command is `claude`; readers `kill -0` it |
| `updated_at` | both | ISO-8601 with offset, last change by either writer |
| `project`, `tab` | plugin | IDE project name and the tab's persistent name; written on bind and rename |
| `title` | plugin | the session's own title (`/rename`, `--name`), written when observed; never cleared (v0.8.0) |

Lifecycle: created by the hook on SessionStart (or by the plugin on bind if earlier),
merged by both writers through temp file + atomic rename, deleted by the hook on
SessionEnd and by the plugin on project close (`RegistryProjectCloseListener`); files
older than 24 h whose `pid` is no longer running are swept once per IDE process and on
hook reinstall - a live process keeps its file however old, since the hook only rewrites
on events and an idle session's file goes stale while the session does not. The plugin
never lists the directory to learn about foreign sessions; its only read is of a file
named by a session id it already holds (`SessionRegistryService.read`), used in two
places: auto-resume skips a session whose `pid` is alive (it is running in another IDE
or terminal - typing `--resume` again would start a second process on the same
transcript), and the output channel takes `cwd` from it when deciding whether a session
id seen in a tab's output belongs under this project at all.

## 6. Startup and restart flow

`AutoResumeService` (`ProjectActivity`): wait `autoResumeDelayMs` -> poll up to 15 s for
the terminal tool window, running `BindingMigrationService.migrate()` on each poll (dedup
by session, then re-attach stale bindings by persistent name, unambiguous matches only)
-> if auto-resume is on: activate the suppression window, then for each binding (live-tab
ones first, one resume per session id) resolve the resumable session (D4) and send
`claude --resume <id>` to the tab -> notifications for resumed / missing-tab / stale
bindings ("Start new session" or "Remove binding") -> start the three detection
channels.

Verified live 2026-08-05 (six projects, ~19 sessions, 2026.1 -> 2026.2 IDE update with
sessions preserved) and 2026-09-15 (24 resume commands, all queued; see section 4 for
the lazy-start caveat).

## 7. Settings

`claudeCommand` (allowlist `[a-zA-Z0-9_./ -]`, default `claude`), `autoResumeEnabled`,
`autoResumeDelayMs` (500-30000), `notifyOnSessionChange`, `notifyOnAutoDetection`,
`syncTabNames` (inbound title sync: the session's `/rename` title becomes the tab's
persistent name), `pushTabNameToSession` (outbound: an IDE rename is typed into an idle
session as `/rename`, else delivered with `--name` at the next auto-resume - amendment
2026-09-19), `customClaudeHomePath`, `fileWatcherDebounceMs`, `maxHistoryPerBinding`,
`orphanedHistoryRetentionDays`, `forceOptionAsMetaKeyOff` (reverts the macOS
"Option as Meta" toggle Claude keeps enabling), `protectTabNames`. The
`CLAUDE_CODE_DISABLE_TERMINAL_TITLE` env toggle is written to `~/.claude/settings.json`
by `ClaudeTitleSettingsService`.

Project level (`ProjectSettingsService`, workspace file, component
`ClaudeSessionManagerProject`): `tabProfiles` (name, working directory, launch
arguments, keep open - amendment 2026-09-30; validated on save and on read), and
`extraLaunchArgs` - allowlist `[A-Za-z0-9_.:=/ ,@-]`,
single line, reserved session flags rejected (D14); re-validated on read. Edited on the
"Launch Profile" page nested under the application page. Reference value:
`--channels plugin:<name>@<marketplace>`, with the plugin on the managed-settings
allowlist (`channelsEnabled: true` plus an `allowedChannelPlugins` entry in the object
form `{"marketplace": ..., "plugin": ...}`; the `plugin:name@marketplace` string form is
rejected by the schema validator). Verified on a Max account without an organization,
2026-09-19.

## 8. User surface

Terminal tab context menu (`Claude Session`): Bind to Session, Resume Session, Push
Tab Name to Claude Session (v0.8.0), Session History, Open Profile Tabs and Open Profile
Tab (v0.9.0, hidden without profiles), Current Session Info, Copy Session ID, Unbind,
Settings. Tools menu: Open Profile Tabs. Terminal new-tab dropdown: "Claude: <profile>"
per tab profile. Terminal "new tab"
dropdown: "Claude Session" (pick a session, opens a bound tab). Tab decoration: colored
Claude icon, live-indicator badge while Claude reports progress, tooltip
`Claude: <status>`, OSC title stripped from protected tabs.

## 9. Known limitations

- Hook events are attributed to the selected tab; several sessions starting at once may
  bind wrongly until the output channel corrects them.
- Claude Code shows a full-screen warning for `--dangerously-load-development-channels`
  on every start with no persisted acceptance; restored tabs stop there until a key is
  pressed. The plugin does not automate that keypress - dismissing a security dialog on
  the user's behalf is not something it should do. Use `--channels` with a
  managed-settings allowlist instead (section 7), which needs no dialog; the dev flag is
  unusable for unattended restore, where every restored tab would stop.
- `SessionEnd` does not fire for a killed process; a registry file can outlive its
  session until project close or the 24 h sweep. `pid` exists for exactly this case.
- Two writers share each registry file; a lost update between writes in the same
  millisecond is accepted and repaired by the next event.
- Title sync types `/rename` into a session only right after an IDE rename and only
  while the registry says the session is idle; a busy or waiting session gets the name
  at its next auto-resume or through the tab-menu action. If the prompt box holds an
  unsent draft at that moment, the draft is submitted together with the command.
  Titles are read from the transcript tail (256 KiB) and the `custom-title.json`
  sidecar, never from `sessions-index.json` (amendment 2026-09-19).
- Tab profiles (amendment 2026-09-30): a tab that is already open when its profile is
  created keeps running what it ran - the plugin never types into an open tab; it says
  so when the bound session runs outside the profile directory. "Bind to Session..." on
  a profile tab lists the project folder's sessions only. Keep-open profile tabs are
  opened only when "Auto-resume on startup" is on.

## 10. Removed

- Agent Teams module (v0.3.0-0.6.6: dashboard tool window, teammate tabs, settings
  page, `~/.claude/teams/` watcher, 107 tests). Removed in v0.7.0; subagents are managed
  inside one session now.

## Amendments

- 2026-09-19 `docs/specs/2026-09-19-session-title-sync-amendment.md` - session title
  sync (v0.8.0): sections 2 (D14), 3, 5, 7, 8, 9.
- 2026-09-30 `docs/specs/2026-09-30-tab-profiles-amendment.md` - tab profiles (v0.9.0):
  sections 2 (D16), 5, 6, 7, 8, 9.
