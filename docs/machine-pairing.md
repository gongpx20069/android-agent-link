# Machine Pairing

This document defines how a developer machine becomes available to AgentLink.

## Goal

Starting the bridge on a developer machine should lead the user to a scannable QR code. The Android app scans the QR code, connects to the bridge, completes pairing, and saves the machine.

The QR code does not create network connectivity by itself. It transfers endpoint metadata and a short-lived pairing credential. Connectivity still depends on Tailscale, another private network, LAN, or an explicitly configured tunnel.

## Recommended Flow

```text
Start bridge on developer machine
  detect Tailscale
  if unavailable, attempt automatic install and show platform guidance if install cannot run
  if installed but not running/logged in, run `tailscale up --qr`
  when reachable, start bridge listener
  create one-time pairing token
  show Android pairing QR code
Android scans QR
  connect to bridge endpoint
  redeem pairing token for device token
  save Machine
```

## Bridge Startup State Machine

```text
starting
  -> tailscale_cli_missing
  -> tailscale_needs_login
  -> tailscale_stopped
  -> tailscale_running
  -> bridge_listening
  -> pairing_ready
```

### `tailscale_cli_missing`

The bridge cannot find the `tailscale` CLI.

Bridge behavior:

- Continue only if the user explicitly chooses a non-Tailscale transport.
- Try to install Tailscale automatically when a supported installer is available: `winget` on Windows, Homebrew on macOS, or the official Tailscale install script on Linux.
- Show installation guidance for the current OS when automatic installation cannot run or fails.
- If Windows blocks `winget` with organization policy / exit code 1625, do not bypass policy; instruct the user to install through their company software portal, ask an administrator to approve Tailscale, or use the official Windows installer.
- Do not generate a Tailscale endpoint.

### `tailscale_needs_login`

Tailscale is installed but not authenticated.

Bridge behavior:

- Run `tailscale up --qr` by default.
- If supported, use `tailscale up --qr` to show a Tailscale login QR code for the developer machine.
- Poll `tailscale status --json` until the backend state becomes running or the user cancels.

This is a Tailscale login QR code, not the AgentLink pairing QR code.

### `tailscale_stopped`

Tailscale is installed and authenticated but disconnected.

Bridge behavior:

- Run `tailscale up --qr` by default.
- Wait for a running state before generating a Tailscale endpoint.

### `tailscale_running`

Tailscale is connected.

Bridge behavior:

- Read the Tailscale IP and machine DNS name when available.
- Put the Tailscale IPv4 address in the pairing endpoint when available so Android connectivity does not depend on MagicDNS; use DNS only when no Tailscale IP is available.
- Prefer a private-network endpoint over public endpoints.
- Bind the bridge listener to a private interface when possible.

### `pairing_ready`

The bridge listener is up and a pairing token exists.

Bridge behavior:

- Display a compact CLI QR code containing endpoint metadata and a one-time token.
- Display the underlying `acpclient://pair?...` link next to the QR code so the user can paste it manually if scanning is unavailable.
- Expire the token quickly.
- Require local confirmation on the developer machine when a device redeems the token.

## Tailscale Detection

The bridge should use the Tailscale CLI when available.

Useful commands:

```powershell
tailscale status --json
tailscale up
tailscale up --json
tailscale up --qr
```

Expected checks:

- CLI exists.
- Backend state is running.
- The machine has at least one Tailscale IP.
- Optional: MagicDNS name is available.

`tailscale up` connects the device and authenticates if needed. The Tailscale CLI supports JSON output for `up`, and `tailscale up --qr` can generate a QR code for the Tailscale web login URL.

The bridge startup command requires Tailscale by default. `--allow-non-tailscale` is reserved for localhost/manual testing and should not be used for normal Android pairing.

The bridge does not select a workspace during pairing. Workspace selection happens later in Android when creating a chat.

## Android Pairing QR Payload

The Android pairing QR should use a deep link:

```text
acpclient://pair?data=BASE64URL_JSON
```

Decoded payload:

```json
{
  "version": 1,
  "type": "acp-bridge-pairing",
  "machineName": "developer-machine",
  "endpoint": "ws://100.64.0.10:4317",
  "pairingId": "pair_01HZZ...",
  "pairingToken": "short-lived-one-time-token",
  "expiresAt": "2026-07-05T14:00:00Z",
  "bridgeFingerprint": "sha256:...",
  "headers": {
    "X-Tunnel-Authorization": "tunnel short-lived-dev-tunnel-connect-token"
  }
}
```

Rules:

- `pairingToken` must be one-time use.
- `pairingToken` should expire within 2-5 minutes.
- The payload must not contain long-lived GitHub tokens, SSH keys, OAuth tokens, or API keys. Short-lived relay access headers such as Dev Tunnels connect tokens are allowed only when required to reach the bridge.
- `endpoint` should prefer a Tailscale or private-network address.
- `bridgeFingerprint` should be saved by Android and checked on future connections.
- `headers` is optional per-machine connection metadata. Android must persist it with the paired machine and send it on every bridge HTTP/WebSocket request for that machine.
- Only relay-required headers should be included. The MVP supports `X-Tunnel-Authorization: tunnel <token>` for Microsoft Dev Tunnels private access.
- For authenticated relay transports, generate the QR/link from the running bridge with `start --pairing-endpoint <wss-relay-url> --connection-header ...`. Do not use a standalone payload from `pairing` for a running server, because that command creates a separate token store.

## Pairing Handshake

After scanning, Android connects to the bridge and redeems the token:

```json
{
  "type": "pairing.redeem",
  "pairingId": "pair_01HZZ...",
  "pairingToken": "short-lived-one-time-token",
  "device": {
    "name": "Pixel",
    "platform": "android",
    "appVersion": "0.1.0"
  }
}
```

The bridge should show a local confirmation:

```text
Allow Pixel to pair with this machine?
[Approve] [Deny]
```

On approval, the bridge returns a long-lived but revocable device token:

```json
{
  "type": "pairing.completed",
  "machineId": "machine_abc",
  "deviceToken": "revocable-device-token",
  "bridgeFingerprint": "sha256:..."
}
```

Android stores the device token in secure platform storage.

## Android UX

Machine setup flow:

```text
Machines
  -> Add Machine
  -> Scan QR Code
  -> Test Connection
  -> Save Machine
```

If the endpoint is unreachable, the app should explain likely causes:

- Android is not connected to the same Tailscale tailnet.
- Tailscale is disabled on Android.
- The bridge is no longer running.
- The QR code expired.
- A firewall blocked the selected port.

## Non-Tailscale Fallbacks

Supported:

- Microsoft Dev Tunnels private relay with `android-acp-bridge start --transport devtunnel`. The bridge downloads or finds the `devtunnel` CLI, triggers device-code login when needed, creates/reuses a tunnel, creates the port, issues a short-lived connect token, starts `devtunnel host`, and includes the `wss://*.devtunnels.ms` endpoint plus `X-Tunnel-Authorization` header in the Android pairing QR.

Supported later:

- Manual endpoint entry.
- LAN endpoint.
- ZeroTier or WireGuard endpoint.

Fallbacks must preserve the same pairing-token and local-confirmation model.
