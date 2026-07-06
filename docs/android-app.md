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
- New Chat form with machine, per-chat workspace path, and agent selection.
- Chat list and WhatsApp-style chat detail view with full conversation history.
- Fixed bottom prompt box for sending chat messages.
- Collapsible agent activity cards for ACP `tool_call` and `tool_call_update` events.
- Approval list with approve/deny actions.

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
- `session/update` + `agent_message_chunk` -> normal agent chat bubble.
- `available_commands_update`, `config_option_update`, and `usage_update` are suppressed in chat to avoid noisy setup cards on every prompt.
- `bridge.done` -> ends the current one-shot WebSocket request.

## Workspace Selection

The bridge does not bind a workspace at startup. Workspace is selected per chat in the New Chat form by entering the remote absolute project path. That path maps to ACP `cwd` when the bridge creates the Copilot ACP session.

## Validation

PR prechecks for Android are defined in `.github/workflows/android-pr-prechecks.yml` and run:

```text
gradle :app:testDebugUnitTest
gradle :app:compileDebugKotlin
```

Local Android validation requires Gradle and Android SDK. If those are not installed locally, rely on PR prechecks for Android compilation and unit tests.
