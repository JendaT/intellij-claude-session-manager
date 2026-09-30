---
created: 2026-09-30
source: brief pasted into the plugin's development session (the orchestrator / Acme planning)
---

# Hand-off: tab profiles

Verbatim as received.

Add "tab profiles" to Claude Session Manager: named terminal tabs that start
Claude in a different working directory with their own launch arguments,
configured per project.

Why: in the Acme project, two tabs must act as proxies for git worktrees
outside the project folder, so no extra WebStorm project is needed per
worktree. All other tabs keep today's behaviour exactly.

Configuration (Settings > Claude Session Manager > Launch Profile, stored per
project like extraLaunchArgs today, services/ProjectSettingsService.kt):
- The project default (extraLaunchArgs) stays as it is and applies to every
  tab without a profile.
- New list "Tab profiles", each entry: tab name (exact, unique), working
  directory (absolute path; ~ expanded), launch arguments (optional; empty
  means the project default), and whether the plugin keeps this tab open.
- Acme entries to enter by hand after the change:
    acme-worker1  ~/Projects/Acme/worktrees/worker1  --channels plugin:inbox-channel@local-tools
    acme-worker2  ~/Projects/Acme/worktrees/worker2  --channels plugin:inbox-channel@local-tools

Behaviour:
1. A profile tab opens as a terminal tab named exactly as configured, cds into
   its working directory, and launches `claude <args> [--resume <id>]` through
   the existing launch path (util/LaunchCommand.kt, terminal/TerminalHelper.kt
   around 541/555/562). Reuse LaunchArgsValidator for its arguments.
2. An action "Open profile tabs" (and per-tab open from the Session Manager
   UI) opens missing profile tabs. Profile tabs marked "keep open" are
   restored by multi-tab restore on project open, with the right directory
   and arguments, resuming their last session.
3. The session registry and the hook (~/.claude/hooks/session-notify.sh ->
   ~/.claude/ide-events/sessions/<id>.json) must record the tab's configured
   name and the directory Claude actually runs in (the worktree), not the
   project folder. external readers join tabs to worktrees by that cwd.
4. Validation: warn if the directory does not exist or is not a git worktree;
   refuse duplicate tab names; a tab the user renames stops being a profile tab.
5. Projects without tab profiles behave exactly as today.

Acceptance: in the Acme project, "Open profile tabs" gives two tabs named
acme-worker1 and acme-worker2 running Claude in the two worktrees with
--channels; their registry entries show those names and worktree paths; they
come back after reopening the project; every other tab still starts in the
main clone with the project default. Add tests where the plugin has them,
follow the repo's release process, and state the version for BRAT/manual
install.
