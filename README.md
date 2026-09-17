# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

**Keep coding with your agents, even when you step away from your computer.**

AgentLink brings your remote coding-agent conversations to Android. Start a task,
follow its progress, respond to approval requests, and pick up an existing session
from your phone. Your projects and agent processes stay on your computer.

**[Download the Android APK](https://github.com/gongpx20069/android-agent-link/releases)**

## What you can do

- **Chat across computers and projects.** Choose a machine, workspace, and agent for each conversation.
- **Follow work as it happens.** Read streaming replies, tool activity, and execution results.
- **Respond from your phone.** Approve or deny requests that the agent sends for your decision.
- **Pick up where you left off.** Resume available agent sessions and view recent history.
- **Browse long conversations without loading everything.** Older saved messages and long
  replies/tool outputs are paged; history is retained, not silently deleted.

AgentLink is a remote control, not an on-phone agent runtime. Your computer must
stay awake, online, and running the bridge while you use it.

## What you need

| On your phone | On your computer |
| --- | --- |
| Android 8.0 or later and the AgentLink APK | Python 3.11 or later, Git, and an installed, signed-in coding-agent CLI |
| Internet access | Internet access and the AgentLink bridge running |

**Microsoft Dev Tunnels is the recommended connection method and the default.**
It provides an authenticated relay, so you do not need to open inbound firewall
ports or install a separate VPN/networking app on Android. Keep tunnel access
private; never enable anonymous access.

## Get started

The commands below are for Windows PowerShell.

### 1. Install AgentLink on your phone

Open [Releases](https://github.com/gongpx20069/android-agent-link/releases) and
download `agentlink-0.0.x.apk` from the newest published version. Builds are
currently marked **Pre-release**. Open the APK on Android and allow installation
from that source if prompted.

Signed release APKs can update previous signed releases in place. A debug build
may require uninstalling before switching to a release build; uninstalling removes
its local app data.

Upgrades migrate the older chat store automatically. Keep the app open during its
first load; do not uninstall or clear data to address lag. The new encrypted chat
database is not readable by older APKs, so downgrading is not a supported rollback.

### 2. Set up your computer once

Install and sign in to your coding-agent CLI first. Then download the bridge:

```powershell
git clone https://github.com/gongpx20069/android-agent-link.git
cd android-agent-link
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .\bridge
```

Already have a checkout? Use that folder instead of cloning again.
For Conda, uv, and additional installation options, see the
[bridge guide](bridge/README.md).

### 3. Start the recommended Dev Tunnel connection

Run from the repository folder:

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start
```

Follow the Dev Tunnels sign-in instructions if prompted. The bridge prepares a
private tunnel for this computer and prints a pairing QR code and link. Keep
this terminal open.

On Windows, the bridge can download the Dev Tunnels CLI if it is missing.
You do not need to register an application or enter a client ID to use the
published AgentLink app.

### 4. Pair your phone

**Scan a QR code**

In AgentLink, open **Machines → Scan QR**, scan the code in the terminal, and
confirm the pairing on your computer. You can also paste the printed pairing
link. Use **Test Connection** on the saved machine to check connectivity.

**Or try account discovery without scanning**

Account discovery is new in `0.0.25`. Use the same provider and account on your
computer and phone. For example, start the bridge with GitHub:

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start --devtunnel-login github
```

In **Machines**, sign in with GitHub, complete the browser/device-code flow, and
find your computers. Select one, compare the six-digit code shown on your phone
and computer, then enter `y` in the computer terminal to approve first-time access.
Press Enter or enter `n` to decline; you do not need to type the numeric code.
GitHub consent names Microsoft's **Visual Studio Tunnel Service**.

On subsequent starts, omit the login flag to reuse the computer's existing CLI
account.

For Microsoft, use `--devtunnel-login microsoft` on the computer and Microsoft
sign-in on the phone. AgentLink requests delegated Dev Tunnels access, not just
basic sign-in; review the browser's permission prompt before approving.
The explicit permission request fixes the "code expired" failure reproduced with
the earlier request. A real personal-account login, refresh-token issuance, and
tunnel-list API access have now succeeded using AgentLink's own application ID.
No changes to your app registration are required to use the published app.

**Preview limitation:** full physical-phone discovery, pairing, and connection
remain unverified for both providers. Microsoft token refresh and connect-token
issuance still need live verification; receiving a refresh token alone does not
verify renewal. If account discovery does not work, use QR pairing.

### 5. Start your first conversation

Open **Chats → New Chat**, choose your machine and agent, and enter your project's
absolute path **on the computer**, such as `C:\Repos\my-project`. Create the chat
and send a task.

To continue previous work, choose **Existing session** in New Chat and load a
session offered by that agent. When an agent requests approval, respond from
**Approvals**. Available sessions, models, and permission behavior depend on the
installed agent CLI.

## Chat from your computer too

AgentLink has an optional terminal chat mode of its own, separate from the agent
CLI's native interface. It runs alongside Android through the same bridge.

From the repository folder, install the extra once and start:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".\bridge[interactive]"
.\.venv\Scripts\python.exe .\bridge\run.py start --interactive
```

Open a chat on Android. When the bridge sees just one chat and your terminal
input is empty, it selects that chat automatically: **just type to continue**.
No Chat ID to find, copy, or share.

For multiple chats, enter `/chats`, then choose a number from the list:

```text
* 1. my-project | copilot-cli | Fix login
  2. my-project | copilot-cli | Update README
Choice > 2
```

The list shows project, agent, phone chat title, status, and full workspace path.
Older phones without title support use an observed message excerpt instead.
Numbers stay stable until the bridge restarts. New phone connections and
background reconnects never switch an already selected terminal chat.

Messages use the same agent session and queue as your phone; replies stream
without interrupting your draft. Other chats show task/approval notices, not
their replies. Selection shows new events only; use Android for earlier history.

The input stays short (`You >`); a two-line bottom bar keeps the current chat,
Ready/Working/Approval status, elapsed busy time and queue size visible. It also
shows the running tool or useful commands. Long labels fit the terminal width.
Tool progress updates stay in that bar; only completion/failure summaries enter
the conversation. Submitted input is not echoed a second time.

Agent replies render **Markdown**: headings, bold/italic text, lists, quotes,
inline code, syntax-highlighted code blocks and tables. Completed blocks appear
once as the reply arrives; an unfinished paragraph/list/code block waits for its
boundary or reply completion, with a receiving indicator in the bar. Table cells
wrap rather than silently losing text. Links are shown as text and images as
placeholders, without opening or downloading them. Approval details stay literal.
Set `NO_COLOR=1` before starting for monochrome rendering.

If upgrading an existing terminal installation, rerun the interactive-extra
install command above to add the Markdown dependencies. Extremely narrow/deep
layouts explicitly fall back to original text; blocks exceeding 64 Ki characters
switch the rest of that reply to plain text with a notice.

| Terminal command | Action |
| --- | --- |
| `/chats` | Show recognizable chats, then enter a number to choose. Enter cancels. |
| `/use 2` | Switch directly to chat number 2. |
| `/new copilot-cli C:\Repos\my-project` | Create a terminal-local chat for an installed agent. |
| `/approvals` | Review pending approval details. |
| `/approve <approval-id>`, `/deny <approval-id>` | Decide a request; approval requires reviewing it first. |
| `/pair y`, `/pair n` | Confirm or deny phone pairing after checking the displayed code. |
| `/send <text>` | Send text that starts with `/`, rather than treating it as a terminal command. |
| `/help`, `/quit` | Show help, or stop the bridge and disconnect Android. |

For a shared conversation, **create/open it on Android first**. A terminal-local
`/new` chat is not automatically added to the phone's Chats list. Chat selection
and terminal history are not persisted across bridge restarts.

This mode displays conversation content rather than bridge event logs, even if
`--log-level debug` is supplied. Runtime errors remain visible. Pairing uses
`/pair y|n` so it does not compete with the chat input reader; ordinary server
mode still uses `[y/N]`. `Ctrl+C` cancels the current input, not the agent task.
`/quit` refuses while tasks are busy; `/quit!` explicitly stops the bridge anyway.
Closing the terminal/EOF also stops the bridge, not a guaranteed agent cancellation.
Use a real terminal, not a redirected pipe; the standard-library server is required.

## Coding agents

| Agent | AgentLink integration |
| --- | --- |
| GitHub Copilot CLI | Primary tested integration; requires a working `copilot --acp` command on the computer. |
| Claude Code | Available when `claude` is installed; requires a version or setup that actually supports `claude --acp`. |

Other agents are not currently integrated. An installed CLI appearing in the
machine's agent list does not by itself prove ACP compatibility.

## Staying connected

Account-paired connections obtain fresh tunnel connect credentials when needed,
provided the account remains authorized. Expired or revoked GitHub credentials
require signing in again.
Microsoft refresh is implemented but has not yet been live-verified.
This is not a promise of a permanent connection or unattended computer login.

- Keep the computer awake and leave the bridge running.
- With QR pairing, expired tunnel credentials may require restarting the bridge and scanning a fresh code.
- If the computer reports an expired Dev Tunnels login, follow the login command in its error message and restart the bridge.
- Android background monitoring is currently limited to one hour; it is not an always-on service.

## Other connection options

Dev Tunnels is the first choice. If you already use Tailscale, you can instead run:

```powershell
.\.venv\Scripts\python.exe .\bridge\run.py start --transport tailscale
```

Both phone and computer need Tailscale installed, connected, and signed in to the
same tailnet. Pair through the bridge's QR code. ZeroTier setup is not automated.
Local transport is for testing and does not make the computer remotely reachable.

## Need help?

The computer terminal now shows task and tool summaries instead of streamed
message fragments. For more diagnostic metadata, add `--log-level debug` to the
bridge start command. This does not change streaming replies on your phone.

| Problem | What to try |
| --- | --- |
| `android-acp-bridge` is not recognized | Use the explicit `.\.venv\Scripts\python.exe .\bridge\run.py start` command above, from the repository folder. |
| Phone cannot connect | Check that the computer is awake and the bridge is running. For QR connections, refresh an expired QR/tunnel credential. |
| Account discovery shows no computers | Update and restart the bridge. Check that both devices use the same provider and account; GitHub and Microsoft are separate identities. |
| Dev Tunnels reports `Login token expired` | Use the exact CLI login command printed by the bridge, then restart it. Phone sign-in does not renew the computer's login. |
| Pairing was cancelled but the computer still waits | Press Enter to dismiss its outstanding confirmation prompt before trying again. |
| Agent is missing or fails to start | Install and sign in to the agent CLI on the computer, and check that its ACP command works there. |

To update the bridge, stop it, run `git pull --ff-only` in your checkout, repeat the
pip install command above, and start it again. Use matching app and bridge versions
for new features. Older installations can retain their fixed tunnel address with
`--devtunnel-id agentlink`; otherwise rediscover or re-pair the new computer tunnel.

For more setup and troubleshooting, see the [bridge guide](bridge/README.md).
For bugs, include the app version and the error message in a
[GitHub issue](https://github.com/gongpx20069/android-agent-link/issues), but never
include pairing links, login codes, or tokens.

## Contributing

Looking to build or extend AgentLink? Start with the
[technical documentation](docs/README.md) and [contributor instructions](CLAUDE.md).
