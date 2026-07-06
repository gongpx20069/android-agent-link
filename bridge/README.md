# AgentLink Bridge

Python MVP bridge for pairing AgentLink with a remote developer machine.

## Development

```powershell
python .\run.py start
```

The default server backend uses only the Python standard library. It requires Tailscale by default, checks Tailscale status, starts a local HTTP/WebSocket server on the machine's Tailscale IP, creates a short-lived pairing token, and prints both an Android pairing link and a compact CLI QR code on every bridge startup.

Default Tailscale setup flow:

1. Check whether the `tailscale` CLI is installed.
2. If missing, try to install it automatically with `winget` on Windows, Homebrew on macOS, or the official Tailscale install script on Linux.
3. If installed but logged out or stopped, run `tailscale up --qr` so you can complete Tailscale login.
4. Re-check status and only generate the Android pairing QR after a Tailscale IP is available.

The Android device must also be signed in to the same Tailscale tailnet. Use `--allow-non-tailscale` only for localhost/manual testing.

If Windows reports `组织策略正在阻止安装` / installer exit code `1625`, your organization blocks `winget` installs. The bridge will not bypass that policy; install Tailscale from your company software portal, ask an administrator to approve `Tailscale.Tailscale`, or use the official installer from <https://tailscale.com/download/windows>, then re-run `python .\run.py start`.

`run.py` creates `bridge\.venv` on first use, installs `requirements.txt`, and forwards every argument to the bridge CLI. Package installation is still supported when you want the command on your PATH:

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
```

## Commands

```powershell
python .\run.py start
python .\run.py tailscale-status
python .\run.py pairing
```

`start` runs automatic Tailscale setup by default. Use `--no-tailscale-setup` to only inspect current Tailscale status without installing or logging in.

For authenticated relay transports such as private Microsoft Dev Tunnels, include the required connection header in the pairing QR/link:

```powershell
python .\run.py pairing --endpoint wss://example-4317.devtunnels.ms --connection-header "X-Tunnel-Authorization=tunnel <connect-token>"
```

Android stores that header per machine and sends it on future bridge requests for that machine.

## Optional Extras

```powershell
python -m pip install -r requirements-fastapi.txt
```

- `qrcode` is part of the required bridge runtime so pairing can render compact CLI QR codes by default.
- `fastapi` enables the optional FastAPI/uvicorn server backend.
