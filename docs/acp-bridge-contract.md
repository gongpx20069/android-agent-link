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

The default startup transport is an authenticated Microsoft Dev Tunnel so Android does not need a companion private-network app. Anonymous Dev Tunnel access is prohibited. Tailscale remains an explicit private-network option and requires Tailscale on both Android and the developer machine.

Startup flow:

```text
select transport (default: devtunnel)
  devtunnel:
    authenticate developer-machine CLI
    create/reuse private tunnel and short-lived connect token
    start bridge listener on localhost
  tailscale:
    detect/install Tailscale on the developer machine
    run `tailscale up --qr` if login is required
    wait for a Tailscale IP and bind the listener to it
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
  "sessionId": "sess_abc",
  "sessionResumable": true,
  "lastEventGeneration": "4bd67d8e...",
  "lastEventId": 128
}
```

Bridge behavior:

1. Authenticate the device token from the WebSocket URL.
2. Attach the socket to `chatId`.
3. Ensure the bridge has a `ChatChannel` for the chat.
4. Replay cached events with `eventId > lastEventId`.
5. Send current `chat.status` with `snapshot=true`. Replayed status events do not carry this flag, so Android must wait for the snapshot before considering startup synchronization complete.

`chat.attached` includes the current `eventGeneration`. If it differs from `lastEventGeneration`, or if `lastEventId` is greater than the Bridge's current event counter, the Bridge returns `checkpointReset=true`, replays the available generation from event `1`, and Android resets its durable event checkpoint before applying that replay.

`sessionId` and `sessionResumable` are optional for a new Chat and required once Android has received a `chat.session` binding. They allow a restarted Bridge to restore the same ACP conversation.

For `chat.prompt`, the bridge must send `operation.accepted` before execution or queueing. When execution begins it sends `operation.started` for every member of the active batch before any streamed ACP `session/update`. Streamed events for the combined ACP turn must be sent exactly once, followed by one `operation.done` per batch member. The bridge sends `chat.status=idle` only after the per-chat prompt queue drains.

Example response:

```json
{
  "type": "chat.attached",
  "chatId": "chat_123",
  "eventGeneration": "4bd67d8e...",
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
  "operationId": "op_abc",
  "snapshot": true
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
While recovery is pending, Android must not acknowledge replay checkpoints or show replayed completion notifications. It commits the new checkpoint only after both recent-session recovery and the final attach status snapshot succeed.

### Event Log and Replay Rules

- `eventId` is scoped to one `chatId`.
- Events are appended before they are sent to connected Android clients.
- Replay must preserve original event order.
- Bridge should keep a ring buffer per chat; the MVP target is at least 500 recent events per chat.
- Approval requests and terminal operation events should remain replayable while they are still actionable.
- Heartbeats are transport keepalive events and do not need to be replayed.

### Bind ACP Session

After ACP `session/new` or `session/load`, the Bridge emits:

```json
{
  "type": "chat.session",
  "eventId": 139,
  "chatId": "chat_123",
  "sessionId": "sess_abc",
  "resumable": false
}
```

`resumable=false` means the session may still be a config-only empty shell. After the first successful prompt, or immediately after loading an existing session, the Bridge emits the binding with `resumable=true`.

On a Bridge restart, requests carrying a session binding must try ACP `session/load` before creating a new session. If loading a non-resumable empty shell fails, the Bridge may create a replacement and includes `replacedSessionId` in `chat.session`. If a resumable session cannot be loaded, the operation fails; the Bridge must not silently replace conversation history. A live Bridge session always wins over a stale binding on an already queued prompt.

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

### Load Recent Session History

Android uses this when opening an existing ACP session or using the built-in resume picker:

```json
{
  "type": "session.loadRecent",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "sessionId": "sess_abc",
  "limit": 50
}
```

Bridge behavior:

- Start the ACP agent and call ACP `session/load`.
- Drain replayed ACP `session/update` history internally until replay becomes idle, or until the bridge hits its safety timeout/event cap.
- Do not stream old replay events to Android as chat timeline events.
- Convert `user_message_chunk` and `agent_message_chunk` updates into visible message bubbles.
- Return only the latest `limit` visible user/agent messages.
- Keep the loaded ACP session attached to `chatId` for future prompts.

Response:

```json
{
  "type": "session.loadRecent.result",
  "chatId": "chat_123",
  "sessionId": "sess_abc",
  "messages": [
    { "role": "user", "text": "What changed?" },
    { "role": "agent", "text": "I updated the bridge startup docs." }
  ],
  "scannedEvents": 842,
  "truncated": false
}
```

The Bridge drains and retains complete replay up to its safety event cap, then applies the requested visible-message limit after user/agent chunks have been merged into messages. If replay does not become idle before the safety deadline or event cap, loading fails and the ACP process is closed so partial history cannot be returned or leak into the next prompt.

### Send Prompt

Android sends a chat prompt over the attached chat WebSocket:

```json
{
  "type": "chat.prompt",
  "operationId": "op_prompt_001",
  "chatId": "chat_123",
  "agentId": "copilot-cli",
  "workspacePath": "D:\\repos\\android-agent-link",
  "sessionId": "sess_abc",
  "sessionResumable": true,
  "content": "Run the tests"
}
```

The bridge starts or reuses the ACP agent session for `chatId`, creates the session with `workspacePath` as ACP `cwd`, and serializes prompt turns through a per-chat FIFO. Different chats may execute concurrently, but one chat never has overlapping ACP `session/prompt` requests. At a turn boundary, the bridge atomically drains all operations already waiting, joins their content in FIFO order with two newline characters, and sends one ACP `session/prompt`. Operations accepted while that combined turn runs remain queued for the next batch. Immediately after accepting an operation, the bridge appends and sends:

```json
{
  "type": "operation.accepted",
  "eventId": 137,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
  "operationType": "chat.prompt",
  "state": "starting",
  "queuePosition": 0,
  "content": "Run the tests"
}
```

If another prompt is already active, `state` is `queued` and `queuePosition` is one-based. When a batch becomes active, the bridge sends one event for every member:

```json
{
  "type": "operation.started",
  "eventId": 138,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
  "operationType": "chat.prompt",
  "content": "Run the tests",
  "batchSize": 2
}
```

Android uses these events to move every original user message into the timeline. ACP updates for the combined turn carry the first batch member's `operationId`. The bridge keeps the chat busy and sends:

```json
{
  "type": "chat.status",
  "eventId": 139,
  "chatId": "chat_123",
  "status": "busy",
  "operationId": "op_prompt_001"
}
```

ACP tool call events are forwarded in the same shape produced by the ACP agent, with bridge event metadata:

```json
{
  "type": "session/update",
  "eventId": 140,
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
  "eventId": 141,
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
  "eventId": 142,
  "chatId": "chat_123",
  "operationId": "op_prompt_001",
  "status": "completed",
  "queueRemaining": 0,
  "batchSize": 2
}
```

Every batch member receives its own terminal event. `queueRemaining` counts member operations that have not yet received a terminal event, including operations already assigned to the next batch. If it is greater than zero, processing continues without an intermediate idle state. A queued prompt may be removed before the bridge drains it into a batch:

```json
{
  "type": "chat.prompt.remove",
  "chatId": "chat_123",
  "operationId": "op_prompt_002"
}
```

The removed operation receives `operation.done` with `status=cancelled`. Cancellation is idempotent. If the operation has not arrived yet, the Bridge records a cancelled tombstone, retains it until a matching prompt arrives, and then acknowledges that prompt as `state=cancelled` without executing it. If it has already been drained into an active batch, the bridge returns `status=already_started` and does not cancel the combined ACP turn. Android immediately hides the pending item but persists and retries its removal tombstone until replayable lifecycle events authoritatively reconcile it.

```json
{
  "type": "chat.status",
  "eventId": 143,
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

### Load Session (legacy/debug)

The older `session.load` request loads a selected session and streams ACP replay updates directly to Android:

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

This is retained for debugging and low-level compatibility. Normal existing-session UX should use `session.loadRecent` instead so old replay does not stream into the visible timeline; the bridge scans ACP replay internally and returns only the latest visible message snapshot.

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

The bridge ensures an ACP session exists for that chat, caches `configOptions` returned by ACP `session/new` or `session/load`, forwards them as a `config_option_update` event-log entry, then sends `operation.done`.

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
