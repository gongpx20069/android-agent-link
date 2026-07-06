# CLAUDE.md - AgentLink

This file is the primary agent harness for this repository. It is intentionally prescriptive: agents should follow it before making code, documentation, dependency, or workflow changes.

## Project Mission

Build an Android-first ACP client for controlling multiple remote coding-agent environments. The app is a mobile control surface for remote machines, workspaces, chats, approvals, diffs, logs, and agent sessions.

The app should treat the phone as the coordinator, not as the main development runtime. Code checkout, shell commands, Git operations, and agent CLI execution happen on remote machines or developer workstations through a bridge.

## Product Concepts

- **Chat**: The primary user-facing object. A chat is one agent conversation/task.
- **Machine**: A remote developer machine, workstation, or devbox that runs an ACP bridge and agent processes.
- **Workspace**: A project directory or repository on a machine. In ACP this maps to `session/new.params.cwd`.
- **Agent Session**: The ACP session behind a chat. It has a `sessionId`, status, prompt history, updates, approvals, and tool-call state.
- **Approval**: A user decision for risky operations such as running commands, writing files, deleting files, or accessing external services.

## Harness Engineering Model

This repository follows a five-layer harness model.

### 1. Memory

Persistent project context lives in:

- `CLAUDE.md`
- `docs/README.md`
- `docs/product-requirements.md`
- `docs/architecture.md`
- `docs/android-app.md`
- `docs/acp-bridge-contract.md`
- `docs/machine-pairing.md`
- `docs/security-model.md`
- `docs/harness-engineering.md`

Agents must read the relevant docs before changing behavior, architecture, security, or API contracts.

### 2. Tools

Prefer native ecosystem tools once an Android project exists:

- Gradle for build and tests.
- Kotlin and Jetpack Compose for Android UI.
- OkHttp or Ktor client for WebSocket/HTTP transport.
- Kotlin serialization for protocol models.
- Git only for repository operations requested by the user.

Do not add new tools or frameworks without documenting the reason in `docs/architecture.md` or the relevant decision section.

### 3. Permissions

Default to least privilege:

- Do not silently run destructive commands.
- Do not expose bridge endpoints publicly by default.
- Do not store bridge tokens, OAuth tokens, SSH keys, or API keys in source files.
- Do not implement "raw shell passthrough" from Android to a machine without explicit approval UX.
- Do not auto-approve file deletion, dependency installation, Git history rewrite, or external network access.

Any feature that changes files, runs commands, or accesses secrets must surface an approval path in the mobile UX.

### 4. Hooks

When code is added, enforce important rules mechanically instead of only documenting them. Planned enforcement points:

- Pre-commit formatting and static checks.
- Gradle unit tests for protocol models and connection state machines.
- UI screenshot or Compose tests for critical approval flows.
- Contract tests for bridge message compatibility.
- Security checks for accidental secret commits.

If a rule is important enough to block unsafe behavior, implement it as code or tests rather than relying only on this file.

### 5. Observability

The app and bridge should make agent behavior auditable:

- Record chat lifecycle events.
- Record machine connection state transitions.
- Record approval decisions with timestamp, machine, workspace, command/file target, and outcome.
- Record bridge and agent errors in a user-visible logs surface.
- Avoid logging secrets, full tokens, or private file contents unless explicitly required and approved.

## Engineering Rules

- Keep the user model chat-centric: `Chat -> Machine -> Workspace -> Agent Session`.
- Keep ACP protocol handling separate from app UI state.
- Keep bridge-specific APIs separate from standard ACP messages.
- Treat machine and workspace labels as untrusted display data.
- Validate all remote paths and command metadata received from a bridge.
- Prefer explicit state machines for connection, session, prompt, and approval flows.
- Use behavior-safe defaults: disconnected, read-only, and approval-required states should be safe.
- Document new protocol fields before implementing them.

## Documentation Update Rules

Update docs whenever behavior changes:

- Product behavior: update `docs/product-requirements.md`.
- App/bridge/system structure: update `docs/architecture.md`.
- Android app pairing, storage, navigation, or validation: update `docs/android-app.md`.
- Bridge API or remote transport: update `docs/acp-bridge-contract.md`.
- Machine onboarding, Tailscale startup, or QR pairing: update `docs/machine-pairing.md`.
- Permissions, approvals, threat model: update `docs/security-model.md`.
- Agent workflow or enforcement rules: update `docs/harness-engineering.md`.

## Initial MVP Scope

The first useful version should include:

1. Chats list as the home screen.
2. New chat flow selecting machine, workspace, agent, and permission mode.
3. Chat detail with streaming agent updates.
4. Approval center for pending command/file operations.
5. Machine management for remote bridge connections.
6. Workspace discovery or manual workspace registration per machine.
7. Diff and logs surfaces sufficient to review agent actions.

## Out of Scope Until Explicitly Approved

- Running full coding agents directly inside Android.
- Public multi-tenant hosting.
- Automatic command execution without approval.
- Editing arbitrary phone files through ACP.
- Building a full mobile IDE before the remote-control workflow is stable.
