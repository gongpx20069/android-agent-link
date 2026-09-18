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

### Shared controller model

Mochi is an optional primary controller, AgentLink Android is the credential owner
and trusted authorization/approval/detail surface, and the terminal is another
human controller. They address the same bridge-owned workspace/chat/session/task
IDs, not shadow conversations. An explicit same-device Android Activity grants
scoped access to a Messenger service; credentials never enter Mochi tool results.

`shared_state.py` uses standard-library SQLite for the private catalog, bounded
event journal and durable task deduplication. `control.py` provides authenticated
grouped control operations. Existing attach/prompt paths register the original IDs;
all prompt execution continues through the existing runtime queue and ACP manager.
Event fan-out is connection-specific multicast and disconnect removes only that
connection. Human revisions protect Mochi continuations, independent of event
generation resets. Restart ambiguity is represented as interrupted, not success.

The state is authoritative for accepted messages, confirmed configuration, task
status and event cursors. Pending approval decisions remain in the existing runtime
approval state machine. Drafts, scroll and folding stay client-local. Read responses
declare observation time, online state, journal truncation and page continuation;
an offline client cannot claim current remote status.

## Why a Bridge Exists

Many coding agents communicate over stdio and assume a desktop/server environment. Android should not directly manage those agent processes. The bridge provides:

- Remote transport for Android.
- Agent process lifecycle management.
- Authenticated relay or private-network transport and machine pairing.
- Workspace discovery.
- Permission and approval mediation.
- Session persistence and reconnection.
- Logs and diagnostics.

## Bridge Implementation

The MVP bridge is implemented as a Python subproject under `bridge/`.

Initial choices:

- Python 3.11+
- Python standard library HTTP/WebSocket server as the default runtime path
- Authenticated Microsoft Dev Tunnels as the default remote transport
- Tailscale CLI integration through subprocess calls
- Optional FastAPI/Pydantic/uvicorn backend for future richer transport work
- Required `qrcode` dependency for compact CLI QR rendering

The Python bridge is optimized for MVP speed and cross-platform development. Users explicitly install it with Conda, uv, or Python venv/pip before startup; the bridge never creates a Python environment or installs packages at startup. The `python bridge\run.py` source helper uses the active Python environment and forwards to the package CLI without modifying that environment. Third-party bridge dependencies must be declared in `pyproject.toml` and exposed through requirements files for pip, uv, and conda installation. A later release may package it as a standalone executable so developer machines do not need to install Python manually.

## App Modules

### Android streaming and persistence

The current single app module uses a shared, ordered `ChatStore` rather than
serializing the lifetime chat collection for each token. `ChatDatabase` stores
encrypted message/approval records separately from chat metadata. The single IO
writer batches changed rows, commits cursor/effects together, and supplies a
durability barrier before network sends. Activity/service socket ownership remains
explicit, but both owners use the same cache, write queue and failure state.

The socket reader parses events and reduces tool JSON off main. `ChatEventPump`
combines only adjacent compatible text/thought deltas or full tool snapshots; it
keeps controls ordered, bounds queued work, and delivers small main-thread batches.
The UI still owns its Compose state and applies the resulting callbacks on main;
this is not a wholesale migration of UI business logic to a worker.

Completed history is paged from disk; the live working set is a recent window plus
unfinished-turn rows. Markdown parsing uses background conflated snapshots and
bounded display pages. See `android-app.md` for thresholds and exceptions.

Robolectric and OkHttp MockWebServer are test-only dependencies, added to exercise
real SQLite transactions, legacy migration, large encrypted records, rollback,
bounded history growth, and WebSocket durability ordering on hosts without a device.
These tests do not establish phone frame-time or heap/GC performance.

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
- `acpSessionResumable`
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
- one active prompt operation and a FIFO of queued prompt operations

Android has a matching `ChatConnection` with:

- WebSocket state: connecting, connected, reconnecting, disconnected
- last received `eventId`
- local cached timeline
- local input/composer state
- the persisted ACP session ID and whether at least one prompt has made it resumable

The bridge is authoritative for active execution status. Android may cache status for UI, but it must converge back to bridge state after reconnect.

### Event Replay

Bridge emits every chat-visible event with a monotonically increasing `eventId` scoped to `chatId`.

On reconnect, Android sends `lastEventId`:

```json
{
  "type": "chat.attach",
  "chatId": "chat_123",
  "sessionId": "sess_abc",
  "sessionResumable": true,
  "lastEventId": 128
}
```

The bridge replays cached events with `eventId > lastEventId`, then sends the current `chat.status`. This prevents lost tool updates, approval requests, and `done` events across mobile network interruptions.

### ACP Session Binding

The Bridge emits a replayable `chat.session` event whenever a Chat creates, restores, or replaces its ACP session. Android immediately stores `sessionId` and `resumable` in encrypted Chat storage and includes them on future attach, prompt, and config requests.

An ACP session created only to read config options may be an empty shell that the agent cannot list or load yet. Its binding has `resumable=false`. After the first successful `session/prompt`, the Bridge emits the same binding with `resumable=true`.

After a Bridge restart, the first request for a bound Chat restores the supplied session ID before doing more work. If a non-resumable empty shell no longer exists, the Bridge may create a replacement and reports the old ID in `replacedSessionId`. A missing resumable session is an error and must never be silently replaced. Once a live session exists for a Chat, it remains authoritative over stale session IDs captured by already queued prompt requests.

Session lifecycle changes are serialized per Chat, so attach recovery, recent-history loading, config changes, and prompts cannot stop or replace one another's ACP process. Attach reads an existing live binding without waiting for the active prompt, preserving reconnect and event replay while work is running. Different Chats use different lifecycle locks and remain concurrent.

If Android's `lastEventId` is older than the bridge cache window, the bridge returns a resync-required event so Android can call `session.loadRecent` or ask the user to reopen the session.

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

### Queued Prompt Operations

ACP prompt turns are serial within one session. AgentLink therefore queues every `chat.prompt` at the bridge instead of issuing overlapping `session/prompt` requests. A per-chat worker runs one prompt at a time; workers for different chats remain independent. At each turn boundary, the worker atomically drains every prompt already waiting, preserves their FIFO order, joins their text with blank lines, and sends the result as one ACP prompt. Prompts accepted after that drain wait for the following batch.

```text
Android -> chat.prompt(op_1)
Bridge  -> operation.accepted(op_1, state=starting)
Bridge  -> operation.started(op_1)
Android -> chat.prompt(op_2)
Bridge  -> operation.accepted(op_2, state=queued, queuePosition=1)
Android -> chat.prompt(op_3)
Bridge  -> operation.accepted(op_3, state=queued, queuePosition=2)
Bridge  -> operation.done(op_1, queueRemaining=2)
Bridge  -> operation.started(op_2, batchSize=2)
Bridge  -> operation.started(op_3, batchSize=2)
Bridge  -> ACP session/prompt("op_2 content\n\nop_3 content")
Bridge  -> operation.done(op_2, queueRemaining=1, batchSize=2)
Bridge  -> operation.done(op_3, queueRemaining=0, batchSize=2)
Bridge  -> chat.status(idle)
```

Each batch member keeps its own operation ID and receives individual `operation.started` and `operation.done` events so Android can move each user message into the timeline and reconcile replay idempotently. ACP updates for the combined turn use the first member's operation ID. The chat stays `busy` between batches and becomes `idle` only after the queue drains. Prompt operation IDs are idempotency keys. Android persists a removal tombstone and retries it on the ordered chat channel; reconnect sends cancellation-only tombstones before normal queued prompts and never resends removed content. The Bridge also records cancellation that arrives before its prompt, preventing a late frame with that operation ID from executing. A queued prompt can be removed before the batch snapshot; a member already drained into an active batch is not silently cancelled.

The WebSocket reader and writer run independently so the bridge can accept queue and approval messages while an ACP prompt request is still running. Chat events are serialized through the connection writer and remain replayable by `eventId`.

### Optional local terminal client

`start --interactive` adds a `TerminalClient` alongside the standard-library
server. `prompt-toolkit` is an explicit optional dependency (`interactive` extra):
its full-screen `Application` owns one input buffer and a separately focusable
conversation control. It is not a PTY wrapper around an agent CLI.
The ACP process retains its dedicated protocol stdin/stdout.

`FullScreenTerminal` renders a header, scrollable conversation, status area and
compact input. A 100 ms event drain and diff-based redraw preserve drafts/focus.
Tab switches focus; Enter toggles a tool/group; arrows and page keys navigate.
Mouse clicks also toggle. Browsing freezes following and End resumes it.
`DraftControl` explicitly focuses on click and resumes following even if input was
already focused during a mouse scroll. Printable keys/bracketed paste in conversation
focus transfer to the input buffer without submission. Returning to input or
submitting resumes following; switching focus into conversation alone does not pause
it. A pre-render focus transition check also covers returning from config/pairing.
Lines
are wrapped by cell width so long unbroken output remains keyboard-accessible.
Operation acceptance supplies the single user-message echo.

`Transcript` retains at most 160 entries and roughly 2 Mi characters across chats,
128 Ki characters per text entry, and 64 tools per task group. Tool projections
have a 32 Ki-character traversal budget and bounded nesting. Partial fields
preserve prior values; supplied content arrays replace rather than append.
Tool identity includes chat and operation. Expansion/page state survives updates;
completed tool groups remain in the bounded view and failed titles stay visible
even while collapsed. Overflow/eviction is explicit and affects no Android events.

Agent replies use the existing safe Rich/markdown-it renderer. Each changed small
message is rendered in place, including unfinished syntax, not appended a second
time on completion. Cached rendered rows are bounded by retained entry count and
invalidated by revision/width. Replies over 8 Ki characters use explicit source
pages; tools use literal JSON detail pages. This avoids arbitrarily expensive
Markdown/layout work while keeping bounded retained content accessible.

Explicit `/copy` and conversation Ctrl+Y snapshot retained source and write it
to a local clipboard utility in a worker thread. No remote OSC52 or shell
interpolation is used; contents travel via stdin, with bounded timeouts and
explicit errors. `/mouse` dynamically disables app mouse reporting for native
terminal selection. Android wraps each message body in SelectionContainer and
provides copy actions for source text/code/detail sections. Clipboard source is
not reconstructed from visible pages. A 128 Ki UTF-16 limit rejects oversized
Android writes without replacing the clipboard; there is no extra transcript
cache or continuous clipboard work on streaming updates.

Only agent replies are formatted: prompts, labels, tool summaries and approval
details remain literal. Remote control sequences are removed before parsing;
only locally generated styling is decoded into prompt-toolkit formatted fragments.
Hyperlinks are displayed as text, images as placeholders; no remote resource is
fetched and no code executes. Width follows the terminal and `NO_COLOR` disables
color. Table columns use folding rather than ellipsis overflow. Layouts whose
nesting or column count cannot fit the terminal display original text with an
explicit notice, rather than silently losing content. Raw HTML is not supported.
Python-side runtime/tunnel output is captured into bounded literal notices while
the alternate screen is active. `/qrcode` (alias `/pairing`) exposes the original startup QR/link
in a separate scrollable view, not in the retained transcript.
Interactive startup stores this display without printing it; other server modes
retain the startup QR/link output.

The runtime's optional `LocalClient` observer receives request metadata and each
new sequenced event before fan-out. It does not register as the Android chat
emitter, so phone attach/reconnect cannot evict it and it cannot evict the phone.
Terminal prompts use `websocket_responses` with a unique operation ID and the
selected chat's agent, workspace and latest session binding. Existing queue,
batching, approval and replay behavior is reused without a new wire protocol.
Terminal-created chats are local metadata only, not new Android chat cards.

Each observed chat has a stable per-run number. Optional `chat.attach.chatTitle`
metadata labels the terminal with the phone's title; older clients fall back to
workspace/agent plus a bounded first observed prompt excerpt. Labels are plain,
single-line text and never routing keys. `/chats` takes a snapshot of numbered
choices; input outside the snapshot is rejected rather than sent as a prompt.
The sole known chat is selected automatically only while input is empty and no
selection exists. Additional attaches and background reconnects never change it:
Android startup/monitor attachments do not identify foreground focus. Discovery
only queues metadata; selection and notices happen in the terminal render loop.

The observer enqueues only bounded display projections and updates session/status
metadata under a local lock; it does not perform input or terminal writes under
the runtime event lock. Rendering consumes reply fragments every 100ms. Queue
overflow is explicitly reported and affects only terminal display. The terminal
owns no full history; selected-chat output is live-only. `/approvals` queries the
runtime's authoritative, unexpired pending requests rather than relying on the
display queue. Local input releases its lock before calling the runtime.

Slash completion combines reserved AgentLink commands with advertised agent
commands. `/model` runs configuration requests in worker threads, shows actual
agent-provided choices, and verifies the confirmed value. Chat/session selection
is pinned to the request. The runtime reserves idle chats across config changes,
rejecting prompts/history/config races. Session mismatches are checked under the
agent's chat lock. Configuration responses are sequenced/broadcast to the phone
and observer without another attach. `/resume` remains an explicit Android action.
`/allow-all` uses the same `ConfigPicker` and async config request path, matching
Android's normalized permission option names. Boolean options become string-valued
Off/On choices; select options keep advertised values. A separate confirmation
state requires `y` after choice selection, displays the shared chat/current/target
values and risk, and never resolves pending approvals. Boolean confirmation accepts
the agent's boolean or equivalent string result; success still requires the
requested value. The status area displays the latest permission configuration.

Interactive pairing uses a bounded confirmation broker with the same runtime
console lock. `/pair y|n` answers a displayed request; there is no competing
`input()`. Exit or timeout denies approval. A single asyncio input/render loop
runs on the main thread; the stdlib listener runs in a joined background thread.
Shutdown closes terminal state, denies pairing and stops the HTTP server; already
started config workers finish within the agent's request timeout.

### Runtime log summaries

`ConsoleLog` consumes new business events at `_append_event`, before transport
fan-out. It never changes the wire event or its replay record. Transport callbacks
do not log each delivery, and chat attach logs one replay-count summary. Unsequenced
one-shot responses are observed separately; legacy history loads summarize the
response rather than replaying tool logs.

Metadata-only summaries are keyed by `(chatId, operationId)` with sparse tool-state
tracking by `toolCallId`. Completed/failed/cancelled operations release their state.
The logger stores counters, timing, identifiers and statuses, never message bodies.
State is capped at 256 operations and 512 tool/approval entries per operation;
capacity loss produces a warning rather than unbounded growth.

The CLI owns a single progress worker, started with the server and joined on exit.
Using a monotonic clock, it reports each active operation at most once per 15
seconds, including quiet periods and approval waits. Concurrent console writes
are serialized. Pairing input defers routine output into a counter, not a backlog;
warnings and errors can still interrupt the prompt. Dev Tunnels subprocess output
is outside this gate. Log-level filtering never changes Android event delivery.

### Recovery and ownership

Persistent chat channels are implemented. One-shot requests remain for bounded
discovery, history and configuration operations.

Android distinguishes transport connectivity, last synchronization time and remote
agent status. A failed connection does not imply an idle agent. Authentication
failures require explicit retry/re-pairing rather than endlessly retrying unchanged
credentials. Bridge device credentials survive restart. For account-paired
machines, a shared account service obtains fresh connect tokens on network workers;
legacy QR headers are not automatically renewed.

`TunnelAccounts` implements the two providers' device authorization flows and
the documented Dev Tunnels REST contracts using the existing OkHttp dependency.
It owns encrypted identity credentials, bounded pagination, account-bound relay
caching, and the asynchronous locally confirmed pairing client. The UI and
background service inject the same relay-header resolver into `BridgeClient`, so
HTTP and WebSocket handshakes use consistent credentials without blocking the UI.
Device authorization avoids an embedded browser, callback URI or client secret;
it requires copying a short code into the provider's external browser.

The bridge advertises labelled tunnel ports and serves bounded account-pairing
requests separately from QR token redemption. A console-confirmation lock prevents
overlapping prompts. Discovery through Microsoft management infrastructure needs
no AgentLink cloud server, but still depends on supported provider authorization.
Microsoft requests the explicit delegated resource scope `/all` on initial login
and refresh, rather than `.default`. A live personal-account test using AgentLink's
own client ID confirmed authorization, refresh-token issuance and HTTP 200 from
the tunnel-list API after the old request failed with a browser "code expired"
message. Login is restored with the corrected scope. Actual refresh, connect-token
issuance and full physical-phone pairing/connection remain separately unverified.
Computer-side Dev Tunnels login and QR pairing remain independent and available.

Tool state is reduced from ACP partial updates. Absent fields preserve their previous
values; supplied content collections replace the collection. The client retains
content blocks, diffs, raw input/output and locations rather than flattening the
wire event into a lossy activity title.

Pending approvals have encrypted local persistence and an authoritative bridge
snapshot on attach, independent of event cursors. Decisions are not considered
successful until acknowledged. Expiration is a terminal event, not a silent
transition back to busy.

Recent ACP history is an explicitly paged snapshot with tools and plans retained.
Older pages come from an immutable bridge snapshot and never reload an active ACP
process. Event-gap recovery waits until the agent is idle and advertises incomplete
history until recovery succeeds. The event ring remains bounded and in memory;
this is not a durable, complete audit log across bridge restarts.

While the UI is foreground it owns chat sockets. Background task monitoring hands
ownership to a bounded foreground service, which persists events and raises
completion/approval notifications. Returning to the UI first stops service-owned
sockets, then reloads durable state. Monitoring ends with tasks or a time/platform
limit; it does not promise delivery after force-stop, system termination or relay
credential expiration. No cloud push infrastructure or always-on tunnel is required.

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
- authenticated relay and private-network endpoint discovery
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
