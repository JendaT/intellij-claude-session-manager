# Runbook - build, install, diagnose, audit

Machine-specific procedures for the plugin's development loop on the maintainer's Mac.
Paths change with IDE versions; always check `ls ~/Library/Logs/JetBrains/` first.

## Build and verify

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew test buildPlugin verifyPlugin
ls build/distributions/          # claude-session-manager-<version>.zip
```

- Compiles against the local `~/Applications/WebStorm.app` (override with
  `-PlocalIdePath=...`). `verifyPlugin` targets WebStorm 2026.1.5 (the 261 floor) and
  the same local WebStorm, pinned in `build.gradle.kts` (`recommended()` downloaded every
  newer release and EAP, ~4 GB each, and filled the disk), and must be clean; a
  `NoSuchMethodError`-class finding on one target means an API that the other target
  still has - resolve it reflectively.
- `buildSearchableOptions` is disabled (known issue with local IDE builds).

## Install

The user tests in BOTH IDEs. "JIdea" means IntelliJ IDEA.

```bash
V=$(grep ^pluginVersion gradle.properties | cut -d= -f2 | tr -d ' ')
for d in "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins" \
         "$HOME/Library/Application Support/JetBrains/WebStorm2026.2/plugins"; do
  rm -rf "$d/claude-session-manager" && unzip -q -o "build/distributions/claude-session-manager-$V.zip" -d "$d"
done
```

Restart both IDEs afterwards. Confirm the running version by comparing a log line's
wording with the current source - old builds log different phrases, and a version
mismatch once made every fix look broken (v0.6.1 installed into WebStorm only while the
user tested in IDEA).

Apps: `~/Applications/WebStorm.app`, `~/Applications/IntelliJ IDEA.app` (both 2026.2
since 2026-09; the 2026.1 config dirs are obsolete).

## Deploying across an IDE update

Order that preserved ~19 sessions across 2026.1 -> 2026.2 (2026-08-05):

1. Install the new plugin build, restart the IDE, confirm every binding re-attached
   (audit below and "Auto-resumed session" lines in the log).
2. Only then update the IDE. The IDE's settings import carries the plugin over.
3. Restart, audit again. Terminal API breakage arrived in a MINOR IDE update (2026.2),
   so treat every update as a compatibility event.

## Logs

```
~/Library/Logs/JetBrains/WebStorm2026.2/idea.log        (rotated: idea.1.log ... idea.12.log)
~/Library/Logs/JetBrains/IntelliJIdea2026.2/idea.log
```

Useful greps (plugin lines carry `claudesessionmanager`):

```bash
grep -n "Binding re-attachment starts\|bindings to auto-resume\|Auto-resumed session\|not found (or ambiguous)\|No session of binding\|Bind skipped\|Adopted stale\|Auto-bound\|Protected tab title\|Started shell for restored tab\|did not start within\|Session registry" idea.log
grep -n "Processing session event" idea.log     # hook events, with source and cwd
```

Timing tells the story: a "Processing session event ... source=resume" for a session
minutes after its "Auto-resumed session" line means the tab was launched by a click, not
by the plugin (lazy shell start, PRD section 4).

`workspace.xml` is flushed lazily - it lags the in-memory state for minutes. Never
conclude "binding lost" from the file while the IDE is running.

## Binding audit

```bash
scripts/audit-bindings.py \
  ~/Projects/Work/Shop ~/Projects/Work/Shop-worktree{1,2,3,4} \
  ~/Projects/Acme/source/acme-server
```

`ok` = current session has a transcript; `hist` = resume will fall back to an older
history id; `!!` = nothing to resume (orphaned binding, dismiss via the missing-tabs
notification or "Remove binding"). Transcripts live under `~/.claude/projects/<encoded
cwd>/<session-id>.jsonl`; Claude Code purges them after `cleanupPeriodDays` (default 30).

## Claude Code side

- Hook script: `~/.claude/hooks/session-notify.sh` (v2 marker `# csm-hook-version: 2`);
  entries in `~/.claude/settings.json` under `SessionStart`, `UserPromptSubmit`,
  `PreToolUse`, `Notification` (matcher `permission_prompt`), `Stop`, `SessionEnd`. Other
  tools own entries under the same events - never overwrite the file wholesale. The
  settings page's "Install Hooks" upgrades a v1 install in place.
- Events: `~/.claude/ide-events/current-session.json` (watched) and the registry
  `~/.claude/ide-events/sessions/<id>.json`. `sessions.jsonl` is a v1 leftover.
- Hook payloads carry `session_id`, `cwd`, `hook_event_name`, `source`
  (`startup|resume|clear|compact`), `notification_type` - no terminal identity.
- Exercise the script by hand: `CLAUDE_IDE_EVENTS_DIR=/tmp/ev bash
  src/main/resources/hooks/session-notify.sh <<< '{"hook_event_name":"Stop","session_id":"<uuid>"}'`.
- Watch a session flip: `watch -n1 'jq -c "{state,tab,pid}" ~/.claude/ide-events/sessions/*.json'`.

## Post-restart check (freezes, registry completeness)

After restarting an IDE that restores 10 or more terminal tabs (WebStorm with the
Shop and Acme projects restores ~26), run:

```bash
scripts/check-restart.sh WebStorm2026.2      # or IntelliJIdea2026.2
```

It reads `idea.log` (and `idea.1.log`, which holds the start after a rotation) from the
latest IDE start: tabs monitored, sessions auto-resumed, any `Thread pool exhaustion` /
`UI was frozen` line, this plugin's stack frames in thread dumps written since the
start, and every session the plugin launched since the start whose registry file lacks
`project` or `tab`. Exit 0 = clean. The 2026-09-30 20:45 start (before the launch-time
publish) had WT3-worker and WT2-worker incomplete; they were patched by hand at 20:54. On 2026-09-30 (17:03 start, 15
sessions resumed) it reported Dispatchers.Default unresponsive for over two minutes with
`BindingService.syncToState` -> `ProjectImpl.save` on the UI thread and the title poll
parked in `invokeAndWait` in every dump; `NoBlockingUiWaitsTest` keeps both out of the
sources.
