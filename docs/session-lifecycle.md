# Session lifecycle and background work

## Problem

A prompt response is not a reliable whole-session completion signal when an
agent delegates background work. The old ACP consumer also stopped dispatching
notifications when the prompt RPC returned. This could strand later output and
permission requests, report idle prematurely, and permit history recovery to
replace a process that still owned background work.

## Design

Copilot uses its native SDK session event stream (`github-copilot-sdk==1.0.13`).
Other agents retain ACP.
The Android/terminal/shared-control wire contract and chat/session identifiers
remain unchanged. An explicit ACP compatibility option is retained; it does not
claim the native backend's background completion guarantees.

- Subscribe before sending, and retain the subscription for the session lifetime.
  RPC acceptance and assistant message/turn completion are not task completion.
- Keep the bridge prompt operation active until the native session is idle with
  no background agents or attached shell commands in flight. Ignore child idle
  events and autopilot pauses. Queue subsequent prompts behind that operation.
- Serialize visible output and lifecycle events before publishing `operation.done`
  and `chat.status=idle`. Background tool output belongs to the same operation.
- Permission handling uses the existing bridge approval flow, including requests
  issued by background work. It must not block the SDK event reader or process
  shutdown. Late approval resolution cannot revive a completed operation.
- Errors, cancellations and transport loss are not successful completion.
- Keep the existing live-session locks and runtime busy guards. Attaching does not
  replace a process; history/config changes must not interrupt active work.
- Do not resume a known live session in a second process just to read its history.
  Reserve native session ownership during resume and reject attempts to bind the
  same session to another chat in this bridge process.
- Preserve model selection on resume and expose model/configuration changes through
  the existing configuration UI. Do not silently fall back to ACP if SDK startup
  or compatibility checks fail.

The adapter translates native events into existing ACP-shaped display updates.
It never reads private Copilot session files to infer liveness and never sends an
extra model prompt to poll, keep alive, or automatically continue a task.
Session listing translates the SDK's `SessionContext.working_directory` property
to the existing wire field `cwd`; `cwd` is a JSON field, not a Python SDK attribute.
Sessions without context remain valid and remote sessions remain excluded.
The dedicated event queue is bounded; overflow or delivery failure is an explicit
failure, not silent loss or successful completion. A native protocol ping checks
transport health without advancing the agent.

## Rollout and validation

Existing running bridge processes are not hot-patched or restarted. Deployment
requires a deliberate restart after active work finishes. Existing session IDs
are resumed, not replayed as new prompts.

Regression coverage must include response-before-background-completion, late
messages/tools/approvals, multiple background tasks, cancellation and failure,
message deduplication, and the existing queue/history/configuration guards.
Tests use a deterministic provider double and do not execute paid model prompts.
