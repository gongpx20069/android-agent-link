# Security Model

## Security Goal

The Android app must let users control powerful remote coding agents without accidentally creating a remote shell, file deletion tool, or secret exfiltration path.

## Trust Boundaries

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

Tunnel labels and device names are not proof of identity. Account pairing is
enabled only in authenticated Dev Tunnel startup, requires exact local comparison
and input of a random six-digit code, and cannot use the QR auto-approve option.
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
