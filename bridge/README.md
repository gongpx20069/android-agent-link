# AgentLink Bridge

Python MVP bridge for pairing AgentLink with a remote developer machine.

## Development setup

Recommended conda setup:

```powershell
cd bridge
conda env create -f environment.yml
conda activate android-acp-bridge
android-acp-bridge start
```

If the environment already exists:

```powershell
cd bridge
conda env update -n android-acp-bridge -f environment.yml --prune
conda activate android-acp-bridge
```

Source checkout helper:

```powershell
python .\run.py start
```

The default server backend uses only the Python standard library. `run.py` creates `bridge\.venv` on first use, installs `requirements.txt`, and forwards every argument to the bridge CLI.

## Starting the bridge

### Tailscale mode (default)

```powershell
python .\run.py start
```

Tailscale mode starts the local HTTP/WebSocket server on the machine's Tailscale IP, creates a short-lived pairing token, and prints both an AgentLink pairing link and a compact CLI QR code.

Default Tailscale setup flow:

1. Check whether the `tailscale` CLI is installed.
2. If missing, try to install it automatically with `winget` on Windows, Homebrew on macOS, or the official Tailscale install script on Linux.
3. If installed but logged out or stopped, run `tailscale up --qr` so you can complete Tailscale login.
4. Re-check status and only generate the Android pairing QR after a Tailscale IP is available.

The Android device must also be signed in to the same Tailscale tailnet. Use `--allow-non-tailscale` only for localhost/manual testing.

If Windows reports `组织策略正在阻止安装` / installer exit code `1625`, your organization blocks `winget` installs. The bridge will not bypass that policy; install Tailscale from your company software portal, ask an administrator to approve `Tailscale.Tailscale`, or use the official installer from <https://tailscale.com/download/windows>, then re-run `python .\run.py start`.

### Choosing workspaces

The bridge does not bind a workspace at startup. A workspace is chosen when creating a new chat in Android. Enter the remote absolute project path in the New Chat form; that value becomes the chat workspace and will map to ACP `cwd` when agent-session execution is connected.

### Microsoft Dev Tunnels private relay

Use this when Tailscale/ZeroTier are blocked but a private authenticated Microsoft relay is acceptable. Do not enable anonymous Dev Tunnel access.

With conda:

```powershell
conda activate android-acp-bridge
devtunnel user login -d
android-acp-bridge start --transport devtunnel
```

Source checkout helper:

```powershell
python .\run.py start --transport devtunnel
```

What it does:

1. Finds `devtunnel` on `PATH`, or downloads `bridge\.tools\devtunnel.exe` on Windows.
2. Starts `devtunnel user login -d` if login is required.
3. Creates or reuses a machine-specific `agentlink-<hostname>` tunnel.
4. Adds the bridge port with HTTP forwarding if needed.
5. Issues a short-lived `connect` token.
6. Starts `devtunnel host agentlink` as a child process.
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

Optional overrides:

```powershell
python .\run.py start --transport devtunnel --devtunnel-id my-agentlink
python .\run.py start --transport devtunnel --devtunnel-cli C:\tools\devtunnel.exe
```

If tunnel creation fails with `Conflict with existing entity`, the tunnel ID is already taken but not visible to your account. Retry with a unique ID:

```powershell
python .\run.py start --transport devtunnel --devtunnel-id agentlink-<yourname>-<devbox>
```

Android stores the relay header per machine and sends it on `/pairing/redeem`, `/health`, `/agents`, `/workspaces`, and future WebSocket requests for that machine. Dev Tunnel connect tokens currently expire after a short period, so re-run the command and re-scan when access expires.

Manual debugging flow:

If you need to run `devtunnel host` yourself, start the AgentLink bridge separately with the relay endpoint and connect token:

```powershell
python .\run.py start `
  --allow-non-tailscale `
  --host 127.0.0.1 `
  --port 4317 `
  --pairing-endpoint wss://<copied-devtunnel-host> `
  --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

Why `--pairing-endpoint` matters: the pairing token is created by the running bridge process. Do not use the standalone `pairing` command for the active Dev Tunnel server, because it creates a separate one-off token that the running server will not recognize.

### Localhost/manual testing

```powershell
python .\run.py start --allow-non-tailscale
```

This prints a QR/link for `ws://127.0.0.1:4317`. It is useful for local testing but will not make a developer machine reachable from Android unless another transport forwards the port.

Package installation is still supported when you want the command on your PATH:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\android-acp-bridge start
```

## Requirements

The bridge may use third-party Python packages, but every dependency must be declared in `pyproject.toml` and exposed through a requirements file so users can install it with pip, uv, or conda.

Run these commands from the `bridge` directory.

| File | Purpose |
| --- | --- |
| `requirements.txt` | Base bridge runtime. |
| `requirements-fastapi.txt` | Base runtime plus the optional FastAPI server backend. |
| `requirements-all.txt` | Base runtime plus all optional extras. |

Install with uv:

```powershell
uv venv
uv pip install -r requirements.txt
```

Install with conda:

```powershell
conda env create -f environment.yml
conda activate android-acp-bridge
android-acp-bridge start
```

## Commands

```powershell
python .\run.py start
python .\run.py tailscale-status
python .\run.py pairing
```

`start` runs automatic Tailscale setup by default. Use `--no-tailscale-setup` to only inspect current Tailscale status without installing or logging in.

The standalone `pairing` command prints a sample pairing payload for an endpoint:

```powershell
python .\run.py pairing --endpoint wss://example-4317.devtunnels.ms --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

Use `start --pairing-endpoint ...` instead when you need a pairing QR for a running bridge server.

## Optional Extras

```powershell
python -m pip install -r requirements-fastapi.txt
```

- `qrcode` is part of the required bridge runtime so pairing can render compact CLI QR codes by default.
- `fastapi` enables the optional FastAPI/uvicorn server backend.
