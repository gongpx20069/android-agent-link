# Android ACP Client

Android-first client for controlling multiple remote ACP coding-agent environments.

The app is designed around **Chats**. Each chat belongs to a remote **Machine** and a selected **Workspace** on that machine. The remote machine runs an ACP bridge and one or more coding agents, while Android provides the mobile UI for prompts, streaming responses, approvals, diffs, and logs.

## Documentation

Start with:

- `CLAUDE.md` - repository agent harness and working rules
- `docs/README.md` - documentation map
- `docs/product-requirements.md` - product scope and user flows
- `docs/architecture.md` - app, bridge, ACP, and data model architecture
- `docs/acp-bridge-contract.md` - Android-to-bridge API contract
- `docs/machine-pairing.md` - Tailscale detection and QR-based machine pairing
- `docs/security-model.md` - permissions, approvals, and threat model
- `docs/harness-engineering.md` - harness layers and enforcement plan

## Current Status

This repository currently contains the initial project harness, design documentation, and a Python bridge MVP under `bridge/`. Android implementation files have not been scaffolded yet.

## Bridge MVP

```powershell
cd bridge
$env:PYTHONPATH="$PWD\src"
python -m android_acp_bridge.main start --allow-non-tailscale
```

The default bridge server uses only the Python standard library. For normal use, run with Tailscale connected so the generated QR code contains a private-network endpoint.
