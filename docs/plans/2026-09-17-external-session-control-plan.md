---
created: 2026-09-17
session_id: 3c0d7ea2-c155-4353-96cd-5396c75bf7c7
status: completed
parent_spec: docs/handoffs/2026-09-16-external-session-control.md
related_roadmap_items: [external-session-control]
---

# External session control - implementation plan

Baseline v0.6.6 (`cad8f97`), branch `feature/external-session-control`. Target version
0.7.0 (new public surface: project setting, registry directory, three new hooks, a module
removed).

## 0. Findings - where the code disagrees with the hand-off

Verified against the source and the WebStorm 2026.2 logs on 2026-09-17.

- **F1 `SessionHistoryAction` executes, it does not only display.** Lines 123-124 build
  and send the resume command (`TerminalHelper.executeCommand(context, command)`). It
  must carry the launch args like every other caller. Hand-off section 1 says leave it.
- **F2 Two hook scripts exist.** The plugin's template (`ClaudeHookManager.
  hookScriptContent`) writes only `current-session.json`; the script on disk is an older
  hand-maintained variant that also appends to `sessions.jsonl` (1.1 MB, unpruned).
  Re-installing from the plugin overwrites the disk version. The registry work replaces
  the template; the `sessions.jsonl` append is dropped (nothing reads it).
- **F3 Section 3 root cause is confirmed, and it is the terminal, not the plugin.**
  `TransferableTerminalSessionImpl.scheduleSessionStartOnShow` wraps `startSession` in
  `UiScopeKt.initOnShow(view.component)`: a restored tab's shell starts when its Swing
  component is first shown. `TerminalView.sendText` is buffered until then. Evidence
  (idea.6.log, restart 2026-09-15 15:10): the plugin sent 24 resume commands within 20 s
  ("Executed command via TerminalToolWindowTabsManager (byId=true)", one per bound tab);
  the SessionStart hook events for the selected tab of each project followed within 2-5 s,
  while `Debugger` fired at 15:13:58, `Kafka master` at 15:35:55 and `Local (2)` at
  15:37:17 - each on the user's click. The fix is to show every bound tab once after the
  commands are queued (section 3 below).
- **F4 The test suite is the teams suite.** Outside `teams/` there is one test
  (`TabIdentifierTest`). "Existing binding/resume tests pass" is vacuous; this plan adds
  the tests the hand-off asks for and a few the removal makes necessary.
- **F5 `~/.claude/settings.json` already carries `Stop`, `UserPromptSubmit`,
  `PreToolUse`, `SessionEnd` entries from two other tools** (aoe-sound, notchi). The
  installer's merge logic exists for `SessionStart` only; it is generalised, not
  rewritten.
- **F6 Registry files have two writers** (hook script sets `state`, plugin sets
  `project`/`tab`). Both must read-merge-write through a temp file + rename, and both
  must tolerate the file being absent or half-written. A lost update between two writers
  in the same millisecond is accepted; the next event repairs it.
- **F7 `PreToolUse` runs a bash+jq process on every tool call.** With `UserPromptSubmit`
  already flipping to `busy`, `PreToolUse` only matters when Claude continues without a
  new prompt (a `Stop` hook that blocks continuation). Implemented as specified; latency
  measured in acceptance and revisited if visible.

## 1. Launch profile - per-project extra launch args

- [x] `services/ProjectSettingsService` - project-level `PersistentStateComponent`,
  `@State(name = "ClaudeSessionManagerProject", storages = [Storage(WORKSPACE_FILE)])`,
  state `class State { var extraLaunchArgs: String = "" }`; accessor re-validates on read
  (same pattern as `SettingsService.claudeCommand`).
- [x] `util/LaunchArgsValidator` - single line; allowlist `[A-Za-z0-9_.:=/ ,@-]`; reject
  quotes, `$`, backticks, `;`, `|`, `&`, `<`, `>`, newlines; reject the tokens `--resume`,
  `-r`, `--continue`, `-c`, `--session-id` (the plugin owns which session is resumed).
  Returns the normalised value (trimmed, single spaces) or null.
- [x] `util/LaunchCommand` (pure assembly, unit tested); `TerminalHelper.
  buildResumeCommand(project, sessionId)` / `buildStartCommand(project)` /
  `getExtraLaunchArgs(project)`; `resumeClaudeSession(project, widget, sessionId)`.
  The unused `resumeClaudeSession(AnActionEvent, ...)` overload was deleted.
- [x] Callers pass the project's value: `AutoResumeService` (resume and
  `startFreshSessions`), `ResumeSessionAction`, `BindSessionAction`,
  `SessionHistoryAction` (F1), `NewClaudeSessionAction`, `ClaudeSessionTerminalProvider`.
- [x] Settings UI: `projectConfigurable` "Launch profile" nested under the existing
  application page (`parentId="com.github.jendalen.claudesessionmanager.settings"`),
  one text field with the validator's rejection shown inline; `apply()` calls
  `panel.apply()` before saving (known trap).
- [x] Tests: validator (accepts
  `--dangerously-load-development-channels server:foo`, rejects `;`, `$(`, newline,
  `--resume x`), command assembly (blank args, args present, trailing spaces).

## 2. Session registry

- [x] Hook script v2 (`ClaudeHookManager.hookScriptContent`): reads `hook_event_name`;
  `SessionStart` writes `current-session.json` as today (existing watcher stays) AND
  upserts `sessions/<id>.json` with `session_id`, `cwd`, `state: idle`, `updated_at`
  (ISO-8601 with offset), `pid`; `UserPromptSubmit`/`PreToolUse` -> `state: busy`;
  `Notification` (matcher `permission_prompt`) -> `state: waiting`; `Stop` ->
  `state: idle`; `SessionEnd` -> delete. `pid` = first ancestor of the hook shell whose
  command is `claude`. Upsert = `jq` merge of the existing file (keeps `project`/`tab`)
  -> temp file in the same directory -> `mv`. Session id validated against the UUID
  pattern before it becomes a file name.
- [x] Installer: `hookConfig` becomes one entry per event; `installHooks` merges the
  entry into `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `Notification`
  (matcher `permission_prompt`), `Stop`, `SessionEnd` without touching other entries
  (F5); `checkHookInSettings` requires all six; `revertHooks` removes ours from all six. Idempotent: running install twice changes
  nothing. The settings page's "Integration details" shows the new script and entries.
- [x] `services/SessionRegistryService` (app-level): `upsert(sessionId, project, tab)`
  merge-write, `remove(sessionId)`, `sweep(olderThan = 24h)` on startup. Called from
  `TabBindingCoordinator` after every successful bind/adopt/update (the write path is the
  natural hook), from the tab-rename listener already in
  `TerminalOutputMonitorService` (persistent name, not the OSC display name).
  `removeAll(sessionIds)` on `projectClosing` (`RegistryProjectCloseListener`) for the
  project's bindings. NOT on unbind: unbinding a tab does not end the session, and
  liveness is the hook's domain.
- [x] The plugin creates the file when the hook has not yet (the output channel binds a
  resumed session from the typed command line before Claude starts); no `state` field
  then. Unchanged content is not rewritten.
- [x] Hook entries carry `"timeout": 5` so a hung jq cannot block a tool call for the
  default 60 s. Measured: ~22 ms per PreToolUse invocation on the development Mac.
- [x] Installer reports a v1 script as outdated (`# csm-hook-version: 2` marker) and
  reuses the existing SessionStart entry on upgrade.
- [x] Sweep on plugin start; also sweep when the hook script is (re)installed.
- [x] Never read another process's writes: the plugin only ever merges into files it
  addresses by session id.
- [x] Tests: hook script executed with fixture stdin for each event (skipped when
  `bash`/`jq` are absent; there is no CI), asserting file content and atomic
  replacement; installer merge against a fixture `settings.json` with foreign hooks
  under every event; `SessionRegistryService` merge semantics and sweep.

## 3. Launch every restored tab

- [x] After the resume loop in `AutoResumeService.execute`, on the EDT: collect bound
  tabs whose `TerminalView.sessionState.value` is `NotStarted`; if the terminal tool
  window is hidden, `show()` it; for each such tab `contentManager.setSelectedContent`
  and yield one EDT cycle (the `initOnShow` hierarchy listener needs a dispatched event);
  then restore the originally selected content (and re-hide the window if it was
  hidden). Log one line per tab: "Started shell for restored tab X".
- [x] Two passes with a 10 s budget each; tabs still `NotStarted` are logged ("did not
  start within") and counted as failed. A hidden tool window is shown for the pass and
  the previously active tool window (or hidden state) restored afterwards.
  `initOnShow` runs the start NonCancellable, so deselecting right away is safe.
- [x] Acceptance on the four worktree projects: every "Auto-resumed session" line is
  followed by that session's `Processing session event ... source=resume` within ~10 s,
  no click.

## 4. Remove the teams module

- [x] Delete `src/main/kotlin/.../teams/`, `src/test/kotlin/.../teams/`,
  `src/test/resources/fixtures/teams/`, `icons/claude-teams*.svg`.
- [x] `plugin.xml`: drop the v2.0 services, the Agent Teams configurable, the "Claude
  Teams" tool window, `TeamWatcherStartupActivity`, the four actions and the popup group.
- [x] `TerminalHelper.TEAM_TAB_KEY` / `isTeamTab` and their four call sites go
  (`TabBindingCoordinator`, `AutoResumeService` x2, `BindingMigrationService`,
  `TerminalOutputMonitorService`).
- [x] Old `ClaudeTeamsSettings` XML in users' config is ignored by the platform once the
  component is unregistered; no migration needed.
- [x] Replacement tests so the suite is not empty: `SessionIdValidator`,
  `resolveResumableSessionId` (extracted to a pure function over
  `SessionBinding` + a `(String) -> Boolean` transcript probe).

## 5. Wrap-up

- [x] Version 0.7.0: `gradle.properties`, `plugin.xml` change-notes, `CHANGELOG.md`
  (backfill 0.6.x while there).
- [x] `docs/prd.md`: add section 7 project settings, section 5 registry channel, update
  section 4 lazy-start note and section 9 limitations; drop the teams limitation.
- [x] `README.md`: launch profile, registry format for external readers, hooks list.
- [x] `./gradlew test buildPlugin verifyPlugin` (48 tests; Compatible on WS-261.27258.45,
  WS-262.10315.144, WS-263.4732.34), installed into IntelliJIdea2026.2 and
  WebStorm2026.2 on 2026-09-17.
- [x] Live verification after restart: hooks reinstalled from the settings page,
  acceptance 1-3 below observed (IDEA 2026-09-18, WebStorm 2026-09-19; three defects
  recorded under "Live verification status", none of which blocks the acceptance
  criteria themselves - see the release note there).
- [x] Fixes for the three defects and both review runs merged to `main` (`77b9ed4`,
  2026-09-19). Plan closed 2026-10-05. 0.7.0 was never tagged; the release decision is on
  the roadmap (0.8.0 and 0.9.0 are stacked on top of it on `feature/tab-profiles`).

## Live verification status

2026-09-18, IntelliJ IDEA 2026.2 restarted with 0.7.0, hooks reinstalled from the
settings page at 16:27:37 (log: "Created hook script", "Added hook to settings.json for
5 event(s)" - SessionStart was reused, so six in total).

- **Acceptance 2 observed.** `~/.claude/hooks/session-notify.sh` carries
  `csm-hook-version: 2`; `settings.json` has our command under all six events.
  `sessions/4e695d61-....json` was created by the hook (`state`, `pid` 68248 = a live
  `claude` process, `cwd`) and merged by the plugin (`project: claude-session-manager`,
  `tab: Main dev`) - the plugin's write comes from the output channel's "already bound"
  re-confirmation, on a pooled thread, so it lands a few seconds after the hook's; a
  read in between shows the hook's fields only. Not a defect. The hook's `. + {...}`
  merge kept the plugin's fields through every later PreToolUse rewrite. A second
  session (`c12ac491-...`, WebStorm) flipped `busy` 16:28:39 -> `idle` 16:28:56 around
  one prompt.
- **Acceptance 1 not exercised.** This project has no `extraLaunchArgs` set (no
  `ClaudeSessionManagerProject` component in `workspace.xml`); the resume command was
  the bare `claude-x --resume <id>`. Needs a value set on one project and a new
  session or restart.
- **Acceptance 3 not exercised.** IDEA had 3 bindings, 2 with no transcript on disk
  (skipped, correct), 1 on the selected tab - its shell was already started, so the
  NotStarted set was empty and no "Started shell for restored tab" line was expected.
  The four worktree projects are in WebStorm, which has NOT been restarted (73 h
  uptime in its log, still on 0.6.6 - which is also why its registry entry has no
  `project`/`tab`).

2026-09-19 12:15, WebStorm 2026.2 restarted with 0.7.0, six projects, 28 bindings.

- **Acceptance 1 observed.** 13 resume commands carried
  `--channels plugin:test-channel@spike-market` (the four Shop worktrees; the other
  12 projects have no launch profile). All 13 started: registry files with live pids,
  state `idle`, no dialog stalled any tab. Whether each channel is live is not readable
  from idea.log; the spike plugin's 8788 race (no cwd guard) means one instance holds
  the port per restart (bun 87449 this time) and the rest report CONNECTION_CLOSED -
  known, the spike plugin is being retired.
- **Acceptance 3 observed.** 19 "Started shell for restored tab" lines across the six
  projects, zero "did not start within". 25 of 28 bindings resumed (2 skipped for no
  transcript on disk, correct), and **every one of the 25 has a registry file with a
  live pid** - all started with no click. The hook-event log lines are not a reliable
  count (111 `source=resume` events for 25 resumes: every project's watcher processes
  every event, and 5 sessions had no matching line at all yet had started fine); the
  registry is the ground truth for "did Claude start".
- **Acceptance 2 holds** at 30 registry files.

Two defects found in the same run:

1. **Cross-IDE double resume.** Session `4e695d61` was bound in IntelliJ IDEA
   (ClaudeSessionManager project, tab 'Main dev', pid 68248, running since 09-18 16:27)
   AND in WebStorm (Shop project, tab 'Local'). WebStorm resumed it at 12:15:44 while
   the IDEA process was still alive; both now run the same transcript (pids 68248 and
   19288). Bindings are per project per IDE and nothing checks for a live process
   elsewhere before resuming. The registry already carries what is needed to prevent
   this: before resuming, read `sessions/<id>.json`; a live `pid` with a different
   `cwd`/`project` means the session is running elsewhere - do not resume, do not
   type into the tab. **Fixed 2026-09-19:** `SessionRegistryService.read` +
   `Entry.isLive()` (`ProcessHandle`, no shell); `AutoResumeService` skips a binding
   whose session has a live pid and logs where it runs. The sweep keeps files with a
   live pid however old - otherwise a day-idle session's file would be swept and the
   guard would see nothing.
2. **Registry `project`/`tab` never written for two of the four worktree sessions**
   (`c12ac491` worktree1, `a2bac8d5` worktree4). Traced for `a2bac8d5`: the output
   channel confirmed it twice (12:15:27, 12:15:31), both inside the 15 s auto-resume
   suppression window; `TabBindingCoordinator.bindOrUpdate` returns the existing
   binding on that path WITHOUT calling `registerSession`, and nothing re-confirmed
   after the window closed. For `c12ac491` the output channel never matched at all.
   Suppression exists to stop a binding being CHANGED during the storm; publishing an
   unchanged, existing binding to the registry is safe there. Fix: call
   `registerSession` on the suppressed-existing path too. **Fixed 2026-09-19**, guarded
   on the suppressed event naming the session that IS bound - a misattributed event for
   another session must not publish this tab into that session's file.
3. **The output channel binds on any bare UUID printed in a tab.** In IDEA, tab 'Main
   dev' (session `4e695d61`) was rebound to `be6e9fc5` and then to `b9851854` - both
   Warp sessions in `channel-spike` - purely because their ids appeared in tool
   output (`cat` of their registry files) at 12:23:03; it flipped back to `4e695d61` at
   12:31:47 when that id was printed. `TerminalOutputMonitorService` scans output for
   the UUID regex and accepts any match for which `ClaudePathResolver.sessionFileExists`
   is true - a GLOBAL check, no cwd or project guard. The hook channel has exactly that
   guard ("Hook event skipped: CWD ... doesn't match project"); the output channel,
   the higher-trust one that may move sessions, does not. Consequence for the registry:
   `registerSession` then wrote `project: claude-session-manager, tab: Main dev` into
   `b9851854`'s file - a Warp session in another repo, labelled as an IDEA tab. An
   orchestrator that inspects worker sessions prints their ids constantly, so this is
   not a corner case for the intended use. Fix: apply the hook channel's cwd guard to
   the output channel, sourcing the session's cwd from the transcript's location
   (`~/.claude/projects/<encoded cwd>/<id>.jsonl`; compare encoded project path with
   the directory-boundary rule) or the registry file's `cwd` when present. Not yet
   made. Until it is, external readers must treat `cwd` as authoritative and
   `project`/`tab` as advisory. **Fixed 2026-09-19:** the delayed verification resolves
   the session's cwd (index `projectPath`, else registry `cwd`, else the transcript's
   records via `ClaudePathResolver.readSessionCwd` - the encoded directory name is
   unusable, `Shop` and `Shop-worktree1` encode as prefixes of each other) and
   refuses on mismatch or on no cwd at all. Same directory-boundary rule as the hook
   channel (`isPathMatch`).

Also observed 12:31, WebStorm closed: `RegistryProjectCloseListener` fired for every
project - 30 "Session registry: removed" lines, registry down from 30 files to 6, and
none of the 13 worktree sessions survived. The six left are Warp / IDEA sessions the
WebStorm plugin never owned.

12:23, routing test against the spike server: one POST (`X-Kind: ping`) to 8788 ->
`queued chat_id=2`, `[reply 2] pong` on the SSE stream 4 s later, and only `b9851854`
moved in the registry. A message reaches exactly ONE session - the process holding the
port - and there is no way to choose which. 14 sessions had `--channels`, one server
was alive (the Warp session's; the 13 IDE ones connected their MCP, then died on
`Bun.serve` EADDRINUSE). Per-worktree targeting is the orchestrator spec §3.4's job (outbound
long-poll, no port), not something the spike can do.

**Review (2026-09-19, `/bookbot:review-code-x`, two runs, Claude x4 + GPT gpt-6-astra).**
Run 1: 65 merged findings, 22 SAFE-AUTO applied (`9bab291`). Run 2: 66, all run-1 fixes
verified holding, 24 applied (`24d161d`). Four of the remaining Highs were then applied
on the owner's decision: registry files of sessions owned or live elsewhere survive
project close (amends D15 - the double-resume guard reads them); the resume dedup honours
the fallback id; the hook watcher's watermark is seeded from the event on disk; the
launch-profile allowlist reserves file-loading and permission flags. Still open, all
NEEDS-REVIEW / DOMAIN-DECISION: the output channel's indexed path accepting any printed
UUID from the same project (M20), the hook channel planting unowned sessions on the
selected tab (H9), non-indexed ids re-verified every 1.5 s (H2), `project.save()` on the
EDT per mutation (H3), four user actions bypassing the coordinator (H6), index-watch
rewriting a binding despite `allowCreate=false` (H8), unsynchronised cross-process
registry writers (M16). Roadmap.

**Release note (superseded 2026-09-19 - all three fixed, 59 tests).** All three
acceptance criteria hold, but two of the three defects
corrupt the field external readers are meant to consume (`project`/`tab`, defects 2
and 3) and the third resumes one transcript in two processes (defect 1). Defect 2 is
one line; defect 3 reuses a guard that already exists on the other channel; defect 1
reads a file the plugin already writes. Recommendation: fix all three before tagging
0.7.0 rather than shipping a registry that lies on first contact. Whether to hold is
the owner's call - recorded here, not decided here.

## Acceptance (from the hand-off)

1. A project with `extraLaunchArgs` set resumes with those flags after an IDE restart,
   and a new session in that project starts with them.
2. `ls ~/.claude/ide-events/sessions/*.json` lists live sessions; one flips
   `idle -> busy -> idle` around a single prompt.
3. All four worktree projects reopen with every bound tab resumed, no click.
4. No `teams` code or settings remain; `./gradlew test` passes with the new tests.

## Decisions (2026-09-17, owner)

1. **`waiting` state added.** `Notification` hook with matcher `permission_prompt` ->
   `state: waiting`; cleared by the next `PreToolUse` or `Stop`. Registry state is
   `idle | busy | waiting`. A session blocked on a prompt must not read as busy forever.
2. **`tab` = persistent name.**
3. **Dead-entry detection.** Each registry file carries `pid` (the Claude process that
   ran the hook, found by walking up from the hook's shell to the first ancestor whose
   command is `claude`) so a reader can `kill -0` it instead of waiting for the sweep.
   The plugin deletes the registry files of its own bindings on `projectClosing`. The
   24 h sweep stays as backstop.
4. **Version 0.7.0** (assumed; not explicitly confirmed).
5. **Launch-profile reference value (corrected 2026-09-19):**
   `--channels plugin:<name>@<marketplace>`. Allowlist keeps `:` and `@`.

   **Something must activate the channel, and omitting it fails silently.** The plugin
   still loads and its MCP tools still work - what dies is the server's `claude/channel`
   experimental capability, so `notifications/claude/channel` and the permission relay
   never reach the session. There is no error to observe, so checking that the plugin
   loaded says nothing about whether the channel is live.

   Two ways to activate it, and the difference decides whether unattended restore works:

   - `--dangerously-load-development-channels <server>` shows a blocking consent dialog
     ("WARNING: Loading development channels" / "I am using this for local development")
     **on every launch**. There is no persisted accept: no `tengu_*_dialog_accept`
     telemetry for it, no `hasSeen*` key in `~/.claude.json`, no `CLAUDE_*` env var.
     The orchestrator spec §6 claims the dialog appears only on the first load of a plugin
     name; that is wrong (observed 2026-09-19). With many tabs autostarting after an IDE
     restart, every one of them would block - which rules this flag out entirely.
   - `--channels <server>` requires the server on an approved allowlist and shows **no
     dialog**. The allowlist comes from managed settings; the built-in "ledger" source
     will never contain a local plugin.

   Managed settings ARE honoured on a Max account without an organization (verified
   2026-09-19, 12:07: registered with no warning line, channel event delivered end to
   end), which corrects the orchestrator spec §3.4. `/Library/Application Support/ClaudeCode/managed-settings.json`:

   ```json
   {
     "channelsEnabled": true,
     "allowedChannelPlugins": [
       { "marketplace": "spike-market", "plugin": "test-channel" }
     ]
   }
   ```

   **`allowedChannelPlugins` takes the object form; the `"plugin:name@marketplace"`
   string form is rejected by the schema validator.** Both keys are required: the policy
   check reads `policySettings !== null && channelsEnabled !== true` as "skip", so
   creating the file with the allowlist but without `channelsEnabled: true` is worse
   than having no file at all - it turns a passing check into a failing one, and the
   symptom is silently dropped events. That is the likely cause of the earlier
   "managed settings are ignored" reading.
