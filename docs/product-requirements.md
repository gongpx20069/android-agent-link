# Product Requirements

## Mochi shared control

- Expose only three grouped Mochi tools: workspace list/create, chat list/create/read,
  and control send/cancel/configure, plus a corresponding built-in Skill.
- Connect via an explicit Android authorization screen, with actual caller identity
  and resource/capability selection. Never expose machine or relay credentials.
- Use the same authoritative Chat and Workspace IDs in Mochi, Android and CLI;
  import shared catalogs rather than creating parallel task state.
- Read current task/configuration/approval/cursor state, not just assistant text.
- Keep execution approvals and permission escalation in trusted human UI.
- Reject stale Mochi follow-ups after human takeover, and never automatically rerun
  uncertain writes or interrupted tasks after restart.
- Keep new-workspace operations restricted to explicit bridge-owner roots.

## Account-based computer discovery

- Offer GitHub and Microsoft login from Machines, with QR/link pairing retained
  as a fallback. Microsoft uses explicit delegated tunnel consent, not only
  identity login or an unconfigured `.default` request.
- Distinguish live-verified authorization/API access from unverified physical-phone
  pairing, connection and renewal. Do not ask users to fix app registration.
- Discover only AgentLink-labelled computers owned by the selected account.
- First pairing displays a matching code for visual comparison and requires
  explicit `y/N` confirmation on the computer (default deny); account
  login alone never grants permission to operate it.
- Obtain fresh relay credentials for saved account-backed computers without
  requiring another QR scan while identity authorization remains valid.
- Explicitly show login denial, expiry, configuration gaps and pairing failure.
- Do not promise permanent login, computer-host uptime or unlimited mobile
  background monitoring. Client ID/tenant setup belongs to the publisher, not users.

## Vision

Create an Android app that lets a developer control coding agents running on multiple remote machines. The app should feel like a mobile chat client, but each chat is backed by a specific machine, workspace, and ACP agent session.

## Primary User

A developer who wants to start, monitor, and approve coding-agent work from a phone while the actual development environment remains on a workstation, devbox, or server.

## Core Mental Model

```text
Chat
  belongs to Machine
  runs in Workspace
  maps to ACP Agent Session
```

- The **Chats** list is the home screen.
- Machine and workspace are context labels and execution boundaries for each chat.
- Users should always know where an agent action will run before approving it.

## MVP Features

### Optional computer terminal

- Allow explicit `--interactive` mode alongside Android, with the same Chat,
  workspace, ACP session and serialized prompt queue.
- Use AgentLink's own input UI, not the agent's native terminal UI. Concurrent
  replies must not corrupt the user's draft.
- Do not require finding or copying Chat IDs. Automatically select the sole
  discovered chat while input is empty. For multiple chats, offer stable numbers
  labelled with project, phone title, agent, status and full workspace path.
- Keep terminal selection fixed until explicitly changed. Background phone
  connections must not retarget a draft; picker input must never execute as chat.
- Display selected-chat conversation and tool status, not Android delivery logs.
  Other chats display only task/approval notices.
- Keep input compact and show chat, runtime status, model, queue size,
  and contextual hints in a persistent width-aware status bar. Preserve drafts
  during refresh/resize and support monochrome.
- Use a full-screen redrawable conversation with tool groups/details collapsed by
  default, keyboard/mouse expansion, stable focus and paused following while browsing.
- Input clicks must restore visible typing. Typing/pasting from conversation focus
  must transfer to the draft, not disappear or submit. Returning to input or sending
  resumes following; focus changes into conversation alone must not pause it.
- Render agent Markdown headings, emphasis, lists, quotes, code blocks and tables
  in place without duplicate completion output. Bound retained content and explicitly
  page source text for long replies/layouts that cannot fit, without dropping Android events.
- Provide slash completion and an agent-backed `/model` picker. Confirm changes
  before displaying success and synchronize configuration to the phone. Reject
  busy/stale-session changes without freezing input or bypassing approval.
- Provide `/allow-all` through the same shared configuration path: display current
  state, support advertised boolean/select choices, require a separate risk
  confirmation, and never auto-resolve pending approvals or invent unsupported options.
- Keep prompts and approval details literal. Markdown must not execute code,
  fetch images, open links or pass remote terminal control sequences through.
- Provide explicit terminal copying of the latest reply or selected message/tool,
  with source formatting preserved and retention-limit warnings. Support native
  terminal selection by disabling app mouse handling. Android messages support
  long-press selection and source/code/detail copy buttons across retained pages;
  oversized clipboard requests fail visibly, never silently truncate.
- Avoid duplicate input echoes and per-update tool lines. Retain bounded completed
  tool groups and show failed tool titles even while collapsed.
- Keep pairing and approval explicit through one input controller, with default
  denial and authoritative expiry. Never treat terminal text as raw shell input.
- In interactive mode, show the QR/link only on `/qrcode` (alias `/pairing`) in a
  persistent dedicated view. Other server modes still show it at startup.
- Preserve ordinary headless behavior. Clearly explain that terminal-local new
  chats do not automatically appear on Android and history selection is live-only.

### Chats

- Show all chats across all machines.
- Persist the ACP session ID behind each chat so app or bridge restarts continue the same agent conversation instead of creating a replacement.
- Sessions with completed prompt history must remain discoverable through the agent after the local Android Chat is deleted.
- Display chat title, machine, workspace, agent, status, and pending approval count.
- Show an unread indicator for chats with completed Agent responses that the user has not opened.
- Support creating a new chat.
- Support opening an existing chat.
- Support completed, idle, running, failed, and disconnected states.

### New Chat

The new chat flow must collect:

- Machine
- Workspace
- Agent
- Permission mode
- Initial prompt, optional

Permission modes for MVP:

- **Manual approval**: all write and command operations require approval.
- **Read-only**: agent can inspect context but cannot write files or run commands.
- **Trusted workspace**: selected low-risk operations may be auto-approved after explicit user opt-in.

### Chat Detail

- Keep send/navigation responsive while streaming; do not synchronously serialize
  the entire chat archive on the UI thread.
- Retain older history on disk and browse it in explicit pages. Long replies/tool
  outputs must remain accessible through page controls, with no silent truncation.
- Preserve durable-before-send prompts/cancellations and atomic event/checkpoint
  recovery while batching updates. Surface storage failures and stop unsafe sends.
- Automatically migrate the legacy encrypted chat/approval data during upgrade;
  do not require clearing app data. Explain that legacy APK downgrade is unsupported.

- Show user prompts and agent responses.
- Stream agent updates when available.
- Show tool calls inline with status.
- Show current machine, workspace, and agent in the header.
- Maintain a persistent chat connection while the chat is active.
- Recover from mobile network drops by reconnecting and replaying missed bridge events.
- Show busy, idle, waiting-for-approval, and disconnected states from bridge-reported chat status.
- Synchronize every persisted Chat's Bridge status when the app starts, so the Chat list is correct before a Chat is opened.
- Allow prompts submitted while a chat is busy or waiting for approval to queue in FIFO order. After the active response completes, concatenate every prompt already waiting and send them together as the next ACP turn; prompts added during that turn form the following batch.
- Show queued prompts separately from the conversation timeline and allow removing a prompt before it starts, with immediate local feedback.
- Provide quick access to approvals, diffs, logs, and chat settings.
- Provide a Settings feedback entry that explicitly welcomes feature requests, bug reports, and development collaboration, linking to this repository's GitHub Issues and the developer contact email.
- When an Agent response completes while AgentLink is in the background, show a system notification containing the Chat name, latest Agent response, and a direct link to that Chat.
- Do not show completion notifications while AgentLink is in the foreground; use the chat-list unread indicator when the completed chat is not open.
- While foregrounded in another screen, show an actionable in-app completion or
  approval message in addition to unread indicators.
- Explain incomplete history and provide earlier-page loading. Preserve tool
  outputs, diffs and plans in recovery rather than returning text-only history.
- Show notification permission availability and a settings shortcut. Background
  monitoring is bounded to active work; display interruption/recovery guidance
  rather than promising delivery after force-stop or platform time limits.

### Approvals

- Central page for pending approvals across all chats.
- Each approval must show machine, workspace, chat, requested action, risk level, and exact target.
- User can approve or deny.
- Denied approvals should be visible in the chat timeline.
- Keep pending/submitting/resolved/expired approval states across app restarts;
  reconcile against the bridge snapshot independently of the event checkpoint.
- Do not present a decision as approved/denied until the bridge acknowledges it.

### Machines

- Add, edit, remove, and test remote bridge connections.
- Add a machine by scanning a bridge-generated pairing QR code.
- Add a machine by pasting the bridge-generated pairing deep link when in-app scanning is unavailable.
- Show online/offline state.
- Show bridge version and supported agents when available.
- Default to an authenticated relay that does not require an Android companion networking app.
- Support Tailscale or private-network addresses without requiring public exposure.
- Explain that Tailscale and ZeroTier transports require their client on both Android and the developer machine.
- Show clear troubleshooting when the selected transport or bridge endpoint is unreachable.

### Workspaces

- List workspaces reported by a machine bridge.
- Allow manual workspace entry if discovery is unavailable.
- Allow favoriting common workspaces.

### Diffs and Logs

- Default computer-console output summarizes task/tool lifecycle and approvals,
  not streamed message fragments. Keep Android streaming unchanged.
- Provide metadata-only debug logging and rate-limited progress for long-running
  tasks. Replays and multi-client delivery must not repeat business activity logs.
- Retain visible pairing prompts, default-deny input, and warning/error visibility
  while routine console output is deferred.
- Show file changes caused by a chat.
- Show command output and bridge/agent errors.
- Avoid logging secrets or full sensitive file contents by default.

## Non-Goals for MVP

- Full mobile IDE.
- Local Android execution of Claude Code, Copilot CLI, or other heavyweight agents.
- Multi-tenant cloud service.
- Public bridge exposure by default.
- Automatic approval of destructive operations.

## Success Criteria

- A user can connect at least two machines.
- A user can pair a machine by scanning a QR code generated by the bridge.
- A user can create chats against different workspaces.
- A user can distinguish where each chat is running.
- A user can safely approve or deny command/file operations from Android.
- A disconnected machine does not corrupt chat state or silently lose pending approvals.
- A temporarily disconnected Android device can reconnect to an active chat and replay missed agent updates.
- A chat's busy/idle state remains accurate even if its WebSocket reconnects while an agent turn is running.
- Queued prompts execute exactly once and in submission order without overlapping ACP prompt turns in one chat.
