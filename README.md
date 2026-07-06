# AgentLink

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

## Bridge MVP

```powershell
python .\bridge\run.py start
```

The bridge requires Tailscale by default. On startup it checks for the Tailscale CLI, tries to install it automatically when a supported package manager is available, runs `tailscale up --qr` for login/connect, then prints an Android pairing link and compact CLI QR code that points at the machine's Tailscale endpoint. Sign in to the same Tailscale tailnet on Android before scanning.

Use `--allow-non-tailscale` only for localhost/manual testing; QR pairing does not provide network connectivity by itself.

## Debug APK Release

Run the **Build Android APK** GitHub Actions workflow manually. It builds `app-debug.apk` and attaches the APK directly to a prerelease instead of requiring users to download a zipped workflow artifact.
