# Machine Pairing

This document defines how a developer machine becomes available to AgentLink.

## Goal

Starting the bridge on a developer machine should lead the user to a scannable QR code. The Android app scans the QR code, connects to the bridge, completes pairing, and saves the machine. The default authenticated Dev Tunnel transport does not require a companion networking app on Android.

The QR code does not create network connectivity by itself. It transfers endpoint metadata and a short-lived pairing credential. Connectivity still depends on Tailscale, another private network, LAN, or an explicitly configured tunnel.

## Recommended Flow

Account discovery is an alternative to scanning. Phone and bridge must use the
same provider and account; a GitHub login cannot enumerate Entra-owned tunnels.
For explicit provider selection on the computer:

```powershell
python .\bridge\run.py start --devtunnel-login github
# Or, after configuring Microsoft login in the Android build:
python .\bridge\run.py start --devtunnel-login microsoft
```

Without `--devtunnel-login`, startup reuses the current CLI account. Tunnel and
port metadata are labelled `agentlink`, without enabling anonymous access.
Default tunnel IDs are now deterministic per hostname (`agentlink-<host>-<hash>`)
so different computers do not contend for a single `agentlink` tunnel. Existing
users can pass `--devtunnel-id agentlink` to retain their prior address, or select
the new computer entry and pair again; old tunnels are not deleted automatically.

The phone lists discovered computers, requests pairing, and shows a six-digit
confirmation code. The developer compares it with the bridge console and enters
`y` or `yes` within two minutes to approve. Enter or any other input denies the
request; the numeric code is for visual comparison only. Account pairing always requires this step,
even if legacy QR auto-approval was explicitly enabled. Merely reaching the
authenticated tunnel does not authorize operating the machine.

```text
Start bridge on developer machine
  select transport (default: authenticated Dev Tunnel)
  authenticate and start the selected transport
  start bridge listener
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
  -> devtunnel_authenticating (default)
  -> devtunnel_hosting
  -> tailscale_cli_missing (optional Tailscale transport)
  -> tailscale_needs_login
  -> tailscale_stopped
  -> tailscale_running
  -> bridge_listening
  -> pairing_ready
```

### `devtunnel_authenticating`

A cached account returned by `devtunnel user show` does not guarantee that its
login token is still valid. Authentication failures during tunnel lookup,
creation, port setup, or connect-token issuance stop startup with an explicit
login command using the resolved CLI path. They must not be treated as a missing
tunnel or fall back to anonymous access. The user signs in again and restarts
the bridge; this does not add continuous token renewal.

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

- Run `tailscale up --qr` automatically when the user selects Tailscale transport.
- If supported, use `tailscale up --qr` to show a Tailscale login QR code for the developer machine.
- Poll `tailscale status --json` until the backend state becomes running or the user cancels.

This is a Tailscale login QR code, not the AgentLink pairing QR code.

### `tailscale_stopped`

Tailscale is installed and authenticated but disconnected.

Bridge behavior:

- Run `tailscale up --qr` automatically when the user selects Tailscale transport.
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

Use `android-acp-bridge start --transport tailscale` to select this transport. Tailscale must be installed and connected on both the developer machine and Android device, using the same tailnet. ZeroTier follows the same two-client model but is not currently automated by AgentLink.

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

Dev Tunnel access tokens are intentionally short-lived. Account-paired machines
retrieve fresh connect tokens from the management service as needed, including
after the old relay token expires. This requires a valid identity login and does
not refresh the computer CLI login or keep a tunnel host running indefinitely.
For legacy QR-paired machines, if relay authorization expires,
restart the bridge as needed and scan its new pairing link to update the saved
machine credentials. Re-pairing updates the existing machine without deleting chats.
Bridge device authentication survives a normal bridge restart separately from the
relay credential. Authentication errors and network disconnection are distinct:
retry handles temporary connectivity, while a stale relay credential needs a new
pairing link.

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

- Microsoft Dev Tunnels private relay with `android-acp-bridge start` (or explicit `--transport devtunnel`). The bridge downloads or finds the `devtunnel` CLI, triggers device-code login when needed, creates/reuses a tunnel, creates the port, issues a short-lived connect token, starts `devtunnel host`, and includes the `wss://*.devtunnels.ms` endpoint plus `X-Tunnel-Authorization` header in the Android pairing QR. Android does not need a Dev Tunnel companion app.

Supported later:

- Manual endpoint entry.
- LAN endpoint.
- ZeroTier or WireGuard endpoint.

Fallbacks must preserve the same pairing-token and local-confirmation model.
