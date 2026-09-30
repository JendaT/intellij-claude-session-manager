---
created: 2026-09-19
status: active
type: prd-amendment
---

# Session title sync (v0.8.0)

**Parent PRD:** `docs/prd.md` - sections 3 (identity model), 5 (session registry), 7
(settings), 8 (user surface), 9 (known limitations).

## Problem

A "Sync Claude /rename to terminal tab name" checkbox (`syncTabNames`) has shipped on
the settings page and in the README since the first commit, and nothing implements it.
A session renamed with `/rename` keeps its old tab name; a tab renamed in the IDE is
invisible to the session; the registry `tab` field is the only place an external reader
(the orchestrator) sees a human name, and it never matches what Claude shows in its own prompt
box and `/resume` picker. The user wants one name per session that survives restarts and
is visible from the IDE, from Claude and from the registry.

## Verified facts (2026-09-19, Claude Code 2.1.278)

- `/rename <name>` appends `{"type":"custom-title","customTitle":"<name>","sessionId":
  "<id>"}` to the transcript and rewrites
  `~/.claude/projects/<slug>/<sessionId>/custom-title.json` (`{"customTitle":"<name>"}`).
  The transcript record is re-emitted every assistant message (measured: every 15-25
  lines), so a bounded tail scan finds it. `sessions-index.json` is not updated (no
  entry on this machine carries `customTitle`; the CLI's own reader takes the tail
  record first, the sidecar second).
- `claude --name <name>` sets the title at launch and **combines with `--resume`**: it
  writes a fresh `custom-title` record on the resumed session (tested with `-p`). A
  `--resume` without `--name` writes none. The help text says the name is "shown in
  the prompt box, /resume picker, and terminal title".
- Typed into a running interactive session through a pty, `/rename x` followed by
  **CR (`\r`) submits** ("Session renamed to: x"); followed by **LF (`\n`) it only
  inserts a newline** and the command sits in the prompt box. Whatever the reworked
  terminal's `shouldExecute()` appends, the push must end with an explicit `\r`.
- Enter on a session that is `waiting` answers the permission prompt; typing into a
  `busy` session queues the text as the next prompt. Neither is acceptable.

## Design

### S1 Source of truth for a session's title

The last `custom-title` record in the transcript's tail (last 256 KiB, complete lines
only), else the sidecar `custom-title.json`, else none. Blank titles count as none.
Never `sessions-index.json` (D7). Read by a pure `SessionTitleReader` (unit tested),
located through `ClaudePathResolver.findSessionFile`.

### S2 Inbound: Claude title -> tab

When a bound tab's session has a title that differs from the binding's recorded
`sessionTitle`:

1. record it on the binding (`sessionTitle`),
2. publish it to the registry as `title`,
3. if `syncTabNames` is on and the title differs from the tab's persistent name, set
   the tab's `TerminalTitle.userDefinedTitle` to the title. That is the terminal-
   persisted name (D1), so the name survives restarts and the existing title listener
   updates `persistentName` and the registry `tab` exactly as a manual rename does.

Guard: the rename is skipped (INFO log; steps 1-2 still happen) when another binding of
the project already carries that persistent name - `BindingMigrationService` only
re-attaches unambiguous names after a restart, and two tabs named alike would both be
left unattached. Skipped entirely while the auto-resume suppression window is open (D6).

Detection is a 5 s poll on a pooled thread over bindings whose tab is live: `stat` of the
transcript length and the sidecar mtime, the title is re-read only when either moved.
No NIO watcher per session directory (macOS WatchService is unreliable and there would
be one per bound tab). The poll is also kicked by `bindOrUpdate` so a fresh bind or a
SessionStart applies the title within a second.

### S3 Outbound: tab rename -> Claude

When the user renames a bound tab in the IDE (persistent name changes and the new name
is not the inbound echo, i.e. differs from `sessionTitle`) and `pushTabNameToSession`
is on:

- session live (`pid` alive) and registry `state == idle`: send `/rename <name>\r` into
  that tab (reworked terminal `sendText` by tab id, classic widget by name). The next
  inbound poll confirms it (the tail record now equals the tab name) and clears the
  pending mark.
- otherwise (busy, waiting, no registry file, not live): store `pendingTitlePush =
  <name>` on the binding, log INFO, and show one balloon with a "Push now" action. The
  pending push is delivered by the first of: the action (re-checks idle), or
  `--name '<name>'` appended to the next plugin-driven resume of that same session
  (auto-resume after restart; not a manual resume of a different session).

Never typed into a busy or waiting session, never deferred to "next idle" in the
background - the user may be typing a prompt at that moment. A `/rename` pushed into a
session whose prompt box holds an unsent draft submits the draft together with the
command; that is the one hazard of the feature and it is documented, not hidden.

### S4 Session naming belongs to the plugin (extends D14)

`--name` and `-n` join the reserved launch-arg tokens (also inside combined short flags:
`-dn`). The name appended by the plugin is single-quoted and rejected (INFO, push kept
pending) when it contains `'`, `\`, a control character or exceeds 200 characters.

### S5 Titles are per session

`SessionBinding.withNewSession` clears `sessionTitle` and `pendingTitlePush`. A tab that
moves to a new session (after `/clear`, a manual resume) keeps its tab name; the new
session's title is applied when observed. No automatic push of the tab name onto the
new session (that would type into a session the user just started).

### S6 Registry field `title`

Plugin-owned, merged like `project` and `tab`, never cleared (Claude never clears a
title either; blank is not written). After a successful sync `title == tab`; they differ
when inbound sync is off, when the duplicate-name guard skipped the rename, or between
an IDE rename and its delivery.

## Settings

- `syncTabNames` (existing, default true) - inbound (S2). Label kept.
- `pushTabNameToSession` (new, default true) - outbound (S3). Comment on the settings
  page names the draft hazard and the idle-only rule.

## User surface

Terminal tab context menu gains "Push Tab Name to Claude Session": pushes now when the
session is idle, else reports why not. The balloon from S3 carries the same action.

## Supersedes in parent PRD

- Section 3 table: `SessionBinding` gains `sessionTitle` (last title observed from
  Claude for `currentSessionId`) and `pendingTitlePush` (IDE name not yet delivered).
- Section 5 registry table: row `title` | plugin | Claude's session title, last
  observed; written on inbound and after a confirmed push.
- Section 7: `syncTabNames` is defined (S2); `pushTabNameToSession` added.
- Section 8: the new context-menu action.
- Section 9: the draft hazard and the busy/waiting deferral become listed limitations.
- D14 wording: "The plugin owns session selection and session naming."

## Out of scope

Titles for sessions without a bound tab; writing `custom-title.json` or the transcript
(D13); pushing on `/clear`; renaming from the registry side (the orchestrator names a session by
sending `/rename` through its channel if it ever needs to).

## Revision 2026-09-30 - first sighting

The first start of the built plugin in WebStorm would have renamed 7 of 26 bound tabs
with titles their sessions got days earlier, overwriting names chosen on purpose
("Kafka master", "Debugger"; IDEA's "Main dev" was renamed before this was caught).
S2 step 3 now distinguishes a first sighting (the binding never recorded a title for
this session - `sessionTitle == null`; after the first look it holds the title or `""`)
from a change: a first sighting renames the tab only when the tab has no user-defined
title (a default "Local ..." name); a change renames it as before. The rule is
`util/TitleSyncRules.kt`, unit tested. Switching inbound sync on (`reapplyTitles`) is
not a first sighting.
