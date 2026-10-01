#!/bin/bash
# Post-restart check for an IDE restart that restored many terminal tabs.
# Usage: scripts/check-restart.sh [WebStorm2026.2|IntelliJIdea2026.2]
# Reports, for the latest IDE start only:
#   1. thread-pool exhaustion and UI freezes, and this plugin's frames in thread dumps
#      (the 2026-09-30 17:03 hang);
#   2. every session the plugin auto-resumed or launched since the start whose registry
#      file lacks project or tab (the 2026-09-30 20:45 startup race).
# Exit 1 when something is found.
set -u
IDE="${1:-WebStorm2026.2}"
LOGDIR="$HOME/Library/Logs/JetBrains/$IDE"
[ -f "$LOGDIR/idea.log" ] || { echo "no log at $LOGDIR/idea.log"; exit 2; }
# The IDE rotates idea.log -> idea.1.log; the latest start may sit in the older file.
LOG=$(mktemp); trap 'rm -f "$LOG"' EXIT
cat "$LOGDIR/idea.1.log" "$LOGDIR/idea.log" 2>/dev/null > "$LOG"
START=$(grep -n 'IDE STARTED' "$LOG" | tail -1 | cut -d: -f1)
[ -n "$START" ] || { echo "no 'IDE STARTED' line in idea.log or idea.1.log"; exit 2; }
STARTED_AT=$(sed -n "${START}p" "$LOG" | cut -c1-19)
echo "$IDE: latest start $STARTED_AT"
tail -n +"$START" "$LOG" | grep -c 'Started monitoring terminal tab' | sed 's/^/  terminal tabs monitored: /'
tail -n +"$START" "$LOG" | grep -c 'Auto-resumed session' | sed 's/^/  sessions auto-resumed:   /'
PROBLEMS=$(tail -n +"$START" "$LOG" | grep -E 'Thread pool exhaustion|UI was frozen')
FOUND=0
if [ -n "$PROBLEMS" ]; then
  FOUND=1
  echo "  freeze / exhaustion lines:"
  echo "$PROBLEMS" | cut -c1-220 | sed 's/^/    /'
else
  echo "  no 'Thread pool exhaustion' or 'UI was frozen' since start"
fi
# Thread dumps written since the start: which of this plugin's stack frames they hold
# (coroutine-scope listings name the plugin in every dump; only "at ..." frames count).
STAMP=$(date -j -f '%Y-%m-%d %H:%M:%S' "$STARTED_AT" '+%Y%m%d%H%M.%S' 2>/dev/null)
if [ -n "$STAMP" ]; then
  REF=$(mktemp); touch -t "$STAMP" "$REF"
  DUMPS=$(find "$LOGDIR" -name 'threadDump*.txt' -newer "$REF")
  rm -f "$REF"
  if [ -n "$DUMPS" ]; then
    WITH=$(echo "$DUMPS" | xargs grep -l 'at com.github.jendalen.claudesessionmanager' 2>/dev/null)
    echo "  thread dumps since start: $(echo "$DUMPS" | wc -l | tr -d ' '), with plugin frames: $(echo -n "$WITH" | grep -c .)"
    if [ -n "$WITH" ]; then
      FOUND=1
      echo "$WITH" | xargs grep -h 'at com.github.jendalen.claudesessionmanager' | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -8 | sed 's/^/    /'
    fi
  fi
fi
# Registry completeness: each session the plugin typed a launch for must have project
# and tab in ~/.claude/ide-events/sessions/<id>.json. A missing file is reported but not
# counted: the session may have ended (SessionEnd deletes it).
python3 - "$LOG" "$START" "$HOME/.claude/ide-events/sessions" <<'PY' || FOUND=1
import json, os, re, sys
log, start, regdir = sys.argv[1], int(sys.argv[2]), sys.argv[3]
launched = {}
with open(log, errors="replace") as fh:
    for n, line in enumerate(fh, 1):
        if n < start:
            continue
        m = re.search(r"Auto-resumed session ([0-9a-f-]{36}) in tab '(.*)'", line) \
            or re.search(r"Opened profile tab '(.*)' and resumed session ([0-9a-f-]{36})", line) \
            or re.search(r"Started new session ([0-9a-f-]{36}) in profile tab '(.*)'", line)
        if not m:
            continue
        a, b = m.group(1), m.group(2)
        sid, tab = (a, b) if re.match(r"[0-9a-f-]{36}$", a) else (b, a)
        launched[sid] = tab
bad, gone = [], []
for sid, tab in launched.items():
    path = os.path.join(regdir, sid + ".json")
    if not os.path.exists(path):
        gone.append((sid, tab)); continue
    try:
        obj = json.load(open(path))
    except Exception:
        bad.append((sid, tab, "unreadable")); continue
    missing = [k for k in ("project", "tab") if not obj.get(k)]
    if missing:
        bad.append((sid, tab, "missing " + "/".join(missing)))
print(f"  registry: {len(launched)} launched since start, {len(launched) - len(bad) - len(gone)} complete, "
      f"{len(bad)} incomplete, {len(gone)} without a file (ended?)")
for sid, tab, why in bad:
    print(f"    {sid[:8]} '{tab}': {why}")
sys.exit(1 if bad else 0)
PY
exit $FOUND
