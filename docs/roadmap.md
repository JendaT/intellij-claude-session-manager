---
type: roadmap
last_updated: 2026-10-05
---

# Claude Session Manager - roadmap

Operational source of truth for what is next. Update on feature completion and when
discovering issues. Shipped detail lives in `docs/prd.md` and the change-notes.

## Shipped (summary)

- [x] v0.5.0 - stable tab id identity; migration of name-only bindings; "keep my tab
  names" toggle.
- [x] v0.6.0-0.6.2 - restart-stable persistent name; single write path
  (`TabBindingCoordinator`); title protection; live progress badge instead of the IDE's
  asterisk title; 261 floor.
- [x] v0.6.3-0.6.6 - resume-storm hardening: user-action bypass of the suppression
  window, history fallback for unpersisted ids, hook events cannot move sessions between
  live tabs, dedup reverts instead of deleting, decoration on the reuse-existing path.
  Verified across the 2026.1 -> 2026.2 IDE update with ~19 sessions preserved.
- [x] v0.7.0 - external session control (plan
  `docs/plans/2026-09-17-external-session-control-plan.md`, completed): per-project extra
  launch args, session registry with idle/busy/waiting state and pid under
  `~/.claude/ide-events/sessions/`, eager launch of every restored tab, teams module
  removed. Live-verified 2026-09-18 (IDEA) and 2026-09-19 (WebStorm, 28 bindings); the
  three defects and two review runs fixed. On `main` at `77b9ed4`; not tagged.

## In flight

Branch state 2026-10-05: `main` = 0.7.0. `feature/session-title-sync` (0.8.0) and
`feature/tab-profiles` (0.9.0, stacked on it, plus three fixes 2026-09-30..10-01) are not
merged. Both IDEs run the `feature/tab-profiles` head (`6993180`) since 2026-10-01.

- [~] **Session title sync** (2026-09-19): spec
  `docs/specs/2026-09-19-session-title-sync-amendment.md`, plan
  `docs/plans/2026-09-19-session-title-sync-plan.md`. Code complete as v0.8.0, plus the
  2026-09-30 first-sighting rule (user-named tabs keep their names). Live: inbound
  `/rename` -> tab rename observed and persistent across restarts. Open: the outbound
  push has never run (whether the reworked terminal's `sendText` delivers a bare CR to
  Claude's prompt box is still unknown). The registry `title` lost after the first
  restart is fixed in 0.9.1 (2026-10-07, awaiting a live check after restart).

- [~] **Tab profiles** (2026-09-30): hand-off `docs/handoffs/2026-09-30-tab-profiles.md`,
  spec `docs/specs/2026-09-30-tab-profiles-amendment.md`, plan
  `docs/plans/2026-09-30-tab-profiles-plan.md`. Code complete as v0.9.0: named tabs that
  run Claude in a worktree outside the project folder with their own arguments, per-tab
  cwd root for every channel, attribution by cwd, resume-only-what-ran-there, keep-open
  at project open. Live in WebStorm since 2026-09-30 (Acme `acme-worker1/2`): no
  carry-over of main-clone sessions, registry `tab`/`cwd` correct, resume in the
  worktrees across four restarts, no freeze, launch publish complete. Not exercised: the
  "Open Profile Tabs" action, keep-open recreation of a closed tab, renaming a profile
  tab. Two `check-restart.sh` defects recorded in the plan.

## Backlog

- [ ] **Release.** Nothing is tagged after `v0.6.5`; 0.6.6 and 0.7.0 are on `main`,
  0.8.0 and 0.9.0 on `feature/tab-profiles`. Every one of them has run live. Decide
  whether to tag the intermediate versions or merge and release 0.9.1 alone.
- [x] `CHANGELOG.md` backfilled 0.6.0-0.7.0 (2026-09-17); keep it in sync with the
  `plugin.xml` change-notes from now on.
- [x] `README.md` refreshed for 0.7.0 (storage path, requirements, settings, registry).
- [x] `sessions.jsonl` append removed from the hook script (v0.7.0). The 1.1 MB file on
  disk can be deleted by hand; nothing reads it.
- [x] Tests: 59 across validators, command assembly, resume fallback, hook installer,
  registry service (read, sweep liveness), transcript cwd reader, path matching, and the
  hook script itself (run under bash + jq).
- [ ] Delete the stale `~/.claude/ide-events/sessions.jsonl` by hand. Unblocked: the v2
  hook has been live since 2026-09-18 and the file has not changed since then (1.1 MB,
  last written 2026-09-18 16:27).
- [ ] Session picker ("Bind to Session...") on a profile tab lists the project folder's
  sessions; list the profile directory's instead.
- [ ] Orphaned bindings whose tabs were closed weeks ago survive every restart (4 of 23
  in the 2026-08-05 audit) until dismissed via the missing-tabs notification. Consider
  auto-expiry driven by `orphanedHistoryRetentionDays`.
- [ ] Hook latency: ~22 ms per PreToolUse invocation measured on the development Mac
  (one jq call, pid lookup only when the file lacks one). Revisit only if it becomes
  visible in tool-call turnaround.
- [ ] Registry `waiting` covers permission prompts only; MCP elicitation dialogs
  (`notification_type: elicitation_dialog`) also block a session and could map to the
  same state.
- [x] **Do not resume a session that is running elsewhere.** 2026-09-19: WebStorm
  resumed `4e695d61` (a stale Shop binding) while IDEA was already running it - two
  processes on one transcript. Bindings are per project per IDE and nothing checks for
  a live process before typing the resume command. The registry has what is needed:
  read `sessions/<id>.json` first; a live `pid` (`kill -0`) with a different `cwd` means
  the session is running elsewhere - skip it, log which project/tab owns it, and leave
  the tab alone. Plan defect 1.
- [x] **Output channel needs the cwd guard the hook channel already has.** It binds a
  tab to ANY session UUID printed in that tab's output if a transcript exists anywhere
  (`sessionFileExists` is global). Printing another session's id - a `cat` of a registry
  file, a grep of a log, an orchestrator inspecting its workers - rebinds the tab and then
  writes the wrong `project`/`tab` into that session's registry file. Source the
  session's cwd from the transcript's directory (`~/.claude/projects/<encoded cwd>/`,
  directory-boundary compare against the encoded project path) or the registry `cwd`,
  and reject a mismatch the same way `ClaudeHookEventService` does. Plan defect 3.
- [x] **`registerSession` on the suppressed-existing bind path.** During the 15 s
  auto-resume window an existing binding is returned without publishing to the registry,
  so a session whose only output-channel confirmation lands inside the window never gets
  `project`/`tab`. Publishing an unchanged binding is safe there. One line. Plan defect 2.
- [ ] **Review leftovers (2026-09-19, two cross-model runs), all needing a design call:**
  M20 the output channel's *indexed* path accepts any UUID printed in a tab of the same
  project (the 0.7.0 cwd guard covers only the unindexed path) - the principled fix is
  to accept a UUID only inside a `claude ... --resume <id>` launch line; H9 the hook
  channel writes any *unowned* session onto the selected tab (registry `pid` comparison
  can refuse); H2 non-indexed ids re-verified every 1.5 s with a global scan; H3
  `project.save()` on the EDT after every binding mutation (fixed 2026-09-30 in 0.9.0 -
  it hung WebStorm during a 26-tab restore; now `SaveAndSyncHandler.scheduleProjectSave`
  from a pooled alarm, guarded by `NoBlockingUiWaitsTest`); H6 four user actions write
  bindings without `TabBindingCoordinator`; H8 index-watch rewrites an existing binding's
  session despite `allowCreate=false`; M16 the registry's two cross-process writers are
  unsynchronised (accepted in F6, revisit if lost updates show up). Full reports were
  ephemeral; the findings are summarised in the plan.
- [ ] **Resume-failure detector.** `AutoResumeService` logs "Auto-resumed session X in
  tab Y" when `executeCommandInTerminal` returns true - i.e. when the text reached the
  terminal. It never checks that Claude started, so a project trust dialog or a malformed
  `extraLaunchArgs` value logs as a clean resume. The registry makes this cheap to
  detect: no `~/.claude/ide-events/sessions/<id>.json` ~15 s after the resume means no
  SessionStart hook fired, so Claude never started. Log a warning naming the tab and the
  session. Note this is a different check from section 3's "did not start within", which
  polls `TerminalView.sessionState` - that is the SHELL; a session stopped at a dialog
  has a started shell with Claude sitting at a prompt.

  Scope note (2026-09-19): this does NOT cover the development-channels dialog, which
  fires on every launch rather than once - a per-launch stall is not something a detector
  fixes, it just reports N of them per restart. That case is closed by using
  `--channels` with a managed-settings allowlist instead of the dev flag (plan decision
  5); the detector remains worth having for the one-off cases above.

## Architectural / meta

- IntelliJ IDEA moved to 2026.2 (config dir `IntelliJIdea2026.2`); the 2026.1 dirs of
  both IDEs are obsolete.
- JetBrains Marketplace publishing has never been done (`publishing.token` wired in
  Gradle, no signing certs configured).
