from __future__ import annotations

import hashlib
import platform
import socket
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_PORT = 4317


@dataclass(frozen=True)
class WorkspaceConfig:
    id: str
    display_name: str
    absolute_path: str


@dataclass(frozen=True)
class BridgeConfig:
    host: str = "127.0.0.1"
    port: int = DEFAULT_PORT
    machine_name: str = field(default_factory=socket.gethostname)
    workspaces: tuple[WorkspaceConfig, ...] = field(default_factory=tuple)

    @property
    def bridge_fingerprint(self) -> str:
        raw = f"{self.machine_name}|{platform.node()}|{platform.system()}".encode("utf-8")
        return "sha256:" + hashlib.sha256(raw).hexdigest()


def default_workspace(path: str | None = None) -> WorkspaceConfig:
    workspace_path = Path(path).resolve() if path else Path.cwd().resolve()
    return WorkspaceConfig(
        id=_stable_id(workspace_path.name or "workspace"),
        display_name=workspace_path.name or str(workspace_path),
        absolute_path=str(workspace_path),
    )


def default_config(host: str = "127.0.0.1", port: int = DEFAULT_PORT) -> BridgeConfig:
    return BridgeConfig(host=host, port=port, workspaces=())


def _stable_id(value: str) -> str:
    normalized = "".join(ch.lower() if ch.isalnum() else "-" for ch in value).strip("-")
    return normalized or "workspace"
