# AgentLink

[English](README.md) | [中文](README.zh-CN.md)

Android-first client for controlling multiple remote ACP coding-agent environments.

The app is designed around **Chats**. Each chat belongs to a remote **Machine** and a selected **Workspace** on that machine. The remote machine runs an ACP bridge and one or more coding agents, while Android provides the mobile UI for prompts, streaming responses, approvals, diffs, and logs.

## Documentation

Start with:

- `CLAUDE.md` - repository agent harness and working rules
- `docs/README.md` - documentation map
- `docs/product-requirements.md` - product scope and user flows
- `docs/architecture.md` - app, bridge, ACP, and data model architecture
- `docs/android-app.md` - Android app pairing MVP and validation
- `docs/acp-bridge-contract.md` - Android-to-bridge API contract
- `docs/machine-pairing.md` - Tailscale detection and QR-based machine pairing
- `docs/security-model.md` - permissions, approvals, and threat model
- `docs/harness-engineering.md` - harness layers and enforcement plan

## Current Status

This repository currently contains the initial project harness, design documentation, a Python bridge MVP under `bridge/`, and an Android app skeleton under `app/`.

## Bridge startup options

Run bridge commands from the repository root unless noted otherwise. `bridge\run.py` creates `bridge\.venv`, installs `bridge\requirements.txt`, and runs the bridge CLI.

### Option 1: Tailscale private network (default)

Use this when both the developer machine and Android device can sign in to the same Tailscale tailnet.

```powershell
python .\bridge\run.py start
```

Startup behavior:

1. Checks whether the `tailscale` CLI exists.
2. If missing, attempts automatic install with a supported package manager.
3. If logged out or stopped, runs `tailscale up --qr`.
4. Waits until a Tailscale IP is available.
5. Binds the bridge to the Tailscale IP.
6. Prints an AgentLink pairing link and compact CLI QR code.

If Windows blocks `winget` with organization policy / exit code `1625`, install Tailscale through your company software portal, ask an administrator to approve `Tailscale.Tailscale`, or use the official installer from <https://tailscale.com/download/windows>.

### Option 2: Microsoft Dev Tunnels private relay

Use this when VPN/mesh networking is blocked but an authenticated Microsoft Dev Tunnel is acceptable. Do **not** use anonymous/public tunnels.

One command handles the tunnel setup, starts the Dev Tunnel host, starts the bridge on localhost, and prints the Android pairing QR:

```powershell
python .\bridge\run.py start --transport devtunnel
```

Startup behavior:

1. Finds `devtunnel` on `PATH`, or downloads `bridge\.tools\devtunnel.exe` on Windows.
2. Starts device-code login if the CLI is not already logged in.
3. Creates or reuses the `agentlink` tunnel.
4. Adds port `4317` with HTTP forwarding if needed.
5. Issues a short-lived `connect` token.
6. Starts `devtunnel host agentlink`.
7. Starts the bridge on `127.0.0.1:4317`.
8. Prints a pairing QR containing the `wss://*.devtunnels.ms` endpoint and `X-Tunnel-Authorization: tunnel <token>` header.

Optional overrides:

```powershell
python .\bridge\run.py start --transport devtunnel --devtunnel-id my-agentlink --port 4317
python .\bridge\run.py start --transport devtunnel --devtunnel-cli C:\tools\devtunnel.exe
```

Android stores the `X-Tunnel-Authorization` header with that machine and sends it on bridge requests. Dev Tunnel connect tokens are short-lived, so re-run the command and re-scan a fresh pairing QR when the token expires.

Manual mode is still available for debugging:

```powershell
python .\bridge\run.py start `
  --allow-non-tailscale `
  --host 127.0.0.1 `
  --port 4317 `
  --pairing-endpoint wss://<copied-devtunnel-host> `
  --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

### Option 3: Localhost/manual testing only

Use this only for local experiments. It does not make the developer machine reachable from a phone by itself.

```powershell
python .\bridge\run.py start --allow-non-tailscale
```

QR pairing only transfers endpoint metadata and credentials; network reachability still depends on Tailscale, Dev Tunnels, LAN, USB forwarding, or another transport.

## Debug APK Release

Run the **Build Android APK** GitHub Actions workflow manually. It builds `app-debug.apk` and attaches the APK directly to a prerelease instead of requiring users to download a zipped workflow artifact.
