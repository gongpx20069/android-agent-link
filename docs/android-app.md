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
- Opening a chat automatically scrolls to the newest message.
- Fixed bottom prompt box for sending chat messages.
- Horizontally scrollable command chips above the prompt box.
- Bilingual UI with a Settings language selector: System, English, or Chinese. System mode uses Chinese only when the device language is Chinese; otherwise it uses English.
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

- `session/update` + `tool_call` -> collapsed Agent Activity card.
- `session/update` + `tool_call_update` -> collapsed Agent Activity card with status/details.
- Updates with the same `toolCallId` replace the existing activity card, so one tool call stays as one expandable row.
- `session/update` + `agent_message_chunk` -> normal agent chat bubble. Streaming chunks are merged into one bubble instead of one word per message.
- Empty `agent_thought_chunk` updates are ignored; non-empty thought chunks appear as expandable activity.
- `available_commands_update` -> command chips above the prompt box.
- `config_option_update` -> powers the built-in `model` picker and is hidden from the chat timeline.
- `usage_update` is suppressed in chat to avoid noisy setup cards on every prompt.
- `bridge.done` -> ends the current one-shot WebSocket request.

## Workspace Selection

The bridge does not bind a workspace at startup. In New Session mode, workspace is selected per chat in the New Chat form by entering the remote absolute project path. Leaving the field blank uses `~`, which the bridge resolves to the remote machine user's home directory. The selected path maps to ACP `cwd` when the bridge creates the Copilot ACP session.

In Existing Session mode, the user does not enter a workspace. AgentLink asks the selected machine and agent for all resumable sessions with ACP `session/list {}`. The selected session's own `cwd` becomes the local chat workspace display and is used when loading that session.

The workspace does not have to be a Git repository, but Copilot's coding workflows work best inside a repository. If a parent folder such as `D:\peixianws` is selected instead of `D:\peixianws\android-agent-link`, Git-aware commands may report that the current directory is not a Git repository.

## Slash Commands and Resume

ACP slash commands are not the same as Copilot CLI's interactive slash commands. In ACP, commands must be advertised by the agent through `available_commands_update` and then sent as normal prompt text, such as `/plan ...` when that command is available.

AgentLink displays advertised commands as chips without the slash prefix. Tapping a command chip sends `/<command>` as a prompt.

`resume` is a built-in AgentLink chip rather than a prompt command. It opens a session picker backed by ACP `session/list`; choosing a session calls `session/load` for the current chat and workspace. Typing `/resume` in the prompt is still treated as plain prompt text unless the ACP agent explicitly advertises a `resume` slash command.

`model` is also a built-in AgentLink chip. It appears after the agent sends a `config_option_update` containing a select option with `id == "model"` or `category == "model"`. Selecting a model sends ACP `session/set_config_option` through the bridge and updates the picker with the returned `configOptions`.

`allow-all` is a built-in AgentLink chip backed by the ACP `allow_all` config option. It opens a small on/off picker and sends ACP `session/set_config_option` when changed.

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
