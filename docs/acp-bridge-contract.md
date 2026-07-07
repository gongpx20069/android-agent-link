# ACP Bridge Contract

This document describes the Android-to-bridge contract. It is intentionally separate from standard ACP because machine management and workspace discovery are app-specific concerns.

## Transport

Preferred MVP transport:

- WebSocket for interactive chat, streaming updates, and approvals.
- HTTPS for simple health checks and setup endpoints.

The bridge may connect to the ACP agent through stdio or another local transport.

## Authentication

MVP requirements:

- Every bridge connection must require authentication.
- Tokens must be revocable.
- Tokens must not be stored in source code.
- Android should store tokens using platform secure storage.
- The bridge should reject unauthenticated WebSocket upgrades.

Recommended deployment for personal use:

```text
Android App -> Tailscale/private network -> Bridge
```

Public internet exposure is not the default.

## Startup and Pairing

On startup, the bridge should check whether a Tailscale endpoint is available before showing Android pairing information. Tailscale is the required default MVP transport; non-Tailscale mode is an explicit localhost/manual testing opt-in.

Startup flow:

```text
detect Tailscale
  if missing: attempt automatic install, then show platform guidance if install cannot run
  if not logged in/running: run `tailscale up --qr`
  wait until a Tailscale IP is available
start bridge listener on the Tailscale IP
generate one-time pairing token
show Android pairing QR code
```

See `machine-pairing.md` for the detailed state machine and QR payload.

Pairing payloads may include optional per-machine connection headers for authenticated relay transports. Android stores these headers with the machine and sends them on every bridge request for that machine. The MVP allowlist is limited to `X-Tunnel-Authorization` for Microsoft Dev Tunnels connect-token access.

## Bridge Discovery APIs

### Health

```http
GET /health
```

Response:

```json
{
  "status": "ok",
  "bridgeVersion": "0.1.0"
}
```

### Agents

```http
GET /agents
```

Response:

```json
{
  "agents": [
    {
      "id": "claude-code",
      "displayName": "Claude Code",
      "status": "available"
    },
    {
      "id": "copilot-cli",
      "displayName": "GitHub Copilot CLI",
      "status": "available"
    }
  ]
}
```

### Workspaces

```http
GET /workspaces
```

Response:

```json
{
  "workspaces": []
}
```

The bridge does not bind a workspace at startup. The Android app selects a workspace per chat. This endpoint is reserved for optional recent/allowed workspace discovery.

### Pairing Redeem

```http
POST /pairing/redeem
```

Request:

```json
{
  "pairingId": "pair_01HZZ...",
  "pairingToken": "short-lived-one-time-token",
  "device": {
    "name": "Pixel",
    "platform": "android",
    "appVersion": "0.1.0"
  }
}
```

Response after local approval on the developer machine:

```json
{
  "machineId": "machine_abc",
  "deviceToken": "revocable-device-token",
  "bridgeFingerprint": "sha256:..."
}
```

The bridge must reject expired, reused, or unconfirmed pairing tokens.

## Interactive WebSocket Messages

All messages should include:

- `type`
- `id` when request/response correlation is needed
- `chatId` when related to a chat
- `timestamp` for events

### Start Chat

Android sends:

```json
{
  "type": "chat.start",
  "id": "req_001",
  "chatId": "chat_123",
  "agentId": "claude-code",
  "workspacePath": "D:\\repos\\android-agent-link",
  "permissionMode": "manual_approval"
}
```

Bridge behavior:

- Spawn or attach to the requested agent.
- Initialize ACP.
- Create or load ACP session using `workspacePath` as `cwd`.
- Return chat/session state.

### Send Prompt

Android sends a chat prompt through the bridge WebSocket:

```json
{
  "type": "chat.prompt",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "content": "Run the tests"
}
```

The bridge starts or reuses the ACP agent session for `chatId`, creates the session with `workspacePath` as ACP `cwd`, sends `session/prompt`, and streams ACP `session/update` messages back to Android. Tool call events are forwarded in the same shape produced by the ACP agent:

```json
{
  "type": "session/update",
  "chatId": "chat_123",
  "update": {
    "sessionUpdate": "tool_call",
    "toolCallId": "tool_abc",
    "title": "Reading files",
    "kind": "read",
    "status": "pending"
  }
}
```

```json
{
  "type": "session/update",
  "chatId": "chat_123",
  "update": {
    "sessionUpdate": "tool_call_update",
    "toolCallId": "tool_abc",
    "status": "completed",
    "content": {
      "result": "Tool result content from the ACP agent."
    }
  }
}
```

```json
{
  "type": "bridge.done",
  "chatId": "chat_123"
}
```

### List Sessions

Android can request resumable ACP sessions for a workspace:

```json
{
  "type": "session.list",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link"
}
```

Bridge response:

```json
{
  "type": "session.list.result",
  "sessions": [
    {
      "sessionId": "sess_abc",
      "cwd": "D:\\repos\\android-agent-link",
      "title": "Previous work",
      "updatedAt": "2026-07-07T00:00:00Z"
    }
  ]
}
```

### Load Session

Android loads a selected session into the current chat:

```json
{
  "type": "session.load",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "sessionId": "sess_abc"
}
```

The bridge calls ACP `session/load`, forwards replayed `session/update` notifications, then sends `bridge.done`.

### Set Config Option

Android can ask the bridge to refresh current ACP session config options before opening built-in pickers such as `model` or `allow-all`:

```json
{
  "type": "session.refreshConfigOptions",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link"
}
```

The bridge ensures an ACP session exists for that chat, forwards the latest `config_option_update` messages, then sends `bridge.done`.

Android changes session-level options such as model selection through the bridge WebSocket:

```json
{
  "type": "session.setConfigOption",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "configId": "model",
  "value": "gpt-5.4"
}
```

The bridge ensures an ACP session exists for that chat, calls ACP `session/set_config_option`, and forwards the returned `config_option_update`.

### Agent Update

Bridge sends:

```json
{
  "type": "chat.update",
  "chatId": "chat_123",
  "update": {
    "kind": "agent_message_chunk",
    "text": "I will inspect the Gradle files first."
  }
}
```

### Approval Request

Bridge sends this when an ACP agent calls `session/request_permission`:

```json
{
  "type": "approval.requested",
  "approvalId": "approval_456",
  "chatId": "chat_123",
  "action": "run_command",
  "summary": "Run Gradle tests",
  "details": {
    "toolCallId": "tool_abc",
    "title": "Run Gradle tests",
    "kind": "execute"
  },
  "options": [
    {
      "optionId": "allow-once",
      "name": "Allow once",
      "kind": "allow_once"
    },
    {
      "optionId": "reject-once",
      "name": "Reject",
      "kind": "reject_once"
    }
  ]
}
```

Android responds:

```json
{
  "type": "approval.decide",
  "approvalId": "approval_456",
  "decision": "approved"
}
```

The bridge waits for this response and then replies to the ACP `session/request_permission` request using the matching allow/reject option.

## Contract Rules

- Bridge APIs must not expose arbitrary filesystem browsing by default.
- Bridge must validate that requested workspace paths are allowed.
- Bridge must not execute commands after Android disconnects unless the user explicitly allowed background execution.
- Bridge must send enough metadata for Android to show a safe approval screen.
- Bridge must redact secrets from logs and approval summaries.
- Bridge must not generate long-lived credentials directly in QR payloads.
- Bridge must require local confirmation before completing first-time pairing.

## Open Decisions

- Decision needed: exact WebSocket envelope format.
- Decision needed: whether the bridge owns chat persistence or Android owns it.
- Decision needed: whether ACP JSON-RPC is tunneled directly or normalized into app-specific events.
