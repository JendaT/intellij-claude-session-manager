# 2026-10-08 - Placeholder names for the orchestrator and client projects

## Decision

The repository is public. Code, tests, docs and commit messages never name the external
orchestrator that consumes the session registry, its plugins or marketplaces, or the
owner's client and employer projects. They use placeholders instead:

| real thing | placeholder |
|---|---|
| the external orchestrator | "the orchestrator", "external readers" |
| its channel plugin | `plugin:inbox-channel@local-tools` |
| the spike channel plugin | `plugin:test-channel@spike-market`, directory `channel-spike` |
| client projects | `Acme` (`acme-server`, `acme-worker1`), `Shop` (`Shop-worktree1`), `Work` |

Hand-offs (`docs/handoffs/`) are immutable except for this redaction. An outbound note
written for the orchestrator's own planning belongs in that project's repository, not
here.

## Evidence

On 2026-10-07 a review before the first push since v0.6.6 found the orchestrator's name,
its internal spec and plan paths, the design of its inbox component, the spike's path
and port, and three client project names in 22 files and 5 commit messages. None of it
had been pushed. The 2026-09-16 external-session-control hand-off had already required
the plugin to stay generic ("no orchestrator-specific names"); the follow-up work
drifted from that. The unpushed commits were rewritten with the placeholders above, the
outbound hand-off was moved to the orchestrator's repository, and the commit that only
added it was dropped. The pre-rewrite commits are kept locally under
`refs/backup/pre-redact/`.

## Revisit when

The repository is made private, or the orchestrator is published under its own name and
the owner wants the integration documented by name.
