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

The target interactive transport is a persistent chat-scoped WebSocket. The older one-shot flow (`open socket`, send one request, wait for `bridge.done`, close socket) is transitional and should be replaced by the persistent channel design below.

All chat-channel messages should include:

- `type`
- `id` when request/response correlation is needed
- `chatId` when related to a chat
- `timestamp` for events

Bridge-to-Android chat events must include:

- `eventId`: monotonically increasing integer scoped to `chatId`
- `chatId`
- `type`
- `operationId` when related to a prompt/load/config operation
- `timestamp`

### Attach Chat Channel

Android opens or reconnects a persistent chat WebSocket by sending:

```json
{
  "type": "chat.attach",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "lastEventId": 128
}
```

Bridge behavior:

1. Authenticate the device token from the WebSocket URL.
2. Attach the socket to `chatId`.
3. Ensure the bridge has a `ChatChannel` for the chat.
4. Replay cached events with `eventId > lastEventId`.
5. Send current `chat.status`.

Example response:

```json
{
  "type": "chat.attached",
  "chatId": "chat_123",
  "latestEventId": 135,
  "replayed": 7
}
```

Then:

```json
{
  "type": "chat.status",
  "eventId": 136,
  "chatId": "chat_123",
  "status": "busy",
  "operationId": "op_abc"
}
```

If the requested `lastEventId` is older than the bridge cache window, the bridge returns:

```json
{
  "type": "chat.resyncRequired",
  "chatId": "chat_123",
  "latestEventId": 220,
  "reason": "event log no longer contains requested history"
}
```

Android should then reload the ACP session or ask the user to reopen the chat.

### Event Log and Replay Rules

- `eventId` is scoped to one `chatId`.
- Events are appended before they are sent to connected Android clients.
- Replay must preserve original event order.
- Bridge should keep a ring buffer per chat; the MVP target is at least 500 recent events per chat.
- Approval requests and terminal operation events should remain replayable while they are still actionable.
- Heartbeats are transport keepalive events and do not need to be replayed.

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

Android sends a chat prompt over the attached chat WebSocket:

```json
{
  "type": "chat.prompt",
  "operationId": "op_prompt_001",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "content": "Run the tests"
}
```

The bridge starts or reuses the ACP agent session for `chatId`, creates the session with `workspacePath` as ACP `cwd`, sends `session/prompt`, and streams ACP `session/update` messages back to Android. Immediately after accepting the operation, the bridge appends and sends:

```json
{
  "type": "operation.accepted",
  "eventId": 137,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
  "operationType": "chat.prompt"
}
```

The bridge then sends:

```json
{
  "type": "chat.status",
  "eventId": 138,
  "chatId": "chat_123",
  "status": "busy",
  "operationId": "op_prompt_001"
}
```

ACP tool call events are forwarded in the same shape produced by the ACP agent, with bridge event metadata:

```json
{
  "type": "session/update",
  "eventId": 139,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
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
  "eventId": 140,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
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

When the operation ends:

```json
{
  "type": "operation.done",
  "eventId": 141,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
  "status": "completed"
}
```

```json
{
  "type": "chat.status",
  "eventId": 142,
  "chatId": "chat_123",
  "status": "idle"
}
```

### Transport Keepalive

While a long-running ACP turn is still processing and no ACP update is ready, the bridge may send heartbeat messages. Android must ignore these for chat history and replay purposes:

```json
{
  "type": "bridge.heartbeat"
}
```

The stdlib bridge also handles WebSocket ping frames and responds with pong frames so OkHttp/client keepalive does not terminate the connection.

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
  "operationId": "op_load_001",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "sessionId": "sess_abc"
}
```

The bridge calls ACP `session/load`, forwards replayed `session/update` notifications as event-log entries, then sends `operation.done` and the current `chat.status`.

### Set Config Option

Android can ask the bridge to refresh current ACP session config options before opening built-in pickers such as `model` or `allow-all`:

```json
{
  "type": "session.refreshConfigOptions",
  "operationId": "op_config_001",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link"
}
```

The bridge ensures an ACP session exists for that chat, forwards the latest `config_option_update` messages as event-log entries, then sends `operation.done`.

Android changes session-level options such as model selection through the bridge WebSocket:

```json
{
  "type": "session.setConfigOption",
  "operationId": "op_config_002",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "configId": "model",
  "value": "gpt-5.4"
}
```

The bridge ensures an ACP session exists for that chat, calls ACP `session/set_config_option`, forwards the returned `config_option_update`, then sends `operation.done`.

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
  "eventId": 143,
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
  "operationId": "op_approval_001",
  "approvalId": "approval_456",
  "decision": "approved"
}
```

The bridge waits for this response and then replies to the ACP `session/request_permission` request using the matching allow/reject option.

Approval requests must be written to the chat event log before they are sent to Android. If Android disconnects before seeing the request, `chat.attach` replay must show the pending approval.

## Contract Rules

- Bridge APIs must not expose arbitrary filesystem browsing by default.
- Bridge must validate that requested workspace paths are allowed.
- Bridge must not execute commands after Android disconnects unless the user explicitly allowed background execution.
- Android must not infer `busy` or `idle` from WebSocket connection state. It must use bridge `chat.status`.
- Bridge must keep active chat status independent from WebSocket connectivity.
- Every prompt/load/config operation should have an `operationId`; bridge events for that operation should echo it.
- Bridge must send enough metadata for Android to show a safe approval screen.
- Bridge must redact secrets from logs and approval summaries.
- Bridge must not generate long-lived credentials directly in QR payloads.
- Bridge must require local confirmation before completing first-time pairing.

## Open Decisions

- Decision needed: exact WebSocket envelope format.
- Decision needed: whether the bridge owns chat persistence or Android owns it.
- Decision needed: whether ACP JSON-RPC is tunneled directly or normalized into app-specific events.
