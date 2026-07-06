from __future__ import annotations

import secrets
from dataclasses import dataclass
from typing import Any, Protocol

from . import __version__
from .acp_agent import AcpAgentError, AcpAgentManager, AcpPromptRequest
from .agents import discover_agents
from .config import BridgeConfig
from .pairing import PairingStore


@dataclass(frozen=True)
class DeviceInfo:
    name: str
    platform: str
    app_version: str


class PairingDeniedError(Exception):
    pass


class InvalidPairingTokenError(Exception):
    pass


class AgentManager(Protocol):
    def prompt(self, request: AcpPromptRequest) -> list[dict[str, Any]]:
        ...


class BridgeRuntime:
    def __init__(
        self,
        config: BridgeConfig,
        pairing_store: PairingStore,
        require_local_pairing_confirmation: bool = True,
        agent_manager: AgentManager | None = None,
    ) -> None:
        self.config = config
        self.pairing_store = pairing_store
        self.require_local_pairing_confirmation = require_local_pairing_confirmation
        self.device_tokens: set[str] = set()
        self.agent_manager = agent_manager or AcpAgentManager()

    def health_response(self) -> dict[str, Any]:
        return {"status": "ok", "bridgeVersion": __version__}

    def agents_response(self) -> dict[str, Any]:
        return {"agents": [agent.to_wire() for agent in discover_agents()]}

    def workspaces_response(self) -> dict[str, Any]:
        return {
            "workspaces": [
                {
                    "id": workspace.id,
                    "displayName": workspace.display_name,
                    "absolutePath": workspace.absolute_path,
                }
                for workspace in self.config.workspaces
            ]
        }

    def redeem_pairing(self, pairing_id: str, pairing_token: str, device: DeviceInfo) -> dict[str, str]:
        if not self.confirm_pairing(device):
            raise PairingDeniedError("Pairing was denied on the developer machine.")

        if not self.pairing_store.redeem(pairing_id, pairing_token):
            raise InvalidPairingTokenError("Pairing token is invalid, expired, or already used.")

        return {
            "machineId": self.config.machine_name,
            "deviceToken": self.issue_device_token(),
            "bridgeFingerprint": self.config.bridge_fingerprint,
        }

    def issue_device_token(self) -> str:
        token = "dev_" + secrets.token_urlsafe(32)
        self.device_tokens.add(token)
        return token

    def is_device_token_valid(self, token: str) -> bool:
        return token in self.device_tokens

    def websocket_responses(self, payload: Any) -> list[dict[str, Any]]:
        if not isinstance(payload, dict):
            return [{"type": "bridge.echo", "payload": payload}, {"type": "bridge.done"}]

        message_type = payload.get("type")
        if message_type == "chat.prompt":
            return self._chat_prompt_updates(payload)
        if message_type == "approval.decide":
            return self._approval_decision_updates(payload)
        return [{"type": "bridge.echo", "payload": payload}, {"type": "bridge.done"}]

    def confirm_pairing(self, device: DeviceInfo) -> bool:
        if not self.require_local_pairing_confirmation:
            return True

        prompt = f"Allow {device.name} ({device.platform}) to pair with this machine? [y/N] "
        try:
            answer = input(prompt)
        except EOFError:
            return False
        return answer.strip().lower() in {"y", "yes"}

    def _chat_prompt_updates(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        prompt = _string_or_default(payload.get("content"), "")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        try:
            updates = self.agent_manager.prompt(
                AcpPromptRequest(
                    chat_id=chat_id,
                    agent_id=agent_id,
                    workspace_path=workspace_path,
                    prompt=prompt,
                )
            )
        except AcpAgentError as exc:
            updates = [
                {
                    "type": "session/update",
                    "chatId": chat_id,
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "agent_start",
                        "title": "Agent runtime",
                        "kind": "execute",
                        "status": "failed",
                        "content": {"error": str(exc)},
                    },
                }
            ]
        for update in updates:
            update.setdefault("chatId", chat_id)
        return updates + [{"type": "bridge.done", "chatId": chat_id}]

    def _approval_decision_updates(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        approval_id = _string_or_default(payload.get("approvalId"), "unknown-approval")
        decision = _string_or_default(payload.get("decision"), "unknown")
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": approval_id,
                    "title": "Approval decision",
                    "kind": "approval",
                    "status": decision,
                    "content": {"decision": decision},
                },
            },
            {"type": "bridge.done"},
        ]


def parse_device_info(value: Any) -> DeviceInfo | None:
    if not isinstance(value, dict):
        return None

    name = value.get("name")
    platform = value.get("platform")
    app_version = value.get("appVersion")
    if not isinstance(name, str) or not name.strip():
        return None
    if not isinstance(platform, str) or not platform.strip():
        return None
    if not isinstance(app_version, str) or not app_version.strip():
        return None

    return DeviceInfo(name=name.strip(), platform=platform.strip(), app_version=app_version.strip())


def _string_or_default(value: Any, default: str) -> str:
    return value if isinstance(value, str) else default
