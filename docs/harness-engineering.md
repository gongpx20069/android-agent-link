# Harness Engineering

This project uses harness engineering to make coding-agent work reliable, safe, and auditable.

## Layer 1: Memory

Memory is the durable instruction set and project context.

Files:

- `CLAUDE.md`
- `docs/README.md`
- `docs/product-requirements.md`
- `docs/architecture.md`
- `docs/acp-bridge-contract.md`
- `docs/security-model.md`

Rules:

- Keep instructions close to the repository.
- Update docs when product or architecture behavior changes.
- Use explicit terminology: Chat, Machine, Workspace, Agent Session, Approval.

## Layer 2: Tools

Tools are the build, test, protocol, and integration surfaces agents can use.

Planned tools:

- Python 3.11+ for the MVP bridge.
- Python standard library HTTP/WebSocket server for the default bridge runtime.
- Optional FastAPI, Pydantic, uvicorn, and qrcode extras for richer transport and QR rendering.
- Gradle for Android builds.
- Kotlin and Jetpack Compose for UI.
- Kotlin serialization for typed JSON messages.
- OkHttp or Ktor for WebSocket/HTTP.
- Contract tests for bridge messages.

Tool additions should be justified in documentation before implementation.

## Layer 3: Permissions

Permissions define what agents and the app may do.

Principles:

- Least privilege by default.
- Approval required for commands and file writes.
- Public bridge exposure is opt-in, not default.
- Machine and workspace context must be visible before risky actions.

Permission behavior is documented in `docs/security-model.md`.

## Layer 4: Hooks

Hooks are mechanical enforcement points.

Planned hooks after implementation begins:

- Format check before commit.
- Unit tests for ACP and bridge protocol models.
- Secret scanning before commit.
- Contract tests against bridge message fixtures.
- UI tests for approval flows.

Written instructions are not enough for safety-critical behavior. Important requirements should become hooks, tests, or runtime checks.

## Layer 5: Observability

Observability makes agent behavior inspectable.

Required event categories:

- Machine connection events.
- Chat lifecycle events.
- Prompt lifecycle events.
- Tool-call events.
- Approval requested/approved/denied events.
- Bridge and agent errors.

Observability constraints:

- Do not log secrets.
- Do not log full private files by default.
- Keep enough context to understand what happened and where it happened.

## Agent Workflow

Before changing code or docs, agents should:

1. Identify which layer is affected.
2. Read the matching documentation.
3. Make the smallest complete change.
4. Update docs if behavior changes.
5. Run the smallest relevant validation once tooling exists.

## Repository Maturity Stages

### Stage 0: Harness and Docs

Current stage. Define context, product requirements, architecture, bridge contract, and security model.

### Stage 1: Android Skeleton

Create Gradle project, module layout, navigation, and placeholder screens.

### Stage 2: Bridge Connectivity

Implement machine registration, health checks, authentication, and WebSocket connection state.

### Stage 3: Chat and ACP Session Flow

Implement chat creation, ACP session startup through bridge, prompt sending, and streaming updates.

### Stage 4: Safety Surfaces

Implement approvals, diff viewer, logs, and permission policies.

### Stage 5: Reliability

Implement reconnection, persistence, contract tests, telemetry, and failure recovery.
