# Hand-off: external session control (launch profiles, session registry, tab restore)

Written 2026-09-17 from the planning chat. Baseline: v0.6.6 (`cad8f97`).

## Why

The plugin becomes a small framework for controlling Claude Code sessions
from outside the IDE. An external orchestrator — a local daemon, unnamed
here and deliberately not referenced in code — needs three things from the
plugin: sessions that start with extra flags, a readable record of which
session is alive in which directory and whether it is idle, and every
terminal tab coming back after an IDE restart without a click. The plugin
already owns tab↔session bindings and is the only component that knows
which tab is which; nothing else on the machine can provide this.

Keep the plugin generic: no orchestrator-specific names, no queue or inbox
logic, no network. Everything below is files and launch flags.

## 1. Launch profiles — per-project extra launch args

**Problem found.** `TerminalHelper.buildResumeCommand` emits
`"${getClaudeCommand()} --resume $sessionId"`; there is no way to add flags.
The global `claudeCommand` setting (`SettingsService`, app-level) is
validated against `[a-zA-Z0-9_./ -]`, which rejects `:` — so flags like
`--dangerously-load-development-channels server:foo` cannot even be
smuggled in there, and a global value would be wrong anyway (the flags are
per project).

**Change.**
- New project-level setting `extraLaunchArgs: String` (default `""`),
  persisted next to `BindingsState` in the workspace file, with a text field
  in the settings UI (project scope).
- Validation: single line; allowlist `[A-Za-z0-9_.:=/ ,@-]`; reject
  quotes, `$`, backticks, `;`, `|`, `&`, `<`, `>`, newlines. The plugin
  appends `--resume <id>` itself, so the value can never redirect which
  session is resumed.
- `buildResumeCommand(sessionId, extraArgs = "")` →
  `claude <extraArgs> --resume <id>`; new sessions get the same insertion.
  Callers with a `Project` in scope pass the project's value
  (`executeCommandInTerminal`, the resume/bind actions); give
  `resumeClaudeSession(widget, sessionId)` a `project` parameter so the
  terminal provider and `NewClaudeSessionAction` carry the flags too.
- `SessionHistoryAction` only displays the command; leave it.
- Unit tests for the validator (rejects `;`, `$(`, newline; accepts the
  real flag line) and for the command assembly.

Example value (verified 2026-09-19): `--channels plugin:inbox-channel@<marketplace>`
— a user-scope plugin, so nothing lives in the project. No development flag
and no dialog: the plugin is approved machine-wide through
`/Library/Application Support/ClaudeCode/managed-settings.json`
(`channelsEnabled: true` + `allowedChannelPlugins: [{marketplace, plugin}]`),
which Claude Code honours on a personal Max account. Earlier notes claiming
the development flag was required, and that its warning appeared only once,
were wrong: that flag prompts on every launch and is not used.

## 2. Session registry — one file per live session, with state

**Today.** `~/.claude/hooks/session-notify.sh`, installed by the plugin as a
`SessionStart` hook, receives `{session_id, source, cwd}` on stdin and
writes `~/.claude/ide-events/current-session.json` plus a line in
`sessions.jsonl`; `ClaudeHookEventService` watches the former.

**Change.** Generalise to a directory an external reader can consume:

```
~/.claude/ide-events/sessions/<session_id>.json
{
  "session_id": "…",
  "cwd": "/Users/…/Shop-worktree2",
  "project": "Shop-worktree2",       // IDE project name
  "tab": "Local (2)",                  // terminal tab title
  "state": "idle" | "busy",
  "updated_at": "2026-09-17T09:12:03+02:00"
}
```

- `state` comes from hooks the plugin registers alongside `SessionStart`:
  `Stop` → `idle`, `UserPromptSubmit` and `PreToolUse` → `busy`. The hook
  script updates the file in place (atomic write: temp file + rename).
- `project` and `tab` are filled by the plugin when it binds the session
  (the hook does not know them); the plugin rewrites the file on tab
  rename and on rebinding.
- Remove the file on `SessionEnd`; sweep files older than 24 h on plugin
  start.
- Keep `current-session.json` for the existing watcher until it is
  migrated to the directory.
- **Merge, don't overwrite:** `~/.claude/settings.json` already contains
  other hooks (`Notification`, `PermissionRequest`, `PostToolUse`, …). The
  installer must add entries without touching the rest, and must be
  idempotent.
- The reader is external and read-only; the plugin never reads another
  process's writes here.

## 3. Restore every terminal tab on IDE restart

Today only the first terminal tab of a project resumes its session after
an IDE restart; the others load only when clicked. For unattended
operation every bound tab must come back on its own. Investigate whether
this is the terminal tool window's lazy tab creation or the plugin's
startup activity; the goal is: all four worktree projects reopen with all
their bound tabs resumed, no click.

## 4. Remove the `teams` module

`FileSystemTeamWatcher` and the `~/.claude/teams/{team-id}/…` watching
(with its tests under `src/test/kotlin/…/teams/`) date from showing
subagents in separate tabs. Subagents are managed inside one session now.
Delete the module, its settings and tests; bump the version note.

## Acceptance

1. A project with `extraLaunchArgs` set resumes with those flags after an
   IDE restart, and a new session in that project starts with them.
2. An external script can list `~/.claude/ide-events/sessions/*.json` and
   watch one session flip `idle → busy → idle` around a single prompt.
3. All four worktree projects reopen with every bound tab resumed.
4. No `teams` code or settings remain; existing binding/resume tests pass.

## Out of scope

Inbox, queue, channel or network logic of any kind — that lives in an MCP
server the launch profile starts, outside this plugin. Anything that names
the orchestrator.

## Addendum 2026-09-17 — answers to the implementation review

Corrections accepted:

- §1: `SessionHistoryAction` builds and executes the resume; it gets the
  launch args like every other caller.
- §2: the hand-maintained `~/.claude/hooks/session-notify.sh` on disk is the
  one to replace (drop the `sessions.jsonl` append; the file is never
  pruned). `~/.claude/settings.json` already has `Stop`, `UserPromptSubmit`,
  `PreToolUse`, `SessionEnd` and `Notification` entries from other hooks —
  generalise the installer's merge, currently `SessionStart`-only.
- §3: root cause is the terminal's lazy `initOnShow`; fix as proposed —
  after queuing the resume commands, show each not-yet-started tab once
  (select, yield one EDT cycle, restore selection).
- §4: add validator / command-assembly / resume-fallback tests; the
  remaining suite is the `teams` suite, which goes.

Decisions on the open questions:

1. **`waiting` state — add now.** `Notification` with matcher
   `permission_prompt` → `waiting`; cleared by the next `PreToolUse` or
   `Stop`. Registry `state` is `idle | busy | waiting`.
2. **`tab` = persistent name** (what the user named it), not the display name.
3. **Crash residue:** delete the project's registry files on
   `projectClosing`; write the session's `pid` into each file so a reader
   can detect a dead entry with `kill -0`; the 24 h sweep stays as backstop.

Follow-up (2026-09-19): `AutoResumeService` treats "text reached the
terminal" as success, so a trust dialog, a bad `extraLaunchArgs` value or
a first-load warning log as a clean resume. Detector: no
`sessions/<id>.json` ~15 s after a resume ⇒ Claude never started; surface
it. The registry contract is mirrored in the orchestrator's spec
(v0.5); that spec is the source of truth for the file format.
