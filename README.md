<p align="center">
  <img src="docs/assets/agentlink-logo.svg" alt="AgentLink — your agents, within reach" width="640">
</p>

<p align="center">
  <strong>Your coding agents. Your computer. Now on your phone.</strong><br>
  Start a task on Android, follow its progress, and continue the same chat in your terminal.
</p>

<p align="center">
  <a href="https://github.com/gongpx20069/android-agent-link/releases"><strong>Download Android APK</strong></a>
  &nbsp; · &nbsp; <a href="README.zh-CN.md">中文</a>
  &nbsp; · &nbsp; <a href="docs/user-guide.md">User guide</a>
</p>

## Quick start

### 1. Download the app

Install `agentlink-0.0.x.apk` from the newest [Release](https://github.com/gongpx20069/android-agent-link/releases)
on your **Android 8.0+** phone. Releases are currently marked **Pre-release**.

### 2. Start the server on your computer

You need **Python 3.11+, Git, and an installed, signed-in coding agent**:
GitHub Copilot CLI, or Claude Code with a setup that supports `claude --acp`.
Your code and agent processes stay on this computer.

Run once in **Windows PowerShell**:

```powershell
git clone https://github.com/gongpx20069/android-agent-link.git
cd android-agent-link
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".\bridge[interactive]"
.\.venv\Scripts\python.exe .\bridge\run.py start --interactive
```

Already installed? Only the last command is needed on subsequent starts.
Follow the Dev Tunnels sign-in prompt if shown. The default connection is private
and authenticated: **no inbound firewall ports or extra VPN app on your phone**.
Keep the computer awake and this terminal running.

### 3. Chat from your phone and terminal

- In the server terminal, enter **`/qrcode`**. On Android, open **Machines → Scan QR**.
  Check the matching confirmation code, then enter **`/pair y`** in the terminal
  after leaving the QR view with Esc.
- On Android, open **Chats → New Chat**. Choose the computer, agent, and a project
  path **on the computer**, such as `C:\Repos\my-project`, then send a message.
- **Continue the same conversation in the terminal.** With one chat and an empty
  terminal draft, it is selected automatically. For multiple chats, use **`/chats`**.

Phone and terminal share the same agent session, messages, task queue and approvals.
This is AgentLink's terminal UI, not a second independent agent-CLI conversation.

## Built for moving between screens

| Stay in control | Pick up where you left off |
| --- | --- |
| Stream replies and inspect tool activity | Switch between computers, projects and chats |
| Approve agent requests from your phone | Resume sessions offered by your agent |
| Long-press on Android to copy selected text | Drag-select and scroll in the terminal |
| Keep queued messages compact; tap to expand | Read older history without loading it all at once |

## More when you need it

| Looking for | Read |
| --- | --- |
| Terminal shortcuts, copying, models and permissions | [Terminal guide](docs/user-guide.md#chat-from-your-computer-too) |
| Sign in to find computers instead of scanning | [Account discovery and pairing](docs/user-guide.md#4-pair-your-phone) |
| Conda, uv, server-only mode or transport options | [Bridge setup](bridge/README.md#install) |
| Supported agent versions and compatibility | [Coding agents](docs/user-guide.md#coding-agents) |
| Updating, connection issues and troubleshooting | [User guide](docs/user-guide.md#need-help) |
| Optional Mochi control of shared chats | [Mochi integration](docs/user-guide.md#optional-mochi-integration) |
| Architecture, security and contributing | [Technical docs](docs/README.md) · [Contributor guide](CLAUDE.md) |

<details>
<summary>Before upgrading / connection limits</summary>

Signed releases can update previous signed releases in place. Do not uninstall
or clear app data to fix lag: that removes local history. Keep the app open during
the first load after upgrading; older APKs cannot read the migrated chat database.
Switching from a debug APK may require uninstalling it first.

The computer must stay online with the bridge running. QR tunnel credentials
can expire and require a fresh pairing code. Android background monitoring is
limited to one hour, not an always-on service. Account discovery remains a preview;
if it is unavailable, use QR pairing. Never enable anonymous tunnel access.

</details>

Found an issue? [Report it here](https://github.com/gongpx20069/android-agent-link/issues)
with the app version and error message — never include pairing links or tokens.
