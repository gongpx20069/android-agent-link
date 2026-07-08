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
- Required `qrcode` dependency for compact CLI QR rendering

The Python bridge is optimized for MVP speed and cross-platform development. The default source-checkout path is `python bridge\run.py`, which creates a local virtual environment, installs `bridge\requirements.txt`, and runs the package entry point without requiring users to set `PYTHONPATH`. Third-party bridge dependencies must be declared in `pyproject.toml` and exposed through requirements files for pip, uv, and conda installation. A later release may package it as a standalone executable so developer machines do not need to install Python manually.

## App Modules

Planned Android modules:

- `app`: Android entry point and navigation.
- CameraX and ML Kit Barcode Scanning in `app` for QR-based machine pairing.
- `core:model`: shared domain models such as Chat, Machine, Workspace, Approval.
- `core:acp`: ACP JSON-RPC models and protocol handling.
- `core:bridge`: bridge API client and connection manager.
- `core:security`: permission policy and approval state.
- `feature:chats`: chats list and chat detail.
- `feature:machines`: machine management.
- `feature:approvals`: approval center.
- `feature:diffs`: diff viewer.
- `feature:logs`: logs and command output.

The current Android skeleton starts as a single `app` module. The module boundaries above are the target split once the pairing and machine-management flow stabilizes.

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

The app uses machine-level HTTP calls for setup and discovery, and chat-scoped WebSocket channels for interactive agent work.

Machine setup/discovery remains machine-scoped:

```text
Android -> Machine A bridge HTTP: pairing, health, agents, workspaces
Android -> Machine B bridge HTTP: pairing, health, agents, workspaces
```

Interactive work is chat-scoped. The target architecture is one persistent logical WebSocket channel per active chat:

```text
Chat 1 -> Machine A bridge WebSocket -> Bridge ChatChannel(chat_1) -> ACP Agent Session
Chat 2 -> Machine A bridge WebSocket -> Bridge ChatChannel(chat_2) -> ACP Agent Session
Chat 3 -> Machine B bridge WebSocket -> Bridge ChatChannel(chat_3) -> ACP Agent Session
```

Each chat channel reconnects independently. A failure on one chat must not affect other chats on the same machine or chats on other machines.

The previous one-shot request WebSocket model (`open WS -> send one prompt -> wait for bridge.done -> close`) is transitional only. It is not reliable enough for long-running agent turns, approvals, mobile network transitions, or replay after disconnect.

## Persistent Chat Channel Target Design

Each active chat has a bridge-side `ChatChannel` with:

- `chatId`
- `machineId`
- `agentId`
- `workspacePath`
- `acpSessionId`
- current status: `idle`, `busy`, `waitingApproval`, `disconnected`, or `failed`
- active `operationId`, if any
- recent event log / ring buffer
- pending approval references

Android has a matching `ChatConnection` with:

- WebSocket state: connecting, connected, reconnecting, disconnected
- last received `eventId`
- local cached timeline
- local input/composer state

The bridge is authoritative for active execution status. Android may cache status for UI, but it must converge back to bridge state after reconnect.

### Event Replay

Bridge emits every chat-visible event with a monotonically increasing `eventId` scoped to `chatId`.

On reconnect, Android sends `lastEventId`:

```json
{
  "type": "chat.attach",
  "chatId": "chat_123",
  "lastEventId": 128
}
```

The bridge replays cached events with `eventId > lastEventId`, then sends the current `chat.status`. This prevents lost tool updates, approval requests, and `done` events across mobile network interruptions.

If Android's `lastEventId` is older than the bridge cache window, the bridge returns a resync-required event so Android can call `session/load` or ask the user to reopen the session.

### Operation Lifecycle

Prompt, session load, model changes, and approval decisions are operations inside the chat channel. Each operation has an `operationId`.

```text
Android -> chat.prompt(operationId)
Bridge  -> operation.accepted(operationId)
Bridge  -> chat.status(busy)
Bridge  -> session/update(eventId, operationId)
Bridge  -> operation.done(operationId)
Bridge  -> chat.status(idle)
```

Android should not infer chat busy/idle from WebSocket open/closed state. WebSocket connectivity and agent execution status are separate state machines.

### Implementation Phases

1. **Current transitional model**: one-shot WebSocket requests with `bridge.accepted`, heartbeat, and ping/pong keepalive. This is only a mitigation for idle disconnects.
2. **Persistent channel MVP**: Android opens `chat.attach` for the active chat, bridge maintains a `ChatChannel`, event IDs, replay buffer, and bridge-authoritative `chat.status`.
3. **Multi-chat resilience**: Android can keep multiple chat channels attached, reconnect each independently, and replay missed approvals/tool updates.
4. **Durable bridge state**: bridge persists event logs and pending approvals across bridge restarts where feasible.

The persistent channel MVP is the next architectural milestone. New WebSocket work should move toward this model instead of adding more behavior to the one-shot request flow.

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
