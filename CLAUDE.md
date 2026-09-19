# Claude Session Manager - Development Rules

JetBrains IDE plugin (Kotlin, JVM 21, IntelliJ Platform 261+) that binds Claude Code
sessions to terminal tabs and resumes them across IDE restarts. Repo:
`JendaT/intellij-claude-session-manager`. Global rules in `~/.claude/CLAUDE.md` apply
(direct tone, no emoji in code or docs, feature branches only, say "untested" honestly).

## Read order for every session

1. `docs/prd.md` - canonical description of the shipped design. The decisions log in
   section 2 is binding; do not re-litigate it without a dated decision record.
2. `docs/roadmap.md` - what is next. Answer "what should I work on" from here FIRST.
3. The active plan in `docs/plans/` (frontmatter `status: active`), if any.
4. `docs/handoffs/` - hand-off notes from planning chats. Immutable inputs; the plan
   that implements one records where the code disagrees with it.
5. `docs/runbooks/` - how to build, install, diagnose and audit on this machine.

## Documentation layout (strict separation)

- **`docs/prd.md`** - single canonical design doc reflecting current shipped state. No
  version suffix; git history records evolution. Post-implementation it freezes per
  feature: design changes land as dated amendment specs in `docs/specs/` with a
  `**Parent PRD:**` header and a `## Supersedes in parent PRD` section, plus an entry in
  the PRD's Amendments list. Tiny follow-ups (a few lines, no new public surface) may
  back-merge into the PRD as a `## Revision - vX.Y` block - sparingly.
- **`docs/specs/`** - authoritative design documents: PRD amendments, feature specs.
  One doc = one source of truth for a feature's shape.
- **`docs/handoffs/`** - `YYYY-MM-DD-<topic>.md`, written in planning chats, never edited
  after the fact. A plan references its hand-off as `parent_spec`.
- **`docs/plans/`** - implementation plans. Ephemeral relative to specs: written,
  executed, then frozen. Plans reference their spec, never vice versa.
- **`docs/decisions/`** - `YYYY-MM-DD-<topic>.md`, one decision with its evidence and a
  revisit trigger. Anything that overrides a PRD section 2 rule goes here first.
- **`docs/runbooks/`** - operational procedures (build, install, logs, audits).
- `README.md` is user-facing; `CHANGELOG.md` mirrors the `<change-notes>` in
  `src/main/resources/META-INF/plugin.xml` (both must be updated on a version bump).

## Plan lifecycle

- **Creation**: `docs/plans/YYYY-MM-DD-<topic>-plan.md` with YAML frontmatter:
  ```yaml
  created: YYYY-MM-DD
  session_id: <Claude Code session ID>
  status: active
  parent_spec: docs/handoffs/<file>.md or docs/specs/<file>.md or docs/prd.md section N
  related_roadmap_items: [..]
  ```
- **During development**: tick checkboxes as work lands. Related minor fixes append to
  the active plan; unrelated discoveries go to `docs/roadmap.md`.
- **Completion**: set `status: completed`, update `docs/prd.md` (or write an amendment),
  sync `docs/roadmap.md`, bump the version. Plans never move; status lives in frontmatter.

## Code rules

- Kotlin, IntelliJ Platform APIs; `sinceBuild = 261`, no `untilBuild`. Every build must
  pass `verifyPlugin` against the recommended targets (currently 261 and 262) - 262 removed
  API that 261 had (see PRD section 4); resolve such members reflectively, never call
  them directly.
- Comments explain WHY (the observed failure that motivated the code), matching the
  existing density. No emoji anywhere in the repo.
- Guard-clause exits in detection code log at INFO, not DEBUG - DEBUG is invisible in
  production logs and made past failures undiagnosable.
- `TabBindingCoordinator.bindOrUpdate` is the ONLY write path for tab-session bindings.
  New detection channels call it; they never touch `BindingService` directly.
- Files the plugin writes outside the project: `~/.claude/hooks/session-notify.sh`,
  hook entries and `env` in `~/.claude/settings.json` (merge, never overwrite other keys),
  `~/.claude/ide-events/`. Nothing else under `~/.claude/` is ever written.
- Settings classes are plain classes with `var` + defaults (XmlSerializer omits
  default-valued fields, so never gate a migration on a stored version int).
- Tests: `./gradlew test` (JUnit 5). Validators and command assembly get unit tests;
  anything touching the terminal is verified live and reported as such.

## Build, install, verify

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # shell default JDK 11 fails
./gradlew test buildPlugin verifyPlugin
# zip: build/distributions/claude-session-manager-<version>.zip
```
Install into BOTH IDE plugin dirs and restart both (details, log paths and the binding
audit in `docs/runbooks/build-install-diagnose.md`). `idea.log` is the source of truth;
`workspace.xml` is flushed lazily and lies during a session.

## Git and release

- Branches `feature/<name>` or `fix/<name>` from `main`; `--ff-only` merge into `main`;
  push only when asked.
- Version bump = `gradle.properties` + `plugin.xml` change-notes + `CHANGELOG.md`.
- GitHub release: `./scripts/release.sh` (tags `v<version>`, uploads the zip via `gh`).
  Cut a release only after the version has been verified live in at least one IDE.
