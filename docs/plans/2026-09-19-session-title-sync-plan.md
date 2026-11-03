---
created: 2026-09-19
session_id: 4e695d61-3fa2-40af-8f76-45977b1cddfa
status: active
parent_spec: docs/specs/2026-09-19-session-title-sync-amendment.md
related_roadmap_items: [session-title-sync]
---

# Session title sync - implementation plan (v0.8.0)

Spec: `docs/specs/2026-09-19-session-title-sync-amendment.md` (S1-S6). Branch
`feature/session-title-sync` from `main` at `77b9ed4` (0.7.0, unreleased - this ships as
0.8.0 after 0.7.0 is cut). Every step lists its files; unit-tested pieces are pure and
IDE-free, terminal-touching code is verified live and reported as such.

Repo rules that apply to every step: comments explain WHY; guard-clause exits at INFO;
settings classes are plain `var` + defaults; no emoji; nothing written under `~/.claude/`
beyond D13 (the sidecar and the transcript are READ only).

## Status (2026-09-19)

- [x] Steps 1-11 implemented on `feature/session-title-sync`; 75 unit tests green,
      `verifyPlugin` Compatible on WS-261/262/263, zip 0.8.0 built.
- [x] Review: `/implement-reviewed-gpt` (gpt-6-astra, 3 passes, no Claude fleet) - 6
      findings, all in the new files, all tagged NEEDS-REVIEW so the skill auto-applied
      none. Reviewed and applied by hand, all six:
      F2 binding writes moved to the EDT with re-read and tab/session revalidation;
      PERF-01 registry read for the push moved to a pooled thread (push is now
      callback-based); F3 a rename back to the session's title clears a stale pending
      push; F5 tail-window truncation decided on the byte offset, not the decoded
      length (regression test with Czech text); F4 `reapplyTitles()` runs when inbound
      sync is switched on; F1 the typed `/rename` path additionally rejects
      `; | & $ < >` and backticks, and a session whose registry entry names another IDE
      project is not typed into. The full ownership check GPT asked for (prove the tab's
      foreground process is that Claude pid) is not available through the terminal API;
      the heuristic and the character rule are what shipped.
- [~] Live verification checklist (below) - partly observed 2026-09-30..2026-10-05 in
      WebStorm (installed 2026-09-30 with 0.9.0, which carries 0.8.0). Inbound rename
      works and persists; the outbound push has never run; one defect found (registry
      `title` lost on restart, "Found during live verification" below), fixed in 0.9.1
      and verified live 2026-11-03.

## Step 1 - Binding model carries the title state

Files: `model/SessionBinding.kt`, `services/BindingService.kt`,
`src/test/.../model/SessionBindingResumeTest.kt` (or a new `SessionBindingTitleTest.kt`).

- `SessionBinding` gains `val sessionTitle: String? = null` and
  `val pendingTitlePush: String? = null`. `withNewSession` clears both (S5) - titles are
  per session, and a stale pending push would otherwise be typed into the wrong session.
- `BindingService.BindingEntry` gains `var sessionTitle: String? = null`,
  `var pendingTitlePush: String? = null`; `entryToBinding`/`bindingToEntry` map them.
- `BindingService.recordSessionTitle(bindingId, title: String?)` and
  `BindingService.setPendingTitlePush(bindingId, name: String?)`: no-op when unchanged,
  else copy + `syncToState()` + INFO log. Metadata writers next to
  `updatePersistentName`; they never change which session a tab holds, so D2 is intact.
- Tests: `withNewSession` clears both fields; same session id keeps them.

## Step 2 - SessionTitleReader (pure)

Files: `util/SessionTitleReader.kt`, `src/test/.../util/SessionTitleReaderTest.kt`.

```kotlin
object SessionTitleReader {
    const val TAIL_BYTES = 256 * 1024L
    data class Fingerprint(val transcriptLength: Long, val sidecarModified: Long)
    fun sidecarFile(sessionFile: File): File   // <dir>/<sessionId>/custom-title.json
    fun fingerprint(sessionFile: File): Fingerprint
    fun read(sessionFile: File): String?
}
```

- `read`: `RandomAccessFile` seek to `max(0, length - TAIL_BYTES)`, read to end, drop
  the first partial line when the window did not start at 0, drop a trailing partial
  line (Claude may be mid-write), scan lines from the END for the first one containing
  `"type":"custom-title"` and parse it leniently (`Json { ignoreUnknownKeys; isLenient }`
  into a small `@Serializable` record with defaults); blank -> continue scanning older
  records? No: the newest record wins even when blank -> treated as no title (S1).
  Fallback: sidecar JSON `customTitle`. Every failure returns null after an INFO log.
- Tests: newest tail record wins over older ones and over the sidecar; sidecar used when
  the tail has no record; blank record -> null; garbage line before the record is
  skipped; window boundary (record older than the window is missed and the sidecar
  answers); missing file -> null; `fingerprint` changes when the transcript grows and
  when the sidecar is rewritten.

## Step 3 - Launch command learns `--name`; validator reserves it

Files: `util/LaunchCommand.kt`, `util/LaunchArgsValidator.kt`, `util/SessionNameValidator.kt`
(new), tests `LaunchCommandTest`, `LaunchArgsValidatorTest`, `SessionNameValidatorTest`.

- `SessionNameValidator.isValid(name)`: non-blank after trim, length <= 200, no `'`, no
  `\`, no ISO control characters. `quoted(name)` = `'<name>'` (single quotes; nothing
  else is special inside them in sh/bash/zsh). Reason string for logs.
- `LaunchCommand.resume(claudeCommand, extraArgs, sessionId, name: String? = null)`:
  appends ` --name '<name>'` after `--resume <id>` when `name` is non-null; `require`
  it validates (last line, like the session id).
- `LaunchArgsValidator.RESERVED_FLAGS` += `--name`, `-n`; `COMBINED_SHORT_RESERVED`
  becomes `^-[a-zA-Z]*[rcn][a-zA-Z]*$`; rejection reason mentions naming. Existing test
  `rejects combined short flags that carry r or c` extends to `-dn`, `-nd`; `-d`, `-p`,
  `-v`, `-dv` stay valid.
- Tests: quoting, rejection of `'`/backslash/newline/201 chars, `--name` reserved in
  every spelling (`--name x`, `--name=x`, `-n x`, `-dn`).

## Step 4 - Registry field `title`

Files: `services/SessionRegistryService.kt`, `SessionRegistryServiceTest`.

- `upsert(sessionId, project, tab, cwd = null, title: String? = null, dir = ...)`: null
  leaves an existing `title` untouched (the rename listener and the coordinator do not
  know the title); non-blank sets it. Unchanged-file short-circuit still holds.
- `Entry` gains `val title: String?`; `read` maps it.
- Tests: title written; a later upsert without title keeps it; read returns it.

## Step 5 - TerminalHelper: rename a tab, type into a tab, name on resume

File: `terminal/TerminalHelper.kt`.

- `setUserDefinedTitle(project, content, name): Boolean` - `findTerminalTitle` then
  `title.change { userDefinedTitle = name }` on the EDT. INFO on failure. This IS the
  persistent name (D1), so the title listener in `TerminalOutputMonitorService` follows
  with `updatePersistentName` + registry `tab`, the same path a manual rename takes.
- `sendTextToTab(project, content, text): Boolean` - reworked terminal: tab by stable
  id, `createSendTextBuilder().send(text)` WITHOUT `shouldExecute()` - the caller ends
  `text` with `\r` because a LF leaves the command sitting in Claude's prompt box
  (verified through a pty, spec "Verified facts"). Classic widget fallback by name via
  `widget.terminalStarter?.sendString(text, true)`. Returns false with a WARN when no
  terminal is found.
- `buildResumeCommand(project, sessionId, name: String? = null)` and
  `executeCommandInTerminal(project, context, sessionId, name: String? = null)` thread
  the name to `LaunchCommand.resume`.

## Step 6 - SessionTitleSyncService

File: `services/SessionTitleSyncService.kt` (new, `@Service(Service.Level.PROJECT)`,
`Disposable`).

State: `fingerprints: ConcurrentHashMap<bindingId, Pair<sessionId, Fingerprint>>`,
`@Volatile started`, a `ScheduledFuture` from
`AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::pollSafely,
5, 5, SECONDS)` cancelled in `dispose()`.

- `start()`: idempotent; called from `AutoResumeService.startSessionWatcher` next to
  the other watchers.
- `requestSync()`: schedules one `poll()` ~1 s out on the pooled executor (debounced
  by a flag) - called by `TabBindingCoordinator.bindOrUpdate` on every successful
  return so a fresh bind or a SessionStart applies the title promptly.
- `poll()` (pooled thread): skip entirely while
  `TabBindingCoordinator.getInstance(project).isSuppressed()` (D6). For each binding
  with a live tab (`tabUuid` resolvable via `TerminalHelper.findContentByTabId`):
  `findSessionFile(currentSessionId)` (null -> skip, INFO once per binding+session);
  fingerprint; unchanged -> skip; `SessionTitleReader.read`; `applyInbound(binding,
  content, title)`.
- `applyInbound`: `title == binding.sessionTitle` -> nothing. Else
  `recordSessionTitle`; registry upsert with `title`; if `title != null` and
  `binding.pendingTitlePush != null` -> `setPendingTitlePush(null)` (delivered, or a
  newer Claude-side rename wins - S3 last change wins); if `syncTabNames` and `title !=
  null` and `title != persistentName`: duplicate guard (`getAllBindings().any { it.id !=
  binding.id && it.tabIdentifier.persistentName == title }` -> INFO skip) else
  `invokeLater { TerminalHelper.setUserDefinedTitle(...) }` + INFO "Applied Claude
  session title '<t>' to tab '<old>'".
- `onTabRenamed(tabId, newName)` (called from the title listener on the EDT after
  `updatePersistentName`): `pushTabNameToSession` off -> return; binding for tab ->
  none: return; `newName == binding.sessionTitle` -> inbound echo, return;
  `pushTabName(binding, content, newName, interactive = false)`.
- `pushTabName(binding, content, name, interactive): PushOutcome`
  (`SENT`, `DEFERRED(reason)`, `INVALID(reason)`):
  `SessionNameValidator` fails -> INVALID (INFO; pending NOT set - it could never be
  delivered). `SessionRegistryService.read(binding.currentSessionId)`: null -> DEFERRED
  ("no registry entry - hooks installed?"); `!isLive()` -> DEFERRED ("not running");
  `state != "idle"` -> DEFERRED ("session is <state>"). Else
  `sendTextToTab(project, content, "/rename $name\r")` -> SENT: keep
  `pendingTitlePush = name` until the inbound poll confirms; `requestSync()`. On
  DEFERRED: `setPendingTitlePush(name)`, INFO, and (only when not interactive, once per
  rename) a balloon via `NotificationHelper.create(...)` with `addAction("Push now")`
  -> `pushTabName(..., interactive = true)`; interactive outcomes are reported by a
  short balloon each.
- Every catch logs WARN with the binding id; `CancellationException` is rethrown.

Wiring edits: `TerminalOutputMonitorService` title listener calls
`SessionTitleSyncService.getInstance(project).onTabRenamed(tabId, persistentName)` in
the `invokeLater` block after the binding update; `TabBindingCoordinator.bindOrUpdate`
calls `requestSync()` before each non-null return (a private `synced(binding)` helper
keeps it to one line per site); `AutoResumeService.startSessionWatcher` starts it.

## Step 7 - Auto-resume delivers a pending push

File: `services/AutoResumeService.kt`.

- Both `executeCommandInTerminal` sites (the in-place resume around line 315 and the
  recreated-tab resume around line 672) pass
  `name = binding.pendingTitlePush?.takeIf { sessionId == binding.currentSessionId }`.
  A history fallback id is a different session (D4) and must not inherit the name.
- Pending is NOT cleared on send; the inbound poll clears it when the title matches.
  INFO line: "Resuming <id> with --name '<n>' (pending tab-name push)".

## Step 8 - Settings

Files: `model/PluginSettings.kt`, `services/SettingsService.kt`,
`settings/PluginSettingsConfigurable.kt`.

- `pushTabNameToSession: Boolean = true` in all three layers (data class field with
  default, `State` var, `getSettings`/`updateSettings`, property accessor, configurable
  field + `isModified`/`apply`/`reset`).
- "Tab Synchronization" group: existing checkbox keeps its label; new checkbox "Push
  IDE tab renames to the Claude session (/rename)" with comment: "Sent only when the
  session is idle; a busy or waiting session gets the name at its next resume or via
  the tab menu action. An unsent draft in the prompt box would be submitted with it."

## Step 9 - Context-menu action

Files: `actions/PushTabNameAction.kt` (new), `plugin.xml` (action in
`ClaudeSessionManager.TerminalPopup` after Resume Session).

- `getActionUpdateThread` = BGT like the siblings; enabled when the tab has a binding
  with a persistent name. `actionPerformed`: `pushTabName(binding, content, name,
  interactive = true)`; outcome balloon (SENT: "Pushed '<n>' to the Claude session";
  DEFERRED: the reason; INVALID: the reason).

## Step 10 - Docs and version

Files: `docs/prd.md` (Amendments list entry + the section edits listed under
"Supersedes in parent PRD" in the spec), `docs/roadmap.md` (in-flight entry ->
completed on merge; new item "Live-verify session title sync"), `CHANGELOG.md` +
`plugin.xml` change-notes (0.8.0 block), `README.md` (feature bullet, settings table row,
registry `title` field), `gradle.properties` `pluginVersion = 0.8.0`, this plan's
checkboxes.

## Step 11 - Gate

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew test buildPlugin verifyPlugin
```

All tests green, `verifyPlugin` Compatible on the recommended targets. Then the
implementation summary lists what is unit tested versus what needs the live check.

## Live verification checklist (after install, both IDEs)

- [x] `/rename foo` in a bound tab renames the tab to `foo` within ~5 s; the log has
      "Applied Claude session title 'foo'"; the registry file shows `"title": "foo"`
      and `"tab": "foo"`; after an IDE restart the tab comes back as `foo` and resumes.
      Observed 2026-09-30 20:47 (WebStorm, Acme): session `8e9fd95c` "reports title
      'acme-aux' (was '')", applied to tab 'Main Claude'; registry `"tab": "acme-aux"`;
      the tab came back as `acme-aux` and resumed after the 10-01, 10-02 and two 10-04
      restarts. `"title"` failed on 2026-10-05 (defect below); holds since 0.9.1.
- [ ] (not exercised: no "Pushed '/rename" line in either IDE's retained logs, which
      reach back to 2026-09-27 in WebStorm and 2026-09-30 in IDEA)
      Rename an idle bound tab in the IDE to `bar`: Claude prints "Session renamed to:
      bar" - no stray newline in the prompt box (if the command sits unsent, the
      reworked terminal wraps `sendText` in bracketed paste: fall back to sending the
      text and a separate `\r`, and record it here).
- [ ] (not exercised: no "Title push deferred" line) Rename a busy tab: balloon "session is busy", `pendingTitlePush` in
      `workspace.xml`; "Push now" once idle delivers it; or restart -> the resume
      command carries `--name 'bar'` and the pending mark clears after start.
- [ ] (not exercised) Two tabs whose sessions share a title: second rename skipped with the INFO line,
      registry `title` still written.
- [ ] Launch Profile page rejects `--name x` and `-dn` (unit tested in
      `LaunchArgsValidatorTest`; the page itself not checked).

## Found during live verification (2026-10-05)

- [x] **Registry `title` is lost after the first restart.** Fixed 2026-10-07 in 0.9.1
      (`TabBindingCoordinator.registerSession`; `HookScriptTest` covers the restart
      sequence against the real hook script; the coordinator lookup itself is verified
      live only). Verified live 2026-11-03: after the WebStorm restarts at 12:49 and
      13:59, 18 of 26 registry files with a project carry `title`, `acme-aux`,
      `acme-main` and `pg18` among them - all three had none on 2026-10-05. Every titled Acme session
      (`acme-aux`, `acme-main`, `pg18`, `unify-be-fe-translation-keys`) has a registry file
      without `title` on 2026-10-05. `SessionTitleSyncService.applyInbound` publishes
      `title` only when the title differs from `binding.sessionTitle`; `SessionEnd` (hook)
      and `RegistryProjectCloseListener` delete the file, the next launch recreates it from
      the hook plus `TabBindingCoordinator.registerSession`/`publishLaunched` (project,
      tab, cwd - no title), and the poll then sees an unchanged title and writes nothing.
      Fix: `registerSession` looks up the binding that holds the session and passes its
      non-blank `sessionTitle` to `SessionRegistryService.upsert` (null leaves an existing
      value alone; the hook's `. + {...}` merge keeps it when the hook writes second);
      a unit test on the publish path.
