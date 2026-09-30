---
created: 2026-09-30
status: active
type: prd-amendment
---

# Tab profiles (v0.9.0)

**Parent PRD:** `docs/prd.md` - sections 2 (new D16), 5 (detection channels, registry),
6 (startup), 7 (settings), 8 (user surface), 9 (known limitations).
**Input:** `docs/handoffs/2026-09-30-tab-profiles.md`. **Plan:**
`docs/plans/2026-09-30-tab-profiles-plan.md` (verified facts, where the code disagreed
with the hand-off).

## Problem

A git worktree outside the project folder (Acme's `worktrees/worker1`, `worker2`)
needed an IDE project of its own to get a Claude tab that starts there with its own
channel flag. And every detection channel refused a session whose cwd was outside the
project folder, so such a session could not be bound, resumed or published under its
tab even when started by hand.

## Design

**T1 Profile.** Per project (`ProjectSettingsService`, workspace file): tab name (exact,
unique), working directory (absolute, `~` expanded at use), launch arguments (blank =
project default), keep open. Validated on save and again on read: an entry that fails
(name blank/duplicate/over 100 chars/control characters; directory not absolute or
containing `'`, `\` or control characters; arguments refused by `LaunchArgsValidator`)
is refused by the page and ignored at run time. Warnings (saved): directory missing, not
inside a git work tree, shared by two profiles.

**T2 Identity.** A tab is a profile tab while its persistent name (D1) equals a
profile's name. A rename ends it; nothing else is stored.

**T3 Launch.** Profile tab: `cd '<dir>' && <claude> <profile args | project default>
[--resume <id>] [--name '<n>']` through `LaunchCommand`; every other tab's command is
unchanged. The `cd` is always typed (a restored tab can come back elsewhere); `&&` so a
missing directory launches nothing.

**T4 Per-tab root (D16).** The directory a tab's sessions must run under is its profile
directory, else the project folder. The hook channel's project filter accepts the
project folder or any profile directory and then requires the target tab's root; the
output channel's verification, the index-watch channel and the close listener's "live
elsewhere" test use the same roots. With no profiles every root is the project folder:
the checks from before.

**T5 Attribution by cwd.** A SessionStart whose cwd lies in exactly one profile
directory goes to that profile's open tab instead of the selected tab, unless that tab
is bound to a different session that is still running. Otherwise a fresh session in a
profile tab the user is not looking at would be refused by the selected tab's root and
never bound.

**T6 Resume only what ran there.** `claude --resume <id>` from another directory keeps
the conversation (verified 2026-09-30). Automatic launches (auto-resume, keep-open,
"Open Profile Tab(s)", "Recreate Tabs") resume a profile tab's session only when the
directory it last ran in (registry `cwd`, else the newest `cwd` in the transcript tail)
is under the profile directory or unknown; otherwise they start a fresh session there,
the old session staying in the binding's history. Manual resume actions are not
second-guessed.

**T7 Opening.** "Open Profile Tabs" (Tools menu, terminal tab menu), "Open Profile Tab"
submenu, and one "Claude: <name>" entry per profile in the terminal's new-tab dropdown.
A missing tab is created with the reworked terminal's tab builder (working directory,
user-defined title, shell started without waiting for display) and gets the resume of
the stale binding carrying its name (T6), or a fresh session, or - when that session is
running elsewhere - a plain shell and a notification. An open tab is never typed into;
if its bound session runs outside the profile directory the user is told how to fix it.

**T8 Keep open.** After the startup resume loop, keep-open profiles without an open tab
are opened as in T7; their bindings are not reported as missing or stale. Follows
"Auto-resume on startup": off means nothing is launched at project open.

**T9 Registry.** `tab` is the profile name (the persistent name); `cwd` is written by
the hook from where Claude runs. When the plugin publishes first, it uses the profile
directory, not the project folder. The hook script is unchanged (still
`csm-hook-version: 2`).

**T10 Title sync.** Inbound title sync (0.8.0) never renames a profile tab - a `/rename`
in the session would otherwise silently end the profile (T2). The title is recorded and
published as registry `title` as before.

## Supersedes in parent PRD

- Section 2: new rule D16 (T4).
- Section 5: the hook channel's attribution gains T5; every cwd check is per tab (T4).
- Section 6: keep-open profile tabs are opened after the resume loop (T8); profile tabs
  whose session ran elsewhere start fresh (T6).
- Section 7: project level gains `tabProfiles` (T1).
- Section 8: T7's actions.
- Section 9: an open profile tab is not retargeted (T7); "Bind to Session..." lists the
  project folder's sessions only; keep-open follows auto-resume (T8).

## Out of scope

Profiles shared between projects; per-profile environment variables; listing a
worktree's sessions in the session picker.
