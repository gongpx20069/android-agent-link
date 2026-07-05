# Architecture

## System Overview

```text
Android App
  WebSocket / HTTPS
Remote ACP Bridge
  stdio / process control
ACP Agent CLI
  repository workspace
```

The Android app is the control surface. Remote machines run the bridge, agent process, shell commands, Git operations, and repository workspaces.

## Why a Bridge Exists

Many coding agents communicate over stdio and assume a desktop/server environment. Android should not directly manage those agent processes. The bridge provides:

- Remote transport for Android.
- Agent process lifecycle management.
- Tailscale/private-network detection and machine pairing.
- Workspace discovery.
- Permission and approval mediation.
- Session persistence and reconnection.
- Logs and diagnostics.

## Bridge Implementation

The MVP bridge is implemented as a Python subproject under `bridge/`.

Initial choices:

- Python 3.11+
- Python standard library HTTP/WebSocket server as the default runtime path
- Tailscale CLI integration through subprocess calls
- Optional FastAPI/Pydantic/uvicorn backend for future richer transport work
- Optional `qrcode` dependency for terminal QR rendering

The Python bridge is optimized for MVP speed and cross-platform development. The default path avoids external runtime dependencies so a developer can run from source with `PYTHONPATH=bridge/src`. A later release may package it as a standalone executable so developer machines do not need to install Python manually.

## App Modules

Planned Android modules:

- `app`: Android entry point and navigation.
- `core:model`: shared domain models such as Chat, Machine, Workspace, Approval.
- `core:acp`: ACP JSON-RPC models and protocol handling.
- `core:bridge`: bridge API client and connection manager.
- `core:security`: permission policy and approval state.
- `feature:chats`: chats list and chat detail.
- `feature:machines`: machine management.
- `feature:approvals`: approval center.
- `feature:diffs`: diff viewer.
- `feature:logs`: logs and command output.

Decision needed: confirm module boundaries when Android scaffolding begins.

## Domain Model

### Machine

Represents a remote environment that can run the bridge.

Important fields:

- `id`
- `displayName`
- `endpoint`
- `authState`
- `connectionState`
- `pairingState`
- `bridgeVersion`
- `supportedAgents`

### Workspace

Represents a project directory on a machine.

Important fields:

- `id`
- `machineId`
- `displayName`
- `absolutePath`
- `isFavorite`
- `lastUsedAt`

In ACP, the selected workspace maps to:

```json
{
  "method": "session/new",
  "params": {
    "cwd": "/absolute/path/to/workspace",
    "mcpServers": []
  }
}
```

### Chat

Represents the user-facing task/thread.

Important fields:

- `id`
- `title`
- `machineId`
- `workspaceId`
- `agentId`
- `acpSessionId`
- `status`
- `permissionMode`
- `createdAt`
- `updatedAt`

### Approval

Represents a user decision requested by an agent or bridge.

Important fields:

- `id`
- `chatId`
- `machineId`
- `workspaceId`
- `type`
- `riskLevel`
- `summary`
- `details`
- `status`
- `createdAt`
- `decidedAt`

## Connection Model

The app should maintain one logical connection per active machine:

```text
Machine A -> bridge WebSocket A
Machine B -> bridge WebSocket B
Machine C -> bridge WebSocket C
```

Connections should reconnect independently. A failure on one machine must not affect chats on another machine.

## ACP Boundary

ACP is used for agent lifecycle and conversation messages:

- `initialize`
- `session/new`
- `session/load`
- `session/resume`
- `session/prompt`
- `session/update`
- `session/cancel`
- `session/close` when supported

App/bridge management is outside core ACP:

- machine registration
- QR-based machine pairing
- Tailscale/private-network endpoint discovery
- workspace discovery
- agent discovery
- bridge health
- approval routing
- audit events

These bridge APIs must be documented in `acp-bridge-contract.md`.

## State Principles

- Use explicit state machines for connection and prompt lifecycle.
- Persist enough state to recover the UI after app restart.
- Treat bridge state as remote truth for active agent execution.
- Treat local app state as cached control-plane state.
- Never hide machine/workspace context in a chat view.
