# Security Model

## Security Goal

The Android app must let users control powerful remote coding agents without accidentally creating a remote shell, file deletion tool, or secret exfiltration path.

## Trust Boundaries

### Same-device Mochi controller

AgentLink retains device/tunnel/account credentials. The exported authorization
Activity and Messenger service require explicit user grants and validate the real
calling UID/package and installed signing identity; a claimed package, request ID
or successful Activity result is not itself authorization. Grants are scoped and
revocable in AgentLink. The model cannot grant itself access, approve an execution
request, or authorize a permission escalation with an argument such as `confirmed`.

The bridge authenticates AgentLink's existing device connection. Android applies
the finer controller scope and forces the controller source; bridge authentication
alone does not identify the originating model. Shared catalog reads use authenticated
WebSocket control requests; the unauthenticated legacy HTTP workspace endpoint
continues to expose only explicitly configured startup workspaces.

New workspace operations require bridge-owner `--workspace-root` opt-in in addition
to the Android grant. Destinations are canonicalized and existing directories are
not overwritten; clone accepts credential-free HTTPS only. This is not a sandbox
against a malicious local user modifying directory links concurrently. ACP agent
permissions and human execution approvals still govern subsequent agent work.

The durable SQLite conversation journal is owner-private **but not encrypted**.
It contains sensitive prompt/output content, unlike the separate hash-only device
token store. Protect the disk and backups. Bounded history/truncation and interrupted
restart states must remain visible; they are not evidence that an external action
never ran. No automatic write retries are permitted after uncertain acceptance.

```text
Android UI
  trusted for user decisions
Network
  untrusted unless protected by TLS/private network
Bridge
  trusted only after authentication
Agent
  semi-trusted; must be mediated by approvals
Workspace
  sensitive source code and local developer files
External services
  untrusted by default
```

## Default Deployment

Use a private authenticated Microsoft Dev Tunnel as the default transport. Anonymous tunnel access is prohibited; pairing payloads carry a short-lived, machine-specific `X-Tunnel-Authorization` connect token.

Tailscale is an explicit alternative transport and requires both Android and the developer machine to install the Tailscale client and join the same tailnet. Localhost mode remains an explicit manual-testing opt-in.

The Android MVP permits cleartext HTTP/WebSocket traffic only for private Tailscale or LAN endpoints. Relay endpoints must use HTTPS/WSS and authenticated access.

## Authentication Requirements

- Every bridge must require authentication.
- Use short, revocable tokens for MVP.
- Use one-time, short-lived pairing tokens in QR codes.
- Exchange pairing tokens for revocable device tokens only after local confirmation on the developer machine.
- Store Android tokens in secure platform storage.
- Never commit tokens or local endpoints containing secrets.
- The bridge should support token rotation.
- Relay access headers such as `X-Tunnel-Authorization` are credentials. Store them only in secure platform storage, scope them to a single paired machine, and never log them.

### Restart-safe device authentication

Production bridge startup stores only SHA-256 hashes of random device tokens in
`%LOCALAPPDATA%\AgentLink\device-tokens.json` on Windows, or
`$XDG_STATE_HOME/AgentLink/device-tokens.json` (default `~/.local/state`) elsewhere.
The dedicated directory and files are user-private: protected current-user-only
Windows ACLs, or directory mode `0700` and file mode `0600`. Writes use a flushed,
atomic replacement; the bridge never returns a newly issued token if persistence
fails. Invalid/unreadable stores fail explicitly rather than silently forgetting
paired devices. Back up this file as sensitive authentication state, not as source.
Use `--device-token-store` only with a dedicated private directory.

Library/test construction with no configured store remains memory-only and never
writes to the real user profile. HTTP access logs redact query strings, including
WebSocket device tokens.

This durability does **not** renew Dev Tunnel connect tokens, extend their lifetime,
or permit anonymous access. Legacy QR relay credentials require re-pairing when
expired. Account-backed connections separately obtain fresh connect tokens using
the owner's identity; they still require the bridge device credential.

### Account discovery boundaries

OAuth device authorization happens only against the fixed provider endpoints in
an external browser. GitHub uses the client ID published for client apps by the
Dev Tunnels SDK; Microsoft uses AgentLink's own publisher-provided client ID.
No client secrets are embedded. Microsoft account claims from the direct TLS
token response identify the locally stored account; management API authorization
is always performed by the tunnel service, not by decoded claims.

Microsoft login and refresh request
`46da2f7e-b5ef-422a-88d4-2a7f9de6a0b2/all openid profile offline_access`.
This is delegated tunnel access, not just basic identity consent or a
single-tunnel connect grant. The browser prompts for the user's authorization,
and the UI tells users to review it. The application uses the identity only for
management reads and obtaining tunnel-scoped connect tokens; the broad identity
credential is never sent to the bridge. No first-party Microsoft client ID is
borrowed. A successful live `/all` request does not establish that every tenant's
consent policy allows the same request.

Tunnel labels and device names are not proof of identity. Account pairing is
enabled only in authenticated Dev Tunnel startup, displays a random six-digit code
for visual comparison, and requires explicit `y`/`yes` approval at a `[y/N]` prompt.
Enter or any other input denies access. Unlike retyping the code, this relies on
the user actually comparing both screens before approving; the prompt requests
that comparison. Account pairing cannot use the QR auto-approve option.
Attempts expire after two minutes. One console request at a time and a minimum
ten-second request interval bound prompting; an unanswered console input blocks
additional prompts until dismissed. Device labels reject control characters.
Poll credentials are random, supplied in POST bodies, and terminal approval is
consumed only after durable device-token issuance. Storage failures are explicit.

Account tokens are encrypted, excluded from backup/transfer, never logged, and
never sent to bridges. Connect tokens are scoped to the selected tunnel and cached
briefly in memory. Management pagination and forwarding origins are validated and
credential-bearing clients refuse redirects. Saved bindings include the account
ID; switching identities cannot reuse another account's grant. Sign-out is local,
not remote revocation. Network failures never enable anonymous fallback.

## Pairing QR Security

The QR code may include endpoint metadata, pairing ID, a short-lived pairing token, expiry, bridge fingerprint, and short-lived relay access headers needed to reach the bridge. It must not include long-lived credentials or broad third-party tokens.

Recommended QR properties:

- Expires within 2-5 minutes.
- Single use.
- Bound to the bridge instance and machine fingerprint.
- Requires local confirmation before issuing a device token.
- Safe to regenerate without invalidating already paired devices.
- Relay access headers in the QR should be short-lived and scoped to the single tunnel or machine endpoint.

## Approval Policy

### Always Require Approval

- Running shell commands.
- Writing files.
- Deleting files.
- Installing dependencies.
- Changing Git history.
- Creating commits or pushing branches.
- Accessing external network services from the agent.
- Reading files outside configured workspace roots.

### May Be Auto-Approved After Explicit Opt-In

- Reading files inside the selected workspace.
- Listing configured workspace files.
- Running safe status commands such as `git status`.
- Reading non-sensitive logs generated by the current chat.

### Never Auto-Approve

- `git reset --hard`
- force push
- recursive delete
- commands with secret-looking inline values
- writing credential files
- changing bridge configuration

## Approval Screen Requirements

Every approval must show:

- Machine
- Workspace
- Chat
- Agent
- Action type
- Exact command or file path
- Risk level
- Reason requested by agent
- Approve and deny actions

Approvals must not use vague labels such as "continue" when the action is risky.

### Approval recovery

Every chat attach returns an authoritative chat-scoped pending approval snapshot,
even after the request event has been acknowledged. Requests include creation and
expiry times in epoch milliseconds. Decisions and the five-minute timeout emit
replayable `approval.resolved` events. Clients must wait for resolution or a
successful `approval.decide.result`, not infer success from sending a decision.
Retries return the existing terminal result, including expiry, without changing it.
The bridge retains the latest 1,000 terminal decisions in memory; approvals are
not restored across bridge restarts. A denial never falls back to an allow option.

### Android chat storage

Chat bodies, titles, workspace metadata, prompts, tool details, approvals and replay
cursors are stored as authenticated AES-256-GCM payloads under a dedicated
Android Keystore key. Record identity is authenticated as associated data. Each
rewrite uses a fresh nonce. Large ciphertexts are split into small SQLite rows and
authenticated after reassembly; missing/reordered/modified parts fail closed.

This is record encryption, not whole-database encryption: opaque chat/message/
approval IDs, row positions, role/kind enums, record sizes and table relationships
remain visible in SQLite indexes. No plaintext transcript, tool body, command,
workspace path, identity credential or device token is written to database payloads.
The database is excluded from cloud backup/device transfer because its Keystore
key cannot be transferred.

Migration reads existing encrypted preferences and commits all imported data and
the migration marker together before removing legacy values. It does not permit
downgrade to a legacy-store-only APK. Storage failures are visible and block sends.
User prompts/cancel tombstones pass a durability barrier before network transmission;
received approval changes and their chat cursor commit together, rather than relying
on separate encrypted preference commits.

### History snapshot boundaries

Recent-history loads require the session's workspace instead of silently selecting
the bridge home directory. Pagination uses an immutable chat/session-bound snapshot;
it never reloads ACP or replaces an active session. Snapshots include tool/diff,
plan, and control information for reviewing agent actions. They live only in memory,
expire after 30 minutes, and are bounded to 16 snapshots (one per chat). Expired,
evicted, restarted, or mismatched snapshots fail explicitly; partial replay is not
represented as complete history.

A successful explicit `session.loadRecent` also replaces that chat's replay
baseline: it discards the old event log, rotates a per-chat `eventGeneration`,
and returns `latestEventId: 0` alongside the new generation. Clients save this
checkpoint with the snapshot before attaching, so events from the previous ACP
session cannot reappear in the new timeline. Other chats are unaffected; history
pagination never changes checkpoints. Loading fails with `session_busy` while
that chat has an active prompt/approval, and prompts cannot start during the load.
Failed loads preserve the previous replay baseline.

## Workspace Boundaries

- Workspace paths must be absolute on the remote machine.
- Bridge must check requested paths against allowed roots.
- ACP `cwd` is the primary workspace root.
- ACP `additionalDirectories` may expand roots only when configured and visible to the user.

## Logging Rules

Interactive terminal mode is an explicitly selected conversation surface, not a
diagnostic log. It displays user/agent text and approval details to the local
computer user. Do not share terminal recordings as sanitized logs. Input history
is disabled and no new transcript file is written.

Phone titles and prompt excerpts in the numbered chat picker are bounded display
labels, not session identifiers or authorization. Background connections never
change an existing terminal selection, and automatic initial selection waits for
empty input. Picker numbers resolve against the displayed snapshot; an invalid
choice cannot become a prompt or select a newly arrived, unseen chat.

The local terminal is trusted by possession of the bridge's console; it does not
add an unauthenticated network API. Phone authentication and pairing remain
unchanged. Terminal prompts and decisions use the existing runtime queue and
approval resolver, not direct access to agent stdin or a shell. The local pairing
broker accepts approval only after the request is displayed and before expiry;
exit denies pending pairing. Terminal approval requires reviewed details, and
truncated requests must be approved on Android instead.

Agent replies may be rendered as Markdown in interactive mode. Prompts, labels,
tool summaries and approval details remain literal. ANSI/OSC escapes, clipboard
sequences and control/bidi formatting characters are stripped before parsing and
again from rendered text segments, including controls decoded from entities.
Only locally generated style sequences are decoded into formatted UI fragments.
Markdown code is never executed, links never open automatically, and image
placeholders never fetch remote or local resources. HTML is unsupported.
Long Markdown and tool content use bounded source/detail pages. Oversized retained
entries, tool projections and evicted history are explicitly labelled incomplete.
The bounded display queue excludes protocol credentials but may contain sensitive
tool arguments/output, as a deliberately selected local conversation UI. Its
overflow does not authorize requests or drop Android events. Display overflow or
history eviction clears terminal approval-review markers; re-review or use Android.
The startup pairing QR/link remains available in a separate `/pairing` view, never
added to the transcript/cache as an ordinary message.

Android copy buttons and terminal `/copy`/Ctrl+Y are explicit local clipboard
writes, never triggered by agent events. The clipboard can contain sensitive
conversation/tool content and may be visible to OS clipboard history/sync or
other clipboard consumers. No clipboard read or network transfer is introduced.
The terminal passes sanitized retained text through stdin to a fixed local
clipboard command without shell interpolation, and suppresses utility stderr
to avoid echoing private content. Missing utilities, failures and truncation are
explicit. Android refuses oversized writes without altering existing contents.

`/model` only offers agent-advertised values and waits for confirmed state before
reporting success. Configuration is refused during prompts, approvals or another
session operation. Requests are pinned to their chat/session; stale session
selections are rejected under the agent lock. Configuration broadcasts reuse the
authenticated Android subscriber, not a new endpoint or anonymous attach.
Unknown slash commands are not prompts or shell commands; advertised agent
commands use the existing prompt/approval path. `/resume` is reserved for Android's
explicit session UX. `/allow-all` is a terminal configuration picker, never a
prompt or a raw CLI flag. Only agent-advertised boolean/select permission options
are offered. Enter selects but does not apply: a separate risk confirmation
identifies the shared session and target value and requires `y`; `n` returns to
selection and Esc cancels. Automatic permission may let the agent execute commands
or modify files without per-action prompts. Configuration never approves existing
requests, and runtime busy/approval/session guards remain authoritative. There is
no bridge-wide auto-approval toggle, arbitrary inline value or unsupported-agent
fallback. Agent confirmation is required before reporting success.

- Bridge runtime logging is metadata-only at both `info` and `debug`: no prompt,
  reply/thought body, tool title/content/arguments, raw error payload or request URL
  is printed. Diagnostic identifiers are single-line sanitized and length-bounded.
- Initial pairing QR/link/code output is a deliberate local onboarding exception,
  not a shareable diagnostic log. External Dev Tunnels CLI output is not filtered
  by the runtime logger.
- Error events and HTTP failure status remain visible at their configured severity;
  their detailed payloads remain in the authenticated Android protocol rather than
  being copied to the terminal. High verbosity does not enable raw payload dumping.
- Redact tokens, cookies, authorization headers, SSH keys, and API keys.
- Avoid storing full file contents in logs.
- Keep enough metadata for auditing approvals and failures.
- Show bridge and agent errors without exposing secrets.

## Threats

### Malicious or Compromised Bridge

Mitigations:

- Authenticate bridge.
- Prefer private networking.
- Show machine identity clearly.
- Let user remove or disable a machine quickly.

### Agent Requests Dangerous Command

Mitigations:

- Approval gate.
- Risk labeling.
- Deny by default on ambiguous commands.
- Persist approval decision in chat timeline.

### User Approves Wrong Machine

Mitigations:

- Always show machine and workspace in chat header.
- Show machine and workspace on approval cards.
- Avoid hidden context switching.

### Secret Leakage

Mitigations:

- Redaction in logs.
- No token storage in source files.
- Do not send private file contents to Android logs unless required for display.
