# AgentLink Bridge

Connect your computer's coding agents to the AgentLink Android app.
For the shortest setup path, start with the [AgentLink quick start](../README.md).
This guide covers additional installation and connection options.

## Claude Code

Install the validated ACP adapter explicitly (Node.js 22 or newer):

```powershell
npm install -g @agentclientprotocol/claude-agent-acp@0.81.2
claude-agent-acp --version
claude-agent-acp --cli auth login
```

The adapter includes the Claude Agent SDK runtime. Existing compatible local
Claude authentication can be reused; credentials never move to Android. Restart
the bridge deliberately after existing tasks finish, then choose Claude Code.
The bridge never installs the adapter, logs in, migrates sessions, or enables
permission bypass automatically. It rejects other adapter versions pending
compatibility validation; do not install an unpinned `latest`/preview build.
Windows npm shims are resolved to Node + the package entry point without passing
workspace paths through a command shell.

Saved sessions are paginated; history replay and context-only resume are chosen
from negotiated capabilities. A live session is not replaced just to read history.
Messages and interactions keep flowing after a prompt RPC returns. Announced
background shell/workflow tasks keep the operation active until they finish;
cancellation also requests their termination. Long-running servers/monitors can
therefore keep a prompt busy until cancelled. This is not a guarantee about
unreported external processes, nor an attachment to an independently running
Claude terminal. Native subagent-session UI and URL/OAuth forms are not negotiated.

Android displays exact permission options and separate question forms. In the
terminal, first use `/approvals`, then `/choose <approval-id> <option-id>` or
`/answer <approval-id> <JSON object>`. `/approve` chooses only an unambiguous
allow-once option; it cannot answer questions or silently grant persistent access.
Unsupported forms fail explicitly; use local login for authentication.

The pinned adapter handshake and non-model session listing were checked on Windows.
Automated tests use deterministic events for background work and approvals.
Authenticated model turns and physical-phone acceptance remain to be exercised
with a signed-in Claude account.

## Kimi Code and Qwen Code

Both use native stdio ACP, with no additional adapter. Install the CLI you want
on the computer, authenticate locally, then restart the bridge after active work
finishes. The phone's agent selector and terminal `/new` use the same catalog.

| Agent / bridge ID | Published baseline | Runtime | Bridge launch |
| --- | --- | --- | --- |
| Kimi Code / `kimi-cli` | `@moonshot-ai/kimi-code@2.1.1` | Node.js 22.19+ | `kimi acp` |
| Qwen Code / `qwen-code` | `@qwen-code/qwen-code@0.24.6` | Node.js 22+ | `qwen --acp` |

```powershell
# Install either or both explicitly.
npm install -g @moonshot-ai/kimi-code@2.1.1
kimi login
npm install -g @qwen-code/qwen-code@0.24.6
qwen
```

In Qwen's local CLI, complete its authentication setup before using AgentLink.
Kimi means the current JavaScript Kimi Code distribution, not the archived
Python `kimi-cli`. Discovery only confirms an executable is on PATH; it does not
validate login. ACP1 capabilities are negotiated at connection time. These
baselines are recommended, not an exact-version rejection rule as with Claude.
Windows npm shims resolve the installed package's declared JavaScript entry point
without invoking a shell. Missing/broken installations and authentication errors
are explicit; the bridge never installs packages or grants automatic approval.

Both support paginated session listing, history replay via `session/load`, and
context-only recovery via `session/resume`. Existing local history is not sent as
new prompts. Model, mode and thinking/reasoning options appear only when advertised.
Android's `/config` opens all supported settings; the terminal `/config` lists
their IDs and `/config <id>` opens a picker (choose, then `y` to apply).
`/model` and `/allow-all` retain their existing behavior. Grouped model values
retain their exact provider IDs. A mode can enable automatic execution; review
the advertised name/description before selecting it.

Kimi uses native form elicitation for questions, including multiple questions and
multi-select fields. Qwen's `qwenInteractionKind: user_question` extension is
translated into a separate form, never a generic approval. Each Qwen question has
a required text answer; its suggested choices/descriptions are displayed. Enter
an option label, a custom answer, or comma-separated labels for multiple choices.
Answers return using Qwen's numeric question keys and `answers` response field.
Cancelling/denying does not submit an answer. Other Qwen permissions preserve
their exact allow-once / project / user option IDs.

These integrations keep receiving events between RPCs, but do not negotiate
Qwen's private daemon/liveness protocol or promise whole-session background-job
completion beyond standard ACP prompt settlement. `qwen serve`, attaching to an
independently running vendor terminal, and session migration are not included.

The published executables were exercised on Windows in isolated, unsigned-in
homes: initialize, empty session listing, explicit authentication-required
responses, and clean EOF shutdown. Model turns and phone acceptance with real
accounts still need to be exercised. The regression suite uses subprocess peers
for prompt streaming, questions/permissions, configuration, cancellation and
session recovery; it does not contact a model.

## OpenCode

Bridge ID `opencode` uses native ACP with **1.18.35** as the compatibility
baseline (not an exact-version startup restriction). Install explicitly on the
computer, using the official native executable or npm:

```powershell
npm install -g opencode-ai@1.18.35
opencode auth login
```

Choose/configure a model and provider locally before creating an OpenCode Chat.
Discovery checks PATH, not authentication. Windows npm installs require Node.js;
the bridge resolves the package's extensionless `bin/opencode` Node launcher
without executing a command shell. Direct native executables are also supported.
Update/restart the Bridge after active work finishes; the existing Android agent
picker and terminal `/new` discover OpenCode dynamically.

The launch command is
`opencode acp --hostname 127.0.0.1 --port 0 --mdns=false`.
These explicit flags keep OpenCode's internal HTTP server on loopback with a
random port and disable mDNS even when local server configuration says otherwise.

**Approvals depend on OpenCode's policy.** Most operations default to `allow`.
To review operations on the phone/AgentLink terminal, merge deliberate `ask`
rules into your local `opencode.json`, for example:

```json
{
  "permission": {
    "bash": "ask",
    "edit": "ask"
  }
}
```

Use `"permission": "ask"` instead to request approval for all permissions.
AgentLink never changes this file or adds a permission-bypass flag.
Offered `once`, `always`, and `reject` options retain their exact IDs; `always`
allows matching patterns for the current OpenCode session, not just one tool
call. Only actual permission requests produce approval cards/notifications.

Supported ACP surfaces include streaming, tool updates, cancellation, paginated
session listing, history replay via `session/load`, and context-only
`session/resume`. New sessions are resumable before the first model turn.
Advertised model, mode and effort settings use the existing `/config` UI.
Image input requires both the negotiated image capability and a vision-capable
model. Phone and AgentLink terminal share the same managed Chat; this does not
attach to a separately running native OpenCode TUI.

No client filesystem/terminal delegation, structured OpenCode question-tool
integration, or extended background-task completion protocol is negotiated.
Do not force-enable `OPENCODE_ENABLE_QUESTION_TOOL` for this integration.

The official Windows 1.18.35 executable was exercised in an isolated home with
no real credentials or model prompts: initialize/new, session listing, history
loading, process restart/resume, and clean EOF shutdown. Deterministic subprocess
tests cover permissions, images, configuration, cancellation and error propagation.
Authenticated model turns and physical-phone acceptance remain unverified.

## DeepSeek Harness

`deepseek-harness` launches native `dsh --profile acp`. The developer-preview
baseline is **`@deepseek-ai/dsh@0.1.7-rc.2`**, exercised with Node.js 24.
Install explicitly on the computer and configure the provider there:

```powershell
npm install -g @deepseek-ai/dsh@0.1.7-rc.2
dsh
```

The bridge checks `dsh --version`; ACP's `agentInfo.version` is not the package
version. Other preview releases require compatibility validation. Discovery
only checks installation, not provider credentials. Credentials/configuration
stay on the computer; ACP `authenticate` success does not verify model access.

Streaming, tool updates, one-shot permissions, cancellation and advertised
model/reasoning settings use the common ACP transport and `/config` UI.
Persisted sessions support listing and context resume, **not transcript replay**.
Android labels these sessions, retains its saved messages, and marks a boundary
when switching contexts. External sessions' old messages cannot be imported.
After a journal gap, the app retains saved messages and shows an explicit
missing-history warning after an authoritative idle snapshot; it never fabricates
an empty successful history or reads DSH's private database.

DSH does not expose ACP question forms, modes, commands or client filesystem/
terminal capabilities in this baseline. AgentLink does not add these surfaces or
attach to an independently running DSH terminal. Prompt settlement follows DSH's
ACP response, not an inferred guarantee about unreported external processes.

The actual Windows package completed isolated initialize/list/new-session,
EOF shutdown and restart/resume without a model request. Authenticated model
turns and phone acceptance remain separate from those protocol checks.

## Copilot background-task lifecycle

Copilot uses the native SDK transport by default. Upgrade the bridge dependencies
with its normal `requirements.txt` / interactive installation command before
starting the updated bridge. The adapter uses the installed `copilot` executable
and preserves existing session IDs/model selection. Native protocol compatibility
was verified with Copilot CLI 1.0.86 and Python SDK 1.0.13.

An assistant reply such as "waiting for background agents" is not completion.
The bridge keeps streaming updates/approvals and holds queued prompts until the
root session reports idle, including background agents and attached shells.
Errors/disconnection are reported as failures rather than successful idle.
New native sessions do not receive an implicit `--allow-all`; use the existing
confirmed permission UI if you want that mode.

For older CLI compatibility, explicitly use `--copilot-transport acp`. That mode
retains ACP's limitations for post-prompt background work; there is no automatic
fallback. Claude Code, Kimi Code, Qwen Code, DeepSeek Harness and OpenCode continue using ACP.

Do not restart an active bridge to apply this change: wait for work to finish or
explicitly cancel it first. Updating files does not hot-patch an existing process
or revive previously cancelled background agents. See the
[design and regression requirements](../docs/session-lifecycle.md).

## Shared Mochi / Android / terminal control

The default stdlib WebSocket runtime owns a durable shared workspace/chat catalog,
task identities and a sequenced journal. Existing Android attach/prompt requests
register their original IDs; terminal `/new` also registers with this catalog.
`/chats` includes persisted and externally created shared chats. Multiple Android
connections can subscribe without stealing one another's streams.

Mochi connects through the explicitly authorized AgentLink app on the same phone,
not directly to machine credentials. The three tool groups are
`agentlink_workspace`, `agentlink_chat`, and `agentlink_control`. Approval and
privileged configuration are trusted UI operations, not model-supplied consent.
See [the control contract](../docs/acp-bridge-contract.md).

Use repeatable `--workspace-root C:\Repos` to permit directory creation,
registration, HTTPS clone and worktree creation beneath existing approved roots.
Without this flag those workspace mutations are denied. Paths are canonicalized;
existing destinations are never overwritten. Git uses argument arrays, disables
interactive credential prompts and has a 20-second limit. A failed/timed-out Git
operation can leave a partial directory: inspect it; AgentLink does not delete it
or blindly retry. Clone URLs cannot contain embedded credentials, query or fragment.

Production stores `shared-state.sqlite3` beside the device-token hash store,
protected with the same private directory/file permissions. **The journal is not
encrypted at rest** and contains conversation content; protect disk/backups.
SQLite's WAL sidecars belong to the same private directory. Embedded/test runtimes
remain memory-only unless `shared_state_store` is explicitly configured.

The journal keeps up to 10,000 events per chat; individual records above 128 KiB
are replaced by explicit truncation notices. Reads have bounded pages and byte
budgets, plus generation/cursor/gap metadata. Task identity records are retained
separately so journal rotation does not permit duplicate execution. Android's
encrypted local history and the terminal's bounded live display are projections,
not unlimited replicas of the shared journal.

After restart, accepted unfinished tasks become `interrupted`: an external command
may have already run, so inspect before issuing a **new** task ID. Reusing a task
ID never reruns it; changing its content conflicts. Human input invalidates stale
Mochi continuations and removes queued Mochi follow-ups. Running ACP cancellation
returns `cancellation_requested`, not a guarantee that past side effects were undone.
Cross-chat Mochi writes to an already busy workspace are rejected; use a worktree.
The optional FastAPI backend remains a discovery/echo scaffold, not this runtime.

## Install

The bridge requires Python 3.11 or newer. It never creates a Python environment or installs packages during startup. Run one of these explicit installation flows from the `bridge` directory.

### Conda

```powershell
conda env create -f environment.yml
conda activate android-acp-bridge
```

If the environment already exists:

```powershell
conda env update -n android-acp-bridge -f environment.yml --prune
conda activate android-acp-bridge
```

### uv

```powershell
uv venv --python 3.12
.\.venv\Scripts\Activate.ps1
uv pip install -r requirements.txt
```

### Python venv and pip

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Use `requirements-all.txt` instead of `requirements.txt` to install every optional backend. After installation, use `android-acp-bridge ...` directly. The source helper `python .\run.py ...` uses the current Python environment and forwards arguments to the same CLI; it never creates an environment or installs packages.

## Starting the bridge

### Microsoft Dev Tunnels (recommended and default)

The updated Android app can discover the bridge without a QR code when both use
the same account. For GitHub login on both sides:

```powershell
python .\run.py start --devtunnel-login github
```

On the phone, sign in with GitHub from Machines, choose the computer, and compare
the six-digit confirmation code. Enter `y` or `yes` at the `[y/N]` prompt to
approve; Enter, `n`, or any other input declines. Do not type the numeric code.
Keep the console available for first pairing. QR scanning remains available.
For Microsoft, use `--devtunnel-login microsoft` on the computer and Microsoft
sign-in on the phone. The corrected explicit delegated scope has passed real
personal-account authorization and tunnel-list API access. Users do not need to
register an application. Live token refresh, connect-token issuance and complete
phone pairing/connection still need verification; GitHub also remains unverified
end to end. Use QR pairing if account discovery is unavailable.
Omit the flag to reuse the CLI's current account on later starts.

Default tunnel IDs now include a hostname-derived suffix. Use `--devtunnel-id
agentlink` to retain an older installation's fixed tunnel ID if needed. New
discovery metadata labels the tunnel and port; it never enables anonymous access.

```powershell
android-acp-bridge start
```

The recommended transport creates a private authenticated Microsoft Dev Tunnel.
Android does not need a Tailscale or ZeroTier app. Account pairing resolves fresh
connect credentials while authorized; QR pairing stores the short-lived relay
authorization header supplied by the bridge. See **Microsoft Dev Tunnels private
relay details** below for login behavior and options.

Device authentication survives restarts. Production stores only device-token hashes
in `%LOCALAPPDATA%\AgentLink\device-tokens.json` on Windows, or
`$XDG_STATE_HOME/AgentLink/device-tokens.json` (default `~/.local/state`) elsewhere.
Use `--device-token-store <path>` to choose a file in a dedicated private directory.
Storage/permission failures are explicit; do not delete this state unless you intend
to invalidate paired devices on the next restart. This device state does not renew
relay credentials: QR-paired connections may need a fresh QR when their relay
token expires, while account-paired connections obtain new connect tokens when
the account remains authorized. Neither path renews the computer's CLI login or
enables anonymous access.

### Tailscale mode (optional)

```powershell
android-acp-bridge start --transport tailscale
```

Tailscale mode starts the local HTTP/WebSocket server on the machine's Tailscale IP, creates a short-lived pairing token, and prints both an AgentLink pairing link and a compact CLI QR code.

Tailscale setup flow:

1. Check whether the `tailscale` CLI is installed.
2. If missing, try to install it automatically with `winget` on Windows, Homebrew on macOS, or the official Tailscale install script on Linux.
3. If installed but logged out or stopped, run `tailscale up --qr` so you can complete Tailscale login.
4. Re-check status and only generate the Android pairing QR after a Tailscale IP is available.

The Android device must also have Tailscale installed, be signed in to the same tailnet, and remain connected. ZeroTier similarly requires its client on both devices and is not currently automated by AgentLink.

If Windows reports `组织策略正在阻止安装` / installer exit code `1625`, your organization blocks `winget` installs. The bridge will not bypass that policy; install Tailscale from your company software portal, ask an administrator to approve `Tailscale.Tailscale`, or use the official installer from <https://tailscale.com/download/windows>, then re-run `android-acp-bridge start --transport tailscale`.

### Choosing workspaces

The bridge does not bind a workspace at startup. A workspace is chosen when creating a new chat in Android. Enter the remote absolute project path in the New Chat form; that value becomes the chat workspace and will map to ACP `cwd` when agent-session execution is connected.

### Microsoft Dev Tunnels private relay details

This is the recommended connection method, not just a fallback for blocked VPN
tools. It avoids a companion networking app on Android. Do not enable anonymous
Dev Tunnel access.

With conda:

```powershell
conda activate android-acp-bridge
devtunnel user login -d
android-acp-bridge start --transport devtunnel
```

What it does:

1. Finds `devtunnel` on `PATH`, or downloads `bridge\.tools\devtunnel.exe` on Windows.
2. Starts `devtunnel user login -d` if login is required.
3. Creates or reuses a machine-specific `agentlink-<hostname>-<hash>` tunnel.
4. Adds the bridge port with HTTP forwarding if needed.
5. Issues a short-lived `connect` token.
6. Starts `devtunnel host <tunnel-id>` as a child process.
7. Starts the local bridge listener.
8. Prints an AgentLink QR/link containing the Dev Tunnel `wss://` endpoint and `X-Tunnel-Authorization` header.

If tunnel creation fails with `Unauthorized tunnel creation access: Anonymous does not have 'create' access scope`, the Dev Tunnel CLI is still anonymous or lacks access. Run:

```powershell
devtunnel user login -d
```

If `devtunnel` is not on PATH and the bridge downloaded its private copy, run:

```powershell
.\bridge\.tools\devtunnel.exe user login -d
```

Then retry the bridge command. The bridge reports this as a setup error instead of printing a Python traceback.

`Login token expired.` means the Dev Tunnel CLI login must be renewed, not the
GitHub release login. Run `user login -d` with the same CLI executable used by
the bridge, then start the bridge again. A repository-root `devtunnel.exe` takes
precedence over PATH and `bridge\.tools\devtunnel.exe`; authentication errors print
the resolved executable's login command. Failures during show/list/create, port
setup, or connect-token issuance stop setup without falling back to anonymous
access or treating an authentication failure as a missing tunnel.

Optional overrides:

```powershell
android-acp-bridge start --transport devtunnel --devtunnel-id my-agentlink
android-acp-bridge start --transport devtunnel --devtunnel-cli C:\tools\devtunnel.exe
```

If tunnel creation fails with `Conflict with existing entity`, the tunnel ID is already taken but not visible to your account. Retry with a unique ID:

```powershell
android-acp-bridge start --transport devtunnel --devtunnel-id agentlink-<yourname>-<devbox>
```

For QR pairing, Android stores the relay header per machine and sends it on
pairing, HTTP, and WebSocket requests to that machine. Re-run the bridge and
re-scan when that token expires. Account-paired connections instead request fresh
connect tokens through the signed-in account; identity credentials are never
sent to the bridge. Revoked or expired login may still require interactive
sign-in, and phone login does not keep the computer's tunnel host alive.

Manual debugging flow:

If you need to run `devtunnel host` yourself, start the AgentLink bridge separately with the relay endpoint and connect token:

```powershell
android-acp-bridge start `
  --allow-non-tailscale `
  --host 127.0.0.1 `
  --port 4317 `
  --pairing-endpoint wss://<copied-devtunnel-host> `
  --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

Why `--pairing-endpoint` matters: the pairing token is created by the running bridge process. Do not use the standalone `pairing` command for the active Dev Tunnel server, because it creates a separate one-off token that the running server will not recognize.

### Localhost/manual testing

```powershell
android-acp-bridge start --transport local
```

This prints a QR/link for `ws://127.0.0.1:4317`. It is useful for local testing but will not make a developer machine reachable from Android unless another transport forwards the port.

## Interactive terminal chat

Run from `bridge`:

```powershell
python -m pip install -r requirements-interactive.txt
python .\run.py start --interactive
```

This is AgentLink's own terminal client, not the native Copilot UI. It leaves the
authenticated HTTP/WebSocket bridge available to Android. A full-screen
prompt-toolkit Application separates your draft from the scrollable conversation.
Prompt history is disabled; nothing is written to a terminal history file.

Open a Chat on Android first. When only one chat is known and the terminal input
is empty, it is selected automatically; type normally to continue without a Chat ID.
With multiple chats, `/chats` opens a numbered picker: enter a displayed number
or press Enter to cancel. `/use 2` is a direct shortcut. Numbers in ordinary
chat input remain messages, not commands. Exact IDs remain accepted by `/use`
for compatibility, but are not needed in the normal workflow.

The picker and bottom status bar show project/agent and the phone chat title, falling
back to a first observed prompt excerpt on older phones. The list also shows
status and the full workspace path. Numbers remain stable for this bridge run.
Discovery/reconnect never replaces an existing selection. Because Android also
attaches background chats, attachment order is not treated as foreground focus.
Automatic initial selection waits while a draft is present; it never retargets
text already being typed. A picker accepts only numbers from its displayed list.

Both surfaces submit to the same existing queue, and terminal observation does
not replace Android's subscription. Concurrent prompts queue behind active work.
The terminal retains bounded live events, including other chats for later switching;
it does not fetch earlier agent-session history.

Tools are grouped per task and collapsed by default. `/tools` focuses the latest
group; Tab switches input/conversation focus, Up/Down selects a line, and Enter
expands/collapses a group or tool. Clicking its header also works. Tool updates preserve
expansion, title and previous fields; supplied output arrays replace older arrays.
Failed titles are visible even in collapsed groups. Details show literal input,
output, diff and location data. PgUp/PgDn scroll, Left/Right page long content,
End resumes following new events, and Esc returns to input. Browsing does not
automatically jump to incoming replies. Input is echoed once, on acceptance.
Clicking input focuses the draft and resumes following, including after scrolling
with the mouse while input stayed focused. Printable typing or bracketed paste
from conversation focus moves to the draft without sending it. Esc/Tab back to
input and submitting a message resume the latest view. Merely tabbing into the
conversation does not pause following; navigation and tool inspection do.

**Select and copy part of the conversation:** hold the left mouse button and drag
over text (no Shift), then release and press **Ctrl+Y**. The highlighted characters,
not the entire message, are copied. Reverse and multi-line selections work, including
expanded tool details. A drag never expands/collapses a tool, even if you drag back
to the starting point. This is application selection, not the terminal's native
selection or a timed long-press gesture; it requires mouse-drag reporting from the
terminal. Merely holding the button without moving does not select a word.

The **right-side vertical scrollbar** shows the current conversation viewport.
Click its track to jump, or hold and drag its thumb up/down; the mouse wheel and
keyboard scrolling keep it synchronized. Scrolling pauses auto-follow, even at the
bottom: End, Esc or returning to input resumes it. Dragging text beyond the top or
bottom of the conversation scrolls while mouse-move events arrive; release over
the input/status area ends the drag without typing or activating tools.

Selections copy displayed text, including rendered Markdown and line-wrap breaks,
only from currently loaded content pages. Use `/copy` or Ctrl+Y **without a
selection** for retained source across pages. Appending unrelated output preserves
selection; reflow/resize, changing chat/page/folding, or changes/eviction affecting
the selected rows clear it rather than copying stale text. A new click, keyboard
line navigation, scrollbar drag, or returning to input also clears selection.

The header/status area shows the selected chat, task state, model, queue and
pairing/approval guidance. Input is isolated from refresh/resize. Type `/` to open
the completion menu; Up/Down selects and Enter inserts a completion.
`/model` opens the actual agent's selectable model configuration, including grouped
choices. Up/Down chooses, Enter applies, Esc cancels. Model work runs off the input
loop; if you start a new draft or leave input while loading, completion does not
steal focus (use `/model` again when ready). Unsupported models, failures, busy chats and stale sessions are explicit
errors, not silently forwarded `/model` prompts. Confirmed changes are sequenced
and broadcast to Android through its existing subscriber. No Android update is
required for the existing config-update event shape.

`/allow-all` (alias `/allow_all`) uses the same asynchronous configuration picker
and shared-session guards. It matches the Android option names (`allow_all`,
`allowAll`, `Allow All`, `allowAllPermissions`, `autoApprove`, `autoApproval`) across
ID, name and category. Advertised boolean options offer Off/On with string wire
values `"false"`/`"true"`; select options preserve the exact advertised values.
No option is invented for an unsupported agent. Enter opens a separate risk
confirmation showing the chat, current value and requested value: `y` applies,
`n` goes back and Esc cancels. Repeated Enter does not authorize the change.
Enabling automatic permission may allow command execution/file modification
without per-action prompts in this shared session. It never resolves existing
pending approvals. Confirmed values appear in the status area and are broadcast
to Android. Busy/stale sessions, rejection and unconfirmed values remain errors.

Agent Markdown (headings, emphasis, code, tables) is rendered in place and cached,
including incomplete streaming text, without repeating the final reply. Replies
over 8 Ki characters use source-text pages to keep layout bounded; tool details
are also paged. Narrow/deep layouts explicitly use source text. Table cells fold
rather than ellipsize. Raw HTML is unsupported. Only agent replies are formatted;
user messages, tool/approval details and labels are literal. Control sequences
are stripped before/after parsing and only generated styling reaches the UI.
Links/images do not fetch resources or execute code. `NO_COLOR=1` enables monochrome.

- `/new` guides you through installed agent and existing absolute workspace
  selection. `/new <agent-id> <absolute workspace>` skips those choices.
  Both create shared Chats that Android discovers automatically while foreground.
- `/resume` takes no arguments and lists saved sessions for the CURRENT Chat's
  agent/workspace, then requires `y` before restoring one in that same Chat.
  Use `/chats` to select a Chat first; use `/new` then `/resume` to keep an existing
  Chat separate. Enter cancels; choice input is never a prompt.
  Replay-capable agents import the latest five available message bubbles and
  activities; other agents explicitly restore context without pretending to replay
  history. No extra Chat is created. Changed/busy bindings reject stale selections.
  A context boundary separates retained earlier messages from imported history;
  the phone's same Chat receives the binding and messages.
- Android's confirmed Delete removes the shared Chat from this terminal too.
  Running/queued work and session changes must finish first. Native agent history
  and project files remain; durable deletion markers prevent stale re-registration.
- `/approvals` displays pending details. `/approve <approval-id>` requires prior
  review; `/deny <approval-id>` can reject immediately. Oversized details are
  explicitly truncated and must be approved on Android instead.
- `/pair y` approves a displayed pairing request; `/pair n` or `/pair` denies.
  Check the phone/code first. The ordinary server's `[y/N]` prompt is replaced
  only in interactive mode so no second stdin reader can steal chat input.
  The two-minute timeout, explicit approval, and device-token rules are unchanged.
- `/qrcode` (also `/pairing`) shows the original startup QR/link in a separate view.
  Interactive startup does not print the QR/link; ordinary stdlib/FastAPI startup
  still does. Incoming conversation events do not replace the QR view. Arrow keys
  scroll it; enlarge the window to fit the entire QR, or paste the link into
  Android. Esc returns to chat. An expired startup token requires restarting the
  bridge, as before; viewing it does not extend its validity.
- `/send <text>` can send messages beginning with `/`. Unknown commands are
  rejected, never run as shell commands. Advertised agent commands are available
  directly and in completion; reserved names keep AgentLink meaning. `/resume`
  opens the current-Chat session picker; `/allow-all` is the local config picker.
- `/copy` copies retained agent-text segments of the latest response in the selected
  chat, preserving Markdown source and all retained pages. Ctrl+Y in conversation
  focus copies highlighted display text when selected; otherwise it copies the
  current message, tool, or tool group (literal JSON). Truncated
  content/eviction is explicitly warned about; this is not a history download.
- `/mouse` toggles application mouse handling. Turn it off if you prefer the
  terminal's native drag-selection and Copy action; app selection, tool clicks and
  the draggable scrollbar are then disabled. Native selection behavior depends on
  the terminal emulator. Keyboard navigation still works; Ctrl+C still clears input.
  Clipboard writes target the bridge computer, not an SSH client's desktop.
  Windows uses PowerShell Set-Clipboard; macOS uses pbcopy; Linux uses an available
  wl-copy, xclip or xsel. No utility is installed automatically. Missing/unavailable
  desktop clipboards and timeouts are explicit errors. Content goes through stdin,
  not command arguments or executable shell text, and utility error bodies are
  not logged. Copy runs off the UI loop and never happens on incoming messages.
- `/quit` stops the bridge when idle; `/quit!` permits stopping during active work.
  Ctrl+C clears input only; Ctrl+D requests ordinary quit. EOF/closing the terminal stops the bridge. Shutdown
  disconnects Android and denies outstanding pairing; it is not a promise that
  an external agent's already-running command is cancelled. An in-flight configuration
  request completes under the agent's bounded timeout before worker shutdown.

`--interactive` requires a TTY and `--server stdlib`. Missing dependencies and
unsupported backends fail before tunnel setup. It suppresses routine runtime and
Android-delivery logs regardless of `--log-level`; errors and interactive
conversation/approval content remain visible. Python-side runtime/tunnel output
becomes literal notices rather than overwriting the alternate screen.

Terminal display queues are bounded. If the terminal cannot keep up, it explicitly
reports omitted display items without dropping Android events. `/approvals` reads
authoritative pending requests even after display overflow. Terminal chat metadata
is limited to 256 chats per bridge run; restart to reset this local view. The
selection and draft do not persist across restarts. The live transcript retains
at most 160 entries and roughly 2 Mi characters, with 128 Ki characters per text
entry, 64 tools per group and a 32 Ki-character tool projection budget. Excess
content/evicted entries are labelled; this is not a complete archive. Overflow or
eviction clears terminal approval-review markers: re-review or decide on Android.

## Console logs

The default `info` output summarizes work rather than printing each streamed
reply or tool-output update. Android still receives the original streaming events.

```powershell
python .\run.py start
python .\run.py start --log-level debug
python .\run.py start --log-level warning
```

Run these commands from `bridge`. Levels are `debug`, `info` (default),
`warning`, and `error`.

- Normal output includes task start/finish, the start of a reply, tool start/finish,
  approvals, and WebSocket open/close. Failures are visible without dumping their
  potentially sensitive payloads; inspect the Android error for details.
- Active tasks report counters at most once every 15 seconds, including while
  waiting for approval. Counts are **chunks and characters**, not model tokens.
- Tool output changes do not each generate a line. Configuration updates and
  successful HTTP requests appear only at `debug`.
- Debug adds event types/IDs, lengths and status, not prompt text, reply/thought
  bodies, command arguments, tool output, credentials, or request URLs.
- Reconnection reports how many events were replayed, without logging historical
  tool activity as new work. Multiple recipients do not duplicate business logs.
- While the computer waits at a pairing prompt, routine bridge logs are counted
  and replaced by one summary afterward; warnings/errors remain visible.

The level controls bridge runtime logs, not the Dev Tunnels child process or
startup onboarding. Pairing QR/link/code output remains visible at every level;
it contains sensitive pairing credentials and should not be shared as a log.
The optional FastAPI backend keeps its existing behavior; this change does not
add ACP streaming to that backend. Its Uvicorn access log is disabled to avoid
printing credential-bearing URLs.

## Requirements

The bridge may use third-party Python packages, but every dependency must be declared in `pyproject.toml` and exposed through a requirements file so users can install it with pip, uv, or conda.

Run these commands from the `bridge` directory.

| File | Purpose |
| --- | --- |
| `requirements.txt` | Base bridge runtime. |
| `requirements-fastapi.txt` | Base runtime plus the optional FastAPI server backend. |
| `requirements-interactive.txt` | Base runtime plus prompt-toolkit, Rich and markdown-it-py for terminal chat. |
| `requirements-all.txt` | Base runtime plus all optional extras. |

## Commands

```powershell
android-acp-bridge start
android-acp-bridge tailscale-status
android-acp-bridge pairing
```

`start` uses an authenticated Microsoft Dev Tunnel by default. Use `start --transport tailscale` for Tailscale; add `--no-tailscale-setup` to inspect its current state without installing or logging in.

The standalone `pairing` command prints a sample pairing payload for an endpoint:

```powershell
android-acp-bridge pairing --endpoint wss://example-4317.devtunnels.ms --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

Use `start --pairing-endpoint ...` instead when you need a pairing QR for a running bridge server.

## Optional Extras

```powershell
python -m pip install -r requirements-fastapi.txt
```

- `qrcode` is part of the required bridge runtime so pairing can render compact CLI QR codes by default.
- `fastapi` enables the optional FastAPI/uvicorn server backend.
