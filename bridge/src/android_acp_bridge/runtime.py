from __future__ import annotations

import secrets
from dataclasses import dataclass
from typing import Any

from . import __version__
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


class BridgeRuntime:
    def __init__(self, config: BridgeConfig, pairing_store: PairingStore, require_local_pairing_confirmation: bool = True) -> None:
        self.config = config
        self.pairing_store = pairing_store
        self.require_local_pairing_confirmation = require_local_pairing_confirmation
        self.device_tokens: set[str] = set()

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

    def confirm_pairing(self, device: DeviceInfo) -> bool:
        if not self.require_local_pairing_confirmation:
            return True

        prompt = f"Allow {device.name} ({device.platform}) to pair with this machine? [y/N] "
        try:
            answer = input(prompt)
        except EOFError:
            return False
        return answer.strip().lower() in {"y", "yes"}


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

