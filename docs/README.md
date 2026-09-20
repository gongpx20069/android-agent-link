# Documentation Map

This folder defines the project memory layer for AgentLink.

## Using AgentLink

- [Quick start](../README.md) / [快速开始](../README.zh-CN.md): download, start the server, and chat.
- [User guide](user-guide.md) / [使用指南](user-guide.zh-CN.md): full setup, terminal controls, account discovery, troubleshooting, and optional Mochi integration.
- [Bridge guide](../bridge/README.md): installation and server configuration.

## Documents

- `product-requirements.md` describes the target user experience, MVP, and feature boundaries.
- `architecture.md` describes the app architecture, remote bridge architecture, data model, and key state flows.
- `session-lifecycle.md` describes native Copilot event delivery and background-work completion.
- `android-app.md` describes the Android app pairing MVP, storage, network calls, and validation.
- `acp-bridge-contract.md` describes the non-ACP bridge APIs needed by the Android app.
- `machine-pairing.md` describes Tailscale detection, bridge startup, QR pairing, and machine onboarding.
- `security-model.md` describes permissions, approvals, threat model, and secure defaults.
- `harness-engineering.md` describes how this repository applies memory, tools, permissions, hooks, and observability.

## Source of Truth

`CLAUDE.md` is the top-level harness instruction file. These docs provide deeper detail. When there is a conflict, update the docs and `CLAUDE.md` together so they agree.

## Documentation Rules

- Prefer short, concrete documents over broad essays.
- Keep protocol examples valid JSON unless explicitly marked as pseudocode.
- Mark open decisions with `Decision needed:` so they can be searched.
- Do not include secrets, real tokens, private machine names, or private repository paths.
