# Claude Session Manager

A JetBrains IDE plugin that persists Claude Code sessions across terminal tabs.

Keep multiple Claude sessions organized - each terminal tab remembers its session. Sessions auto-resume when you restart the IDE.

## Features

- **Bind sessions to tabs** - Manually or automatically associate Claude sessions with terminal tabs
- **Auto-resume on IDE restart** - Bound sessions resume automatically when you reopen the project
- **Session history per tab** - Switch between previous sessions used in a tab
- **Detects session changes** - Automatically tracks `/clear`, `/compact`, `/resume` commands
- **Tab rename support** - Bindings follow when you rename terminal tabs
- **Per-project launch profile** - Extra Claude launch arguments applied to every launch in a project
- **Session registry** - One JSON file per live session (`idle` / `busy` / `waiting`) for external tools

## Requirements

- JetBrains IDE 2026.1 or later (WebStorm, IntelliJ IDEA, PyCharm, etc.) with the reworked terminal
- [Claude Code CLI](https://claude.ai/code) installed

## Installation

### From JetBrains Marketplace
*(Coming soon)*

### Manual Installation
1. Download the latest `.zip` from [Releases](https://github.com/jendalen/claude-session-manager/releases)
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
  "tab": "Local (2)"
}
```

- `state`: `idle` (Stop, SessionStart), `busy` (UserPromptSubmit, PreToolUse), `waiting`
  (a permission prompt is open; cleared by the next PreToolUse or Stop).
- `pid`: the Claude process; `kill -0 <pid>` tells a reader whether the entry is dead.
- `project` and `tab`: written by the plugin when it binds the session and on tab rename
  (`tab` is the persisted tab name, not Claude's live title).
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
| **Sync tab names** | Update tab name when Claude `/rename` is used |
| **Claude command** | CLI command (e.g., `claude` or a custom alias) |
| **Home directory** | Custom Claude home path (default: `~/.claude`) |
| **Protect bound tab names** | Bound tabs ignore Claude Code's terminal-title renaming |
| **Keep my custom tab names** | Sets `CLAUDE_CODE_DISABLE_TERMINAL_TITLE=1` in `~/.claude/settings.json` |
| **Keep "Use Option as Meta Key" off** | Reverts the macOS terminal setting Claude keeps enabling |

**Settings → Tools → Claude Session Manager → Launch Profile** (per project)

| Setting | Description |
|---------|-------------|
| **Extra launch arguments** | Inserted after the Claude command on every launch in this project, e.g. `--channels plugin:name@marketplace`. Letters, digits and `_ . : = / , @ -` only; session selection flags are rejected. Stored in `.idea/workspace.xml`. |

## How It Works

The plugin uses multiple detection methods:

1. **Hook events** (most reliable) - Claude's SessionStart hook notifies the plugin
2. **Terminal output monitoring** - Scans terminal output for session UUIDs
3. **File watching** - Monitors `sessions-index.json` for changes

Bindings are stored in `.idea/workspace.xml` (component `ClaudeSessionBindings`) within your project.

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

Jan Dalen ([@jendalen](https://github.com/jendalen))
