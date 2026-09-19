#!/bin/bash
# Claude Session Manager Plugin Hook
# csm-hook-version: 2
#
# Installed by the Claude Session Manager IDE plugin as a Claude Code hook for the
# events SessionStart, UserPromptSubmit, PreToolUse, Notification (permission_prompt),
# Stop and SessionEnd. Reads one hook event (JSON) from stdin and maintains
#
#   ~/.claude/ide-events/current-session.json   last SessionStart event (the plugin watches it)
#   ~/.claude/ide-events/sessions/<id>.json     one file per live session:
#     { session_id, cwd, state, updated_at, pid, project, tab }
#
# state is idle | busy | waiting. project and tab are added by the IDE plugin; this
# script merges into the existing file so they survive. Files are replaced atomically
# (temp file + rename). pid is the Claude process that ran the hook - a reader can
# detect a dead session with `kill -0 <pid>` instead of waiting for the plugin's sweep.
#
# Never prints to stdout (Claude Code would interpret JSON output) and always exits 0.

INPUT=$(cat)
command -v jq >/dev/null 2>&1 || exit 0

# One jq call extracts every field, shell-quoted so eval is safe.
eval "$(printf '%s' "$INPUT" | jq -r '@sh "EVENT=\(.hook_event_name // "") SESSION_ID=\(.session_id // "") SOURCE=\(.source // "") CWD=\(.cwd // "") NOTIFICATION=\(.notification_type // "")"' 2>/dev/null)"

UUID_RE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
[[ "$SESSION_ID" =~ $UUID_RE ]] || exit 0

EVENTS_DIR="${CLAUDE_IDE_EVENTS_DIR:-$HOME/.claude/ide-events}"
SESSIONS_DIR="$EVENTS_DIR/sessions"
FILE="$SESSIONS_DIR/$SESSION_ID.json"
mkdir -p "$SESSIONS_DIR" 2>/dev/null || exit 0

# ISO-8601 with a colon in the offset; macOS date has no %:z.
NOW=$(date "+%Y-%m-%dT%H:%M:%S %z")
UPDATED_AT="${NOW% *}${NOW##* }"
UPDATED_AT="${UPDATED_AT:0:22}:${UPDATED_AT:22:2}"

# Nearest ancestor that is the Claude process (the hook runs as its child, usually via
# `sh -c`). Only computed when the file does not carry a pid yet - it costs several ps
# calls and PreToolUse fires on every tool call.
find_claude_pid() {
  local pid="$PPID" depth=0 cmd first base
  while [ -n "$pid" ] && [ "$pid" -gt 1 ] && [ "$depth" -lt 8 ]; do
    cmd=$(ps -o command= -p "$pid" 2>/dev/null)
    first="${cmd%% *}"
    base="${first##*/}"
    case "$base" in
      claude) echo "$pid"; return ;;
      node|bun) case "$cmd" in *claude*) echo "$pid"; return ;; esac ;;
    esac
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
    depth=$((depth + 1))
  done
}

# Merge fields into the session file: existing (if valid JSON) + the new values.
upsert() {
  local state="$1" pid="" existing="{}" tmp
  if [ -s "$FILE" ] && jq -e 'type == "object"' "$FILE" >/dev/null 2>&1; then
    existing=$(cat "$FILE")
  fi
  if [ "$EVENT" = "SessionStart" ] || ! printf '%s' "$existing" | jq -e '.pid' >/dev/null 2>&1; then
    pid=$(find_claude_pid)
  fi
  tmp="$SESSIONS_DIR/.$SESSION_ID.$$.tmp"
  if printf '%s' "$existing" | jq --arg sid "$SESSION_ID" --arg cwd "$CWD" --arg state "$state" \
       --arg ts "$UPDATED_AT" --arg pid "$pid" \
       '. + {session_id: $sid, updated_at: $ts}
        + (if $cwd != "" then {cwd: $cwd} else {} end)
        + (if $state != "" then {state: $state} else {} end)
        + (if $pid != "" then {pid: ($pid | tonumber)} else {} end)' > "$tmp" 2>/dev/null; then
    mv -f "$tmp" "$FILE" 2>/dev/null
  fi
  rm -f "$tmp" 2>/dev/null
}

case "$EVENT" in
  SessionStart)
    # Milliseconds: the plugin dedups events on this timestamp and several sessions
    # start within one second during a multi-tab resume.
    if command -v gdate >/dev/null 2>&1; then
      TIMESTAMP=$(gdate +%s%3N)
    elif command -v perl >/dev/null 2>&1; then
      TIMESTAMP=$(perl -MTime::HiRes=time -e 'printf("%d\n", time * 1000)')
    else
      TIMESTAMP=$(( $(date +%s) * 1000 ))
    fi
    tmp="$EVENTS_DIR/.current-session.$$.tmp"
    if jq -n --arg ts "$TIMESTAMP" --arg sid "$SESSION_ID" --arg src "$SOURCE" --arg cwd "$CWD" \
         '{timestamp: ($ts | tonumber), sessionId: $sid, source: $src, cwd: $cwd}' > "$tmp" 2>/dev/null; then
      mv -f "$tmp" "$EVENTS_DIR/current-session.json" 2>/dev/null
    fi
    rm -f "$tmp" 2>/dev/null
    upsert idle
    ;;
  UserPromptSubmit|PreToolUse)
    upsert busy
    ;;
  Notification)
    # The settings.json matcher already limits this to permission_prompt; guard anyway
    # so a broader matcher never marks an idle session as waiting.
    [ "$NOTIFICATION" = "permission_prompt" ] && upsert waiting
    ;;
  Stop)
    upsert idle
    ;;
  SessionEnd)
    rm -f "$FILE" 2>/dev/null
    ;;
esac

exit 0
