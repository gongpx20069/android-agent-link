# Android App

This document describes the current AgentLink Android client implementation.

## Current Scope

The initial Android app supports machine onboarding plus an MVP chat shell:

- Compose application shell.
- Bottom tabs: Chats, Approvals, Machines, Settings.
- In-app QR scanning with CameraX and ML Kit Barcode Scanning.
- `acpclient://pair?data=...` deep-link handling.
- Manual pairing-link paste fallback.
- Pairing token redemption through the bridge.
- Secure machine storage using AndroidX Security encrypted preferences.
- Health, agents, and workspaces loading from the bridge.
- New Chat form with two modes: create a new ACP session, or open an existing resumable ACP session returned by the selected machine and agent.
- Chat list and WhatsApp-style chat detail view with full conversation history.
- Chat, approval, and machine list rows support left-swipe deletion. Deleting a pending approval sends a deny decision before removing it locally.
- Chat list rows and the chat detail header show a small status dot: busy while a prompt is running, idle otherwise.
- Opening a chat automatically scrolls to the newest message.
- Fixed bottom prompt box for sending chat messages.
- In Chat detail, the history list and prompt composer move above the Android soft keyboard while the header stays anchored; the composer does not keep the bottom navigation bar gap above the keyboard.
- Horizontally scrollable command chips above the prompt box.
- Chat prompt WebSocket calls disable the client read timeout, send WebSocket pings, and ignore bridge accepted/heartbeat events; the bridge responds to pings and sends heartbeat messages during long-running Agent turns so idle network paths do not abort the prompt while waiting for ACP updates.
- Target chat communication uses a persistent WebSocket per open chat, with `chat.attach`, `lastEventId`, bridge event replay, operation IDs, and bridge-authoritative `chat.status`. The older one-shot WebSocket request flow is transitional.
- Bilingual UI with a Settings language selector: System, English, or Chinese. System mode uses Chinese only when the device language is Chinese; otherwise it uses English.
- Settings includes a Session load history limit. It defaults to 5 and controls how many recent messages are appended when opening or resuming an existing ACP session.
- Built-in `model` chip that opens a model picker from ACP session config options.
- Common command chips are prioritized before other ACP-advertised commands: `model`, `resume`, and `allow-all`.
- Built-in `allow-all` opens an on/off picker when the ACP agent exposes the `allow_all` session config option.
- Agent/system message bubbles render basic Markdown: headings, bullets, quotes, fenced code blocks, bold, italic, inline code, and link-style text.
- Collapsible agent activity cards for ACP `tool_call` and `tool_call_update` events.
- Approval list with approve/deny actions backed by ACP `session/request_permission`.
- Automatic update checks on app startup and manual update checks from Settings.

GitHub Copilot CLI ACP execution is wired through the bridge when `copilot --acp` is available on the developer machine. The chat shell sends prompts through the bridge WebSocket and displays ACP `session/update` responses as agent messages and expandable activity cards. Claude Code requires the `claude` CLI to be installed and expose an ACP server command.

## Pairing UX

The bridge prints both an Android pairing link and a compact CLI QR code on every startup.

The Android app supports two onboarding paths:

1. Scan the QR code in the Machines tab with the built-in camera scanner.
2. Paste the `acpclient://pair?data=...` link into the Machines tab.

The scanner only accepts `acpclient://pair` QR payloads. Camera permission is requested when the scanner opens.

## Storage

Paired machine records include endpoint, bridge fingerprint, and device token. These are stored through encrypted shared preferences. Do not replace this with plain shared preferences unless a separate secure-storage design is documented first.

Paired machine records may also include per-machine connection headers, such as `X-Tunnel-Authorization` for a private Microsoft Dev Tunnel. Treat these headers like short-lived credentials: store them only in the encrypted machine record, send them only to that machine endpoint, and do not log them.

## Network Contract

The app maps the pairing endpoint from WebSocket to HTTP for setup calls:

- `ws://host:port` -> `http://host:port`
- `wss://host:port` -> `https://host:port`

The MVP permits cleartext HTTP/WebSocket for private Tailscale or LAN endpoints. Public endpoints must use HTTPS/WSS before they are considered supported.

After pairing, the app calls:

- `POST /pairing/redeem`
- `GET /health`
- `GET /agents`
- `GET /workspaces`

If the pairing payload includes `headers`, the app sends those headers with all bridge HTTP calls for that machine.

The chat shell uses:

- `WS /ws?token=<deviceToken>`

The app maps these bridge/ACP events:

- `chat.attached` -> current chat WebSocket is attached to the bridge-side chat channel.
- `chat.status` -> authoritative chat status for busy/idle/waitingApproval/disconnected UI.
- `operation.accepted` -> the bridge accepted a prompt/load/config operation.
- `operation.done` -> the bridge completed a prompt/load/config operation.
- `chat.resyncRequired` -> Android's last event is outside the bridge replay cache; reload or reopen the session.
- `session/update` + `tool_call` -> collapsed Agent Activity card.
- `session/update` + `tool_call_update` -> collapsed Agent Activity card with status/details.
- Updates with the same `toolCallId` replace the existing activity card, so one tool call stays as one expandable row.
- `session/update` + `agent_message_chunk` -> normal agent chat bubble. Streaming chunks are merged into one bubble instead of one word per message.
- Empty `agent_thought_chunk` updates are ignored; non-empty thought chunks appear as expandable activity.
- `available_commands_update` -> command chips above the prompt box.
- `config_option_update` -> powers the built-in `model` picker and is hidden from the chat timeline.
- `usage_update` is suppressed in chat to avoid noisy setup cards on every prompt.
- `bridge.heartbeat` -> transport keepalive; ignored for chat history.
- `bridge.done` -> legacy one-shot request terminator during the transition to persistent chat channels.

## Persistent Chat Channel Design

The target Android connection model is one `ChatConnection` per active chat. The connection opens when a chat detail screen is active or a background operation needs to keep the chat live.

On connect or reconnect, Android sends:

```json
{
  "type": "chat.attach",
  "chatId": "chat_123",
  "lastEventId": 128
}
```

Android responsibilities:

- Persist the latest `eventId` seen for each chat.
- Apply replayed events idempotently.
- Render busy/idle/waitingApproval from bridge `chat.status`, not from WebSocket open/closed state.
- Treat WebSocket disconnect as transport state only; do not mark the agent idle unless the bridge sends `chat.status: idle`.
- Retry `chat.attach` with exponential backoff while the user is viewing the chat.
- Keep pending approvals visible after reconnect by replaying `approval.requested` events.

The bridge is responsible for event ordering and replay. Android is responsible for caching applied events and avoiding duplicate timeline entries.

## Workspace Selection

The bridge does not bind a workspace at startup. In New Session mode, workspace is selected per chat in the New Chat form by entering the remote absolute project path. Leaving the field blank uses `~`, which the bridge resolves to the remote machine user's home directory. The selected path maps to ACP `cwd` when the bridge creates the Copilot ACP session.

In Existing Session mode, the user does not enter a workspace. AgentLink asks the selected machine and agent for all resumable sessions with ACP `session/list {}`. The selected session's own `cwd` becomes the local chat workspace display and is used when loading that session.

The workspace does not have to be a Git repository, but Copilot's coding workflows work best inside a repository. If a parent folder such as `D:\peixianws` is selected instead of `D:\peixianws\android-agent-link`, Git-aware commands may report that the current directory is not a Git repository.

## Slash Commands and Resume

ACP slash commands are not the same as Copilot CLI's interactive slash commands. In ACP, commands must be advertised by the agent through `available_commands_update` and then sent as normal prompt text, such as `/plan ...` when that command is available.

AgentLink displays advertised commands as chips without the slash prefix. Tapping a command chip sends `/<command>` as a prompt.

`resume` is a built-in AgentLink chip rather than a prompt command. It opens a session picker backed by ACP `session/list`; choosing a session calls `session/load` for the current chat and workspace. AgentLink appends at most the latest N loaded chat messages from a resumed session, where N is configured in Settings and defaults to 5. Typing `/resume` in the prompt is still treated as plain prompt text unless the ACP agent explicitly advertises a `resume` slash command.

`model` is also a built-in AgentLink chip. It opens a model picker backed by the latest ACP `config_option_update` option with `id == "model"`, `category == "model"`, or a model-like name. Opening the picker asks the bridge to refresh config options for the current chat, creating an ACP session first if needed. Selecting a model sends ACP `session/set_config_option` through the bridge and updates the picker with the returned `configOptions`. If options have not arrived yet, the picker explains that config options are still loading instead of sending `/model` as a prompt.

`allow-all` is a built-in AgentLink chip backed by ACP config options such as `allow_all`, `allowAll`, or `Allow All`. It opens a small on/off picker and sends ACP `session/set_config_option` when changed; boolean config options are normalized into On/Off choices. Opening the picker also refreshes config options from the bridge.

## Approval Flow

When the ACP agent sends `session/request_permission`, the bridge immediately emits `approval.requested` to Android and pauses the ACP response. AgentLink adds the request to the Approvals tab. Approving or denying sends `approval.decide` back to the bridge, which resolves the pending ACP permission request with the selected allow/reject option.

## Validation

PR prechecks for Android are defined in `.github/workflows/android-pr-prechecks.yml` and run:

```text
gradle :app:testDebugUnitTest
gradle :app:compileDebugKotlin
```

Local Android validation requires Gradle and Android SDK. If those are not installed locally, rely on PR prechecks for Android compilation and unit tests.

Manual APK builds are defined in `.github/workflows/android-build-apk.yml`. Triggering the workflow creates a prerelease with the next `0.0.x` tag by default and uploads the signed release APK.

## Signed APK Releases and Updates

Android requires updates to be signed with the same key as the installed app. The release workflow builds `assembleRelease` and signs it with these GitHub Secrets:

- `AGENTLINK_RELEASE_KEYSTORE_BASE64`
- `AGENTLINK_RELEASE_KEYSTORE_PASSWORD`
- `AGENTLINK_RELEASE_KEY_ALIAS`
- `AGENTLINK_RELEASE_KEY_PASSWORD`

Generate a keystore once, keep it private, and store the base64-encoded `.jks` file in `AGENTLINK_RELEASE_KEYSTORE_BASE64`. After one uninstall/reinstall from the signed release APK, future signed releases can update in-place.

The app checks `https://api.github.com/repos/gongpx20069/android-agent-link/releases` for the newest `0.0.x` release. Settings also provides a manual update check and opens the APK asset or release page in the browser/system installer.
