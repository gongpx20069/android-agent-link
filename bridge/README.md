# Android ACP Bridge

Python MVP bridge for pairing Android ACP Client with a remote developer machine.

## Development

```powershell
cd bridge
$env:PYTHONPATH="$PWD\src"
python -m android_acp_bridge.main start --allow-non-tailscale
```

The default server backend uses only the Python standard library. It checks Tailscale status, starts a local HTTP/WebSocket server, creates a short-lived pairing token, and prints an Android pairing QR payload.

Optional package installation is still supported:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .
.\.venv\Scripts\android-acp-bridge start
```

## Commands

```powershell
android-acp-bridge start
android-acp-bridge tailscale-status
android-acp-bridge pairing
```

`start` does not run `tailscale up` automatically by default. It prints guidance when Tailscale is missing, stopped, or needs login.

## Optional Extras

```powershell
python -m pip install -e .[qr]
python -m pip install -e .[fastapi]
```

- `qr` enables terminal QR rendering with the `qrcode` package.
- `fastapi` enables the optional FastAPI/uvicorn server backend.
