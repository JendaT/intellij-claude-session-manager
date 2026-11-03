# Claude Session Manager

A JetBrains IDE plugin that persists Claude Code sessions across terminal tabs.

Keep multiple Claude sessions organized - each terminal tab remembers its session. Sessions auto-resume when you restart the IDE.

## Features

- **Bind sessions to tabs** - Manually or automatically associate Claude sessions with terminal tabs
- **Auto-resume on IDE restart** - Bound sessions resume automatically when you reopen the project
- **Session history per tab** - Switch between previous sessions used in a tab
- **Detects session changes** - Automatically tracks `/clear`, `/compact`, `/resume` commands
- **Tab rename support** - Bindings follow when you rename terminal tabs
- **Session title sync** - `/rename` in Claude renames the tab (persistently); renaming the tab renames the session
- **Per-project launch profile** - Extra Claude launch arguments applied to every launch in a project
- **Tab profiles** - Named tabs that run Claude in another directory (e.g. a git worktree outside the project) with their own arguments
- **Session registry** - One JSON file per live session (`idle` / `busy` / `waiting`) for external tools

## Requirements

- JetBrains IDE 2026.1 or later (WebStorm, IntelliJ IDEA, PyCharm, etc.) with the reworked terminal
- [Claude Code CLI](https://claude.ai/code) installed

## Installation

### From JetBrains Marketplace
*(Coming soon)*

### Manual Installation
1. Download the latest `.zip` from [Releases](https://github.com/JendaT/intellij-claude-session-manager/releases)
2. In your IDE: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded zip file
4. Restart the IDE

## Setup

After installation, you need to install Claude Code hooks for reliable session detection:

1. Go to **Settings → Tools → Claude Session Manager**
2. Click **Install Hooks**
3. Confirm the installation

This creates a hook script that notifies the plugin when Claude sessions start or change. You can review exactly what gets installed in the "Integration Details" section.

### What the hooks do

The plugin installs:
- A script at `~/.claude/hooks/session-notify.sh`
- Hook entries in `~/.claude/settings.json` for `SessionStart`, `UserPromptSubmit`, `PreToolUse`,
  `Notification` (matcher `permission_prompt`), `Stop` and `SessionEnd`. Entries of other tools under
  the same events are kept; the installer only adds and removes its own.

On `SessionStart` the script writes `~/.claude/ide-events/current-session.json`, which the plugin
watches to bind the session to the selected tab. All events maintain the session registry below.
The settings page reports an outdated script version and offers the upgrade.

### Session registry

`~/.claude/ide-events/sessions/<session_id>.json`, one file per live session:

```json
{
  "session_id": "4e695d61-3fa2-40af-8f76-45977b1cddfa",
  "cwd": "/Users/me/Projects/Shop-worktree2",
  "state": "idle",
  "updated_at": "2026-09-17T09:12:03+02:00",
  "pid": 4452,
  "project": "Shop-worktree2",
  "tab": "payments",
  "title": "payments"
}
```

- `state`: `idle` (Stop, SessionStart), `busy` (UserPromptSubmit, PreToolUse), `waiting`
  (a permission prompt is open; cleared by the next PreToolUse or Stop).
- `pid`: the Claude process; `kill -0 <pid>` tells a reader whether the entry is dead.
- `project` and `tab`: written by the plugin when it binds the session and on tab rename
  (`tab` is the persisted tab name, not Claude's live status title).
- `title`: the session's own title (`/rename`, `--name`), written by the plugin when it
  observes it; equals `tab` once the two are in sync. Absent for sessions never named.
- Files are replaced atomically, removed on `SessionEnd` and when the project closes, and
  swept after 24 h. Readers should treat the directory as read-only.

## Usage

### Binding Sessions

**Automatic:** Start Claude in a terminal tab - the plugin detects and binds it automatically.

**Manual:** Right-click a terminal tab → **Claude Session → Bind to Session...** → Select a session.

### Context Menu

Right-click any terminal tab to access:
- **Bind to Session...** - Associate a Claude session with this tab
- **Resume Session** - Resume the bound session
- **Push Tab Name to Claude Session** - `/rename` the session to this tab's name (only while it is idle)
- **Open Profile Tabs / Open Profile Tab** - Open this project's tab profiles (shown only when the project has profiles)
- **Session History** - Switch to a previous session
- **Copy Session ID** - Copy the session UUID
- **Unbind Session** - Remove the binding
- **Settings...** - Open plugin settings (shows "action required" if hooks not installed)

### New Terminal with Session

Use the terminal dropdown (▼ next to +) → **Claude Session** to create a new terminal with a selected session.

## Configuration

**Settings → Tools → Claude Session Manager**

| Setting | Description |
|---------|-------------|
| **Auto-resume on startup** | Resume bound sessions when IDE starts |
| **Resume delay** | Wait time for terminal initialization (500-30000ms) |
| **Notify on session change** | Show notification when `/clear` etc. changes session |
| **Notify on auto-detection** | Show notification when Claude is auto-detected |
| **Sync Claude /rename to terminal tab name** | The session's title becomes the tab's persistent name. A title the session already had when first seen renames only a tab with a default name ("Local ...") |
| **Push IDE tab renames to the Claude session** | A renamed tab renames its session: `/rename` while idle, else `--name` at the next resume or the tab-menu action "Push Tab Name to Claude Session" |
| **Claude command** | CLI command (e.g., `claude` or a custom alias) |
| **Home directory** | Custom Claude home path (default: `~/.claude`) |
| **Protect bound tab names** | Bound tabs ignore Claude Code's terminal-title renaming |
| **Keep my custom tab names** | Sets `CLAUDE_CODE_DISABLE_TERMINAL_TITLE=1` in `~/.claude/settings.json` |
| **Keep "Use Option as Meta Key" off** | Reverts the macOS terminal setting Claude keeps enabling |

**Settings → Tools → Claude Session Manager → Launch Profile** (per project)

| Setting | Description |
|---------|-------------|
| **Extra launch arguments** | Inserted after the Claude command on every launch in this project, e.g. `--channels plugin:name@marketplace`. Letters, digits and `_ . : = / , @ -` only; session selection flags are rejected. Stored in `.idea/workspace.xml`. |
| **Tab profiles** | Tab name (exact), working directory (absolute or `~/...`), launch arguments (empty: the project default), keep open. A tab with that name runs `cd '<dir>' && claude <arguments> [--resume <id>]`. Open them with Tools > Open Profile Tabs, the terminal tab menu, or the terminal's new-tab dropdown; "keep open" tabs come back at project open. Renaming a profile tab makes it an ordinary tab. |

## How It Works

The plugin uses multiple detection methods:

1. **Hook events** (most reliable) - Claude's SessionStart hook notifies the plugin
2. **Terminal output monitoring** - Scans terminal output for session UUIDs
3. **File watching** - Monitors `sessions-index.json` for changes

Bindings are stored in `.idea/workspace.xml` (component `ClaudeSessionBindings`) within your project.

## Driving sessions from outside the IDE (Claude Code channels)

The launch profile, tab profiles and session registry exist so that a program outside the
IDE can hand work to Claude Code sessions running in IDE terminal tabs. The author uses them
as the IDE half of a local daemon that dispatches tasks to one Claude session per git
worktree through Claude Code channels. The daemon is not part of this repository and the
plugin knows nothing about it: everything goes through files and launch flags, so the same
pieces work for any orchestrator.

How the pieces fit:

1. **Launch** - a launch profile (or a tab profile, for a worktree outside the project) adds
   `--channels plugin:<name>@<marketplace>` to every launch, so each session loads a channel
   plugin: an MCP server that can push messages into the session.
2. **Restore** - after an IDE restart every bound tab resumes by itself, so the whole set of
   sessions comes back without a click.
3. **Discover and route** - the session registry says which sessions exist, where each runs
   (`cwd`), whether it can take work (`pid` alive and `state` is `idle`) and what to call it
   (`title`, else `tab`). Route by `cwd` and compare with a trailing `/`: `/work/app` is a
   string prefix of `/work/app-worktree1`.

Lessons from building it (Claude Code 2.1.278, macOS, September 2026):

- Activate the channel with `--channels` and an allowlist in managed settings
  (`/Library/Application Support/ClaudeCode/managed-settings.json`):
  `{"channelsEnabled": true, "allowedChannelPlugins": [{"marketplace": "...", "plugin": "..."}]}`.
  Both keys are required and only the object form is accepted. Without `channelsEnabled`
  the session still loads the plugin's MCP tools but drops channel events, with no error.
  `--dangerously-load-development-channels` shows a blocking dialog on every launch, which
  stops an unattended restore.
- A channel server runs once per session. If it listens on a fixed port, only the first
  session gets the port and the others lose their channel; have each instance connect out
  to the daemon instead (long-poll) and register its `cwd` and session id.
- A user-scope channel plugin loads in every Claude session on the machine, IDE or not.
  Make it do nothing unless its `cwd` is a directory the daemon manages.

## Troubleshooting

### "Not Installed (action required)"
Go to Settings and click "Install Hooks". The plugin needs hooks in Claude Code to detect sessions reliably.

### Sessions not detected
1. Verify hooks are installed (Settings shows "Installed")
2. Check that `~/.claude/hooks/session-notify.sh` exists and is executable
3. Restart the IDE after installing hooks

### Wrong tab gets bound
This can happen if you start Claude in one tab while another is focused. Use manual binding (right-click → Bind to Session) for precise control.

### Restored tabs launch only when clicked
Fixed in 0.7.0: the plugin shows each restored tab once so its shell starts and the queued resume runs. If a tab still does not launch, check `idea.log` for "did not start within".

## Development

```bash
# Build
./gradlew build

# Build plugin zip
./gradlew buildPlugin

# Run in sandbox IDE
./gradlew runIde

# Bump version
./scripts/bump-version.sh patch  # 0.2.0 → 0.2.1

# Release to GitHub
./scripts/release.sh

# Audit bindings against transcripts on disk
./scripts/audit-bindings.py ~/Projects/my-project
```

Gradle needs JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS). Design
notes, roadmap and plans live in `docs/`; start with `docs/prd.md`.

## License

Apache 2.0

## Author

Jan Dalen ([@JendaT](https://github.com/JendaT))
