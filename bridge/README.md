# Android ACP Bridge

Python MVP bridge for pairing Android ACP Client with a remote developer machine.

## Development

```powershell
python .\run.py start --allow-non-tailscale
```

The default server backend uses only the Python standard library. It checks Tailscale status, starts a local HTTP/WebSocket server, creates a short-lived pairing token, and prints both an Android pairing link and a compact CLI QR code on every bridge startup.

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

`start` does not run `tailscale up` automatically by default. It prints guidance when Tailscale is missing, stopped, or needs login.

## Optional Extras

```powershell
python -m pip install -r requirements-fastapi.txt
```

- `qrcode` is part of the required bridge runtime so pairing can render compact CLI QR codes by default.
- `fastapi` enables the optional FastAPI/uvicorn server backend.
