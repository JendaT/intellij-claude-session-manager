#!/bin/bash
# Freeze check after an IDE restart that restored many terminal tabs.
# Usage: scripts/check-freezes.sh [WebStorm2026.2|IntelliJIdea2026.2]
# Reports, for the latest IDE start only: thread-pool exhaustion and UI freezes, and
# whether any saved thread dump has this plugin on the UI thread or parked in a
# blocking wait. Exit 1 when something is found.
set -u
IDE="${1:-WebStorm2026.2}"
LOGDIR="$HOME/Library/Logs/JetBrains/$IDE"
LOG="$LOGDIR/idea.log"
[ -f "$LOG" ] || { echo "no log at $LOG"; exit 2; }
START=$(grep -n 'IDE STARTED' "$LOG" | tail -1 | cut -d: -f1)
STARTED_AT=$(sed -n "${START}p" "$LOG" | cut -c1-19)
echo "$IDE: latest start $STARTED_AT (line $START)"
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
exit $FOUND
