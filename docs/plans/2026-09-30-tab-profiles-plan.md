---
created: 2026-09-30
session_id: 4e695d61-3fa2-40af-8f76-45977b1cddfa
status: active
parent_spec: docs/handoffs/2026-09-30-tab-profiles.md
related_roadmap_items: [tab-profiles]
---

# Tab profiles - implementation plan (v0.9.0)

Hand-off: `docs/handoffs/2026-09-30-tab-profiles.md`. Branch `feature/tab-profiles`,
stacked on `feature/session-title-sync` (0.8.0, built, never installed): both change
the same launch path (`LaunchCommand`, `TerminalHelper.executeCommandInTerminal`), and
profile tabs need a rule against 0.8.0's inbound title sync (below). One live check then
covers 0.8.0 and 0.9.0.

## Verified facts (2026-09-30, Claude Code 2.1.278)

- Acme's IDE project is `~/Projects/Acme/source/acme-server`; the worktrees
  `~/Projects/Acme/worktrees/worker{1,2}` are linked worktrees of it (`.git` is a
  file `gitdir: .../acme-server/.git/worktrees/workerN`), outside the project folder.
- A `acme-worker2` tab already exists in that project, bound to a session whose registry
  `cwd` is the main clone.
- `claude --resume <id>` from a different directory works: same session id, Claude runs
  in the new directory, and new records (with the new `cwd`) are appended to the
  original transcript in the original project slug directory.
- The reworked terminal has `TerminalToolWindowTabsManager.createTabBuilder()` with
  `workingDirectory`, `tabName`, `requestFocus`, `deferSessionStartUntilUiShown`,
  `createTab()` (WS 262.10315); `tabName` alone is not a user-defined title.
- Managed settings on this Mac already allow `inbox-channel@local-tools`.

## Where the code disagrees with the hand-off

1. **The hook needs no change.** It writes the hook payload's `cwd`, which is where
   Claude runs. What wrote the project folder was the plugin: `registerSession` falls
   back to `project.basePath` when it publishes before the hook. And every detection
   channel refuses a session whose cwd is outside `project.basePath` (hook channel
   `isDirectoryMatch`, output channel's delayed verification, the close listener's
   "live elsewhere" test), so a worktree session would never bind, never be resumed,
   and its registry file would be kept on close. Those guards are the real change.
2. **A profile tab must not inherit a session that ran elsewhere.** A cross-directory
   resume works (above), so the existing `acme-worker2` conversation would continue in
   the worktree with a context full of main-clone absolute paths. Automatic launches
   (auto-resume, keep-open, "Open profile tabs") resume a profile tab's session only
   when the directory it last ran in (registry `cwd`, else the last `cwd` in the
   transcript tail) is under the profile directory, or unknown; otherwise they start
   a fresh session there and say so. Manual resume actions do what the user picked.
3. **Profile tabs are exempt from inbound title sync (0.8.0).** A `/rename` in the
   session would rename the tab and, by rule 4 of the hand-off, silently end the
   profile. The title is still recorded and published as registry `title`.
4. **Keep-open follows auto-resume.** With "Auto-resume on startup" off, the plugin
   launches nothing at project open, profile tabs included.
5. **"Session Manager UI"** has no tool window; per-tab open is an entry per profile in
   the terminal's new-tab dropdown and an "Open Profile Tab" submenu in the tab context
   menu. The settings path is Settings > Tools > Claude Session Manager > Launch Profile.
6. **BRAT** is Obsidian's beta installer and does not apply; install is the plugin zip.
   Release follows CLAUDE.md: `./scripts/release.sh` only after a live check in at
   least one IDE, push only when asked.

## Rules

- **Identity.** A tab is a profile tab while its persistent name (D1) equals a
  profile's name exactly. Renaming the tab ends that; nothing else is stored.
- **Per-tab root.** The directory a tab's sessions must run under is its profile
  directory, else the project folder. Every cwd guard becomes "under this tab's root";
  project-level filters accept the project folder or any profile directory. With no
  profiles both reduce to today's `project.basePath` check (hand-off rule 5).
- **Launch.** Profile tab: `cd '<dir>' && <claude> <profile args or project default>
  [--resume <id>] [--name '<n>']`. Every other tab: unchanged, byte for byte.
- **Validation.** Errors (refuse to save; profiles failing them are also ignored on
  read, since `workspace.xml` is not guaranteed private): blank or duplicate name, name
  over 100 characters or with control characters, directory blank / not absolute after
  `~` expansion / containing `'`, `\` or control characters, arguments rejected by
  `LaunchArgsValidator`. Warnings (saved, shown on the page): directory missing, not
  inside a git work tree (no `.git` file or directory in it or an ancestor), two
  profiles with the same directory.

## Steps

Status 2026-09-30: all steps done; 90 unit tests, `verifyPlugin` Compatible on
WS-261.27258.45, 262.10968.77, 263.5701.38. Found while implementing and added: T5 in
the amendment (a fresh session in a background profile tab was otherwise never bound -
its SessionStart went to the selected tab and the new per-tab root refused it), and the
notification for an open profile tab whose Claude runs elsewhere.

- [x] 1. `model/TabProfile.kt` + `util/TabProfiles.kt` (pure): `expandHome`,
      `validate(profiles, home) -> errors, warnings`, `find(profiles, name)`,
      `rootFor(basePath, profile)`, `acceptsCwd(root, cwd)` (raw and canonical forms),
      `shellQuotedDirectory`. Unit tests.
- [x] 2. `util/TranscriptTail.kt`: bounded tail read shared by `SessionTitleReader`
      and a new `lastCwd(file)`; tests for `lastCwd`.
- [x] 3. `ProjectSettingsService`: `tabProfiles` list (plain `var` classes, XmlSerializer),
      validated on read, `profileFor(persistentName)`, `rootFor(persistentName)`,
      `allRoots()`.
- [x] 4. `LaunchCommand.resume/start` take an optional working directory (cd prefix);
      `TerminalHelper.buildResumeCommand/buildStartCommand/executeCommandInTerminal`
      resolve the tab's profile from its live persistent name. Tests.
- [x] 5. `TerminalHelper.createProfileTab(project, name, dir, activate)` via the tab
      builder, then `userDefinedTitle = name`.
- [x] 6. `services/TabProfileService.kt`: `openProfileTab`, `openMissingProfileTabs`,
      `openKeptTabs` (startup), `sessionBelongsToProfile`. Resumes the stale binding
      that carries the profile's name when its session belongs there and is not live
      elsewhere; else starts fresh with `expectFreshSession`.
- [x] 7. Guards: hook channel (project-level filter on all roots, per-tab check on the
      selected tab), output channel verification (per-tab root), index-watch channel
      (per-tab root), `RegistryProjectCloseListener` (all roots),
      `TabBindingCoordinator.registerSession` (profile directory before
      `project.basePath`), `SessionTitleSyncService` (no inbound rename of profile tabs).
- [x] 8. `AutoResumeService`: profile-aware resume (via step 4), start fresh for a
      profile tab whose session ran elsewhere, `openKeptTabs` after the resume loop,
      keep-open bindings excluded from the "missing tabs" notification.
- [x] 9. UI: Launch Profile page table (name, directory, arguments, keep open) with
      warnings label and errors on apply; actions "Open Profile Tabs" (terminal tab
      menu, Tools menu), "Open Profile Tab" submenu, new-tab dropdown entries.
- [x] 10. Docs and version: PRD amendment spec, PRD sections, roadmap, CHANGELOG,
      change-notes, README, `pluginVersion = 0.9.0`.
- [x] 11. Gate: `./gradlew test buildPlugin verifyPlugin`; install into both IDEs.

## Found during the first restart (2026-09-30)

- [x] WebStorm hung restoring 26 tabs (17:03): `BindingService.syncToState` ran
      `project.save()` on the UI thread; the 0.8.0 title poll was parked in
      `invokeAndWait`. Both fixed; `NoBlockingUiWaitsTest` and
      `scripts/check-freezes.sh` (runbook "Freeze check after a multi-tab restore").
- [x] Title sync renamed user-named tabs on first sight - fixed (spec 2026-09-19,
      revision 2026-09-30).

## Live verification (Acme, after install and restart)

- [ ] Enter the two profiles; the page shows no warning for them.
- [ ] "Open Profile Tabs": two tabs `acme-worker1`, `acme-worker2`; each runs
      `cd '<worktree>' && claude --channels plugin:inbox-channel@local-tools`; the existing
      main-clone session of `acme-worker2` is not carried over (INFO line
      "ran in ... not under profile directory").
- [ ] Registry files for both sessions: `"tab": "acme-workerN"`,
      `"cwd": ".../worktrees/workerN"`, `"project": "acme-server"`.
- [ ] Close and reopen the project: both tabs come back and resume their sessions in
      the worktrees; other tabs resume in the main clone with the project default.
- [ ] Close one profile tab, reopen the project: keep-open recreates it and resumes.
- [ ] Rename a profile tab: next launch in it uses the project default, no `cd`.
- [ ] 0.8.0 checklist (`docs/plans/2026-09-19-session-title-sync-plan.md`).
- [ ] `scripts/check-freezes.sh WebStorm2026.2` exits 0 after the restart.
