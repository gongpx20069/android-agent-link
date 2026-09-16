# AgentLink Bridge

Connect your computer's coding agents to the AgentLink Android app.
For the shortest setup path, start with the [AgentLink quick start](../README.md).
This guide covers additional installation and connection options.

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
the six-digit confirmation code. Type the exact code in this console to approve.
Keep the console available for first pairing. QR scanning remains available.
Microsoft phone account discovery is temporarily disabled after personal-account
tunnel authorization failed in live testing. Computer CLI login with
`--devtunnel-login microsoft` still works independently with QR pairing; users
do not need to register an application. GitHub phone login, discovery, and pairing
remain unverified end to end. Use QR pairing if account discovery is unavailable.
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

## Requirements

The bridge may use third-party Python packages, but every dependency must be declared in `pyproject.toml` and exposed through a requirements file so users can install it with pip, uv, or conda.

Run these commands from the `bridge` directory.

| File | Purpose |
| --- | --- |
| `requirements.txt` | Base bridge runtime. |
| `requirements-fastapi.txt` | Base runtime plus the optional FastAPI server backend. |
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
