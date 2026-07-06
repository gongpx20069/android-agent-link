# Android App

This document describes the current AgentLink Android client implementation.

## Current Scope

The initial Android app focuses on machine onboarding:

- Compose application shell.
- Bottom tabs: Chats, Approvals, Machines, Settings.
- In-app QR scanning with CameraX and ML Kit Barcode Scanning.
- `acpclient://pair?data=...` deep-link handling.
- Manual pairing-link paste fallback.
- Pairing token redemption through the bridge.
- Secure machine storage using AndroidX Security encrypted preferences.
- Health, agents, and workspaces loading from the bridge.

Chats and approvals are placeholder screens until machine/workspace onboarding is stable.

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

Future chat support will use:

- `WS /ws?token=<deviceToken>`

## Validation

PR prechecks for Android are defined in `.github/workflows/android-pr-prechecks.yml` and run:

```text
gradle :app:testDebugUnitTest
gradle :app:compileDebugKotlin
```

Local Android validation requires Gradle and Android SDK. If those are not installed locally, rely on PR prechecks for Android compilation and unit tests.
