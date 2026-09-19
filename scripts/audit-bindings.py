#!/usr/bin/env python3
"""Audit Claude Session Manager bindings against transcripts on disk.

Usage: scripts/audit-bindings.py <project-dir> [<project-dir> ...]

For every binding in each project's .idea/workspace.xml (component
ClaudeSessionBindings) prints the tab name, the current session id and whether a
transcript exists for it, falling back through the session history the way
AutoResumeService.resolveResumableSessionId does. Status column:

  ok     current session has a transcript
  hist   current id has none, an older history id does (resume will use it)
  !!     no id of this binding has a transcript - resume cannot succeed

The IDE flushes workspace.xml lazily: run this after the IDE has been idle for a
while or after it was closed. idea.log is the source of truth during a session.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def transcript_ids(claude_home):
    ids = set()
    for path in glob.glob(os.path.join(claude_home, "projects", "*", "*.jsonl")):
        ids.add(os.path.basename(path)[:-len(".jsonl")].lower())
    return ids


def option(entry, name):
    node = entry.find(f"option[@name='{name}']")
    return node.get("value") if node is not None else None


def audit(project_dir, transcripts):
    ws = os.path.join(project_dir, ".idea", "workspace.xml")
    if not os.path.exists(ws):
        print(f"{project_dir}: no workspace.xml")
        return 0, 0
    component = ET.parse(ws).getroot().find(".//component[@name='ClaudeSessionBindings']")
    if component is None:
        print(f"{project_dir}: no ClaudeSessionBindings component")
        return 0, 0
    print(f"== {os.path.basename(project_dir)}")
    healthy = total = 0
    for entry in component.iter("BindingEntry"):
        total += 1
        tab = option(entry, "tabPersistentName") or option(entry, "tabName") or "?"
        current = (option(entry, "currentSessionId") or "").lower()
        history = []
        for h in entry.iter("HistoryEntry"):
            history.append((int(option(h, "replacedAt") or 0), (option(h, "sessionId") or "").lower()))
        history.sort(reverse=True)
        if current in transcripts:
            status, resolved = "ok", current
        else:
            resolved = next((sid for _, sid in history if sid in transcripts), None)
            status = "hist" if resolved else "!!"
        if status != "!!":
            healthy += 1
        note = "" if resolved in (None, current) else f" -> {resolved[:8]}"
        print(f"  {status:4} {tab:28.28} {current[:8]}{note}")
    return healthy, total


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    claude_home = os.environ.get("CLAUDE_HOME", os.path.expanduser("~/.claude"))
    transcripts = transcript_ids(claude_home)
    healthy = total = 0
    for project_dir in argv[1:]:
        h, t = audit(os.path.abspath(project_dir), transcripts)
        healthy += h
        total += t
    print(f"{healthy}/{total} bindings resolvable")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
