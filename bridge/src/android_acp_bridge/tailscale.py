from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import time
from collections.abc import Mapping
from dataclasses import dataclass, replace
from enum import StrEnum
from pathlib import Path
from typing import Any, Protocol


class TailscaleState(StrEnum):
    CLI_MISSING = "tailscale_cli_missing"
    NEEDS_LOGIN = "tailscale_needs_login"
    STOPPED = "tailscale_stopped"
    RUNNING = "tailscale_running"
    ERROR = "tailscale_error"


@dataclass(frozen=True)
class TailscaleStatus:
    state: TailscaleState
    cli_path: str | None = None
    backend_state: str | None = None
    tailscale_ips: tuple[str, ...] = ()
    dns_name: str | None = None
    auth_url: str | None = None
    user: str | None = None
    message: str | None = None

    @property
    def preferred_endpoint_host(self) -> str | None:
        if self.dns_name:
            return self.dns_name.rstrip(".")
        return self.tailscale_ips[0] if self.tailscale_ips else None


@dataclass(frozen=True)
class TailscaleSetupResult:
    status: TailscaleStatus
    steps: tuple[str, ...] = ()


class CommandRunner(Protocol):
    def __call__(self, args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
        ...


class CommandExists(Protocol):
    def __call__(self, command: str) -> str | None:
        ...


class Sleep(Protocol):
    def __call__(self, seconds: float) -> None:
        ...


def default_runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        check=False,
    )


def default_command_exists(command: str) -> str | None:
    return find_command(command)


def find_command(
    command: str,
    *,
    which: CommandExists = shutil.which,
    system: str | None = None,
    environ: Mapping[str, str] = os.environ,
) -> str | None:
    resolved = which(command)
    if resolved is not None:
        return resolved
    if command != "tailscale" or (system or platform.system()).lower() != "windows":
        return None

    roots = (
        environ.get("ProgramFiles"),
        environ.get("ProgramFiles(x86)"),
        environ.get("LOCALAPPDATA"),
    )
    for root in roots:
        if not root:
            continue
        candidate = Path(root) / "Tailscale" / "tailscale.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def get_status(
    runner: CommandRunner = default_runner,
    timeout: int = 5,
    command_exists: CommandExists = default_command_exists,
) -> TailscaleStatus:
    cli_path = command_exists("tailscale")
    if cli_path is None:
        return TailscaleStatus(
            state=TailscaleState.CLI_MISSING,
            message="Tailscale CLI was not found. The bridge requires Tailscale by default.",
        )

    try:
        completed = runner([cli_path, "status", "--json"], timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return TailscaleStatus(state=TailscaleState.ERROR, message=str(exc))

    if completed.returncode != 0:
        return TailscaleStatus(
            state=TailscaleState.ERROR,
            message=(completed.stderr or completed.stdout or "tailscale status failed").strip(),
        )

    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        return TailscaleStatus(state=TailscaleState.ERROR, message=f"Invalid tailscale JSON: {exc}")

    return replace(parse_status(payload), cli_path=cli_path)


def ensure_tailscale_ready(
    runner: CommandRunner = default_runner,
    command_exists: CommandExists = default_command_exists,
    system: str | None = None,
    sleep: Sleep = time.sleep,
    poll_attempts: int = 12,
    poll_interval_seconds: float = 5,
) -> TailscaleSetupResult:
    steps: list[str] = ["Checking Tailscale status..."]
    current_system = system or platform.system()
    status = get_status(runner=runner, command_exists=command_exists)

    if status.state == TailscaleState.CLI_MISSING:
        install_command = build_install_command(current_system, command_exists)
        if install_command is None:
            return TailscaleSetupResult(status=_with_message(status, install_guidance(current_system)), steps=tuple(steps))

        steps.append(f"Tailscale CLI is missing. Installing with: {_format_command(install_command)}")
        install_result = runner(install_command, 900)
        status = get_status(runner=runner, command_exists=command_exists)
        if install_result.returncode != 0 and status.state == TailscaleState.CLI_MISSING:
            message = (install_result.stderr or install_result.stdout or "Tailscale installation failed.").strip()
            return TailscaleSetupResult(
                status=TailscaleStatus(
                    state=TailscaleState.ERROR,
                    message=f"{message}\n{install_failure_guidance(current_system, install_result.returncode, message)}",
                ),
                steps=tuple(steps),
            )

    if status.state in {TailscaleState.NEEDS_LOGIN, TailscaleState.STOPPED}:
        steps.append("Starting Tailscale login/connect flow with: tailscale up --qr")
        steps.append("Scan the Tailscale login QR or open the login URL, then sign in on this machine.")
        try:
            up_result = runner([status.cli_path or "tailscale", "up", "--qr"], 900)
        except subprocess.TimeoutExpired:
            return TailscaleSetupResult(status=_with_message(status, "Tailscale login timed out. Re-run the bridge and complete `tailscale up --qr`."), steps=tuple(steps))

        if up_result.stdout.strip():
            steps.append(up_result.stdout.strip())
        if up_result.returncode != 0:
            message = (up_result.stderr or up_result.stdout or "tailscale up --qr failed.").strip()
            return TailscaleSetupResult(status=TailscaleStatus(state=TailscaleState.ERROR, message=message), steps=tuple(steps))
        steps.append("Waiting for Tailscale to report a reachable IP...")
        status = wait_until_running(
            runner=runner,
            command_exists=command_exists,
            sleep=sleep,
            attempts=poll_attempts,
            interval_seconds=poll_interval_seconds,
        )

    if status.state == TailscaleState.RUNNING:
        steps.append("Tailscale is running. Use the same Tailscale account/tailnet on Android before scanning the bridge QR.")

    return TailscaleSetupResult(status=status, steps=tuple(steps))


def wait_until_running(
    runner: CommandRunner = default_runner,
    command_exists: CommandExists = default_command_exists,
    sleep: Sleep = time.sleep,
    attempts: int = 12,
    interval_seconds: float = 5,
) -> TailscaleStatus:
    status = get_status(runner=runner, command_exists=command_exists)
    for _ in range(max(0, attempts - 1)):
        if status.state == TailscaleState.RUNNING:
            return status
        sleep(interval_seconds)
        status = get_status(runner=runner, command_exists=command_exists)
    return status


def build_install_command(system: str, command_exists: CommandExists = shutil.which) -> list[str] | None:
    normalized = system.lower()
    if normalized == "windows" and command_exists("winget") is not None:
        return [
            "winget",
            "install",
            "--id",
            "Tailscale.Tailscale",
            "--exact",
            "--source",
            "winget",
            "--accept-package-agreements",
            "--accept-source-agreements",
        ]
    if normalized == "darwin" and command_exists("brew") is not None:
        return ["brew", "install", "--cask", "tailscale"]
    if normalized == "linux" and command_exists("curl") is not None and command_exists("sh") is not None:
        return ["sh", "-c", "curl -fsSL https://tailscale.com/install.sh | sh"]
    return None


def install_guidance(system: str) -> str:
    normalized = system.lower()
    if normalized == "windows":
        return "Install Tailscale with `winget install --id Tailscale.Tailscale --exact`, then re-run `android-acp-bridge start`."
    if normalized == "darwin":
        return "Install Tailscale with `brew install --cask tailscale`, then re-run `android-acp-bridge start`."
    if normalized == "linux":
        return "Install Tailscale with `curl -fsSL https://tailscale.com/install.sh | sh`, then re-run `android-acp-bridge start`."
    return "Install Tailscale from https://tailscale.com/download, then re-run the bridge."


def install_failure_guidance(system: str, returncode: int, detail: str) -> str:
    normalized = system.lower()
    policy_blocked = returncode == 1625 or "policy" in detail.lower() or "组织策略" in detail
    if normalized == "windows" and policy_blocked:
        return (
            "Windows organization policy blocked the winget installer. The bridge will not bypass device policy. "
            "Install Tailscale from your company software portal, ask an administrator to approve Tailscale.Tailscale, "
            "or use the official installer from https://tailscale.com/download/windows, then re-run `android-acp-bridge start`."
        )
    return install_guidance(system)


def parse_status(payload: dict[str, Any]) -> TailscaleStatus:
    backend_state = _string_or_none(payload.get("BackendState"))
    auth_url = _string_or_none(payload.get("AuthURL"))
    user = _extract_user(payload)
    self_node = payload.get("Self") if isinstance(payload.get("Self"), dict) else {}
    tailscale_ips = tuple(ip for ip in self_node.get("TailscaleIPs", []) if isinstance(ip, str))
    dns_name = _string_or_none(self_node.get("DNSName"))

    if backend_state == "Running" and tailscale_ips:
        return TailscaleStatus(
            state=TailscaleState.RUNNING,
            backend_state=backend_state,
            tailscale_ips=tailscale_ips,
            dns_name=dns_name,
            auth_url=auth_url,
            user=user,
        )

    if backend_state == "NeedsLogin" or auth_url:
        return TailscaleStatus(
            state=TailscaleState.NEEDS_LOGIN,
            backend_state=backend_state,
            tailscale_ips=tailscale_ips,
            dns_name=dns_name,
            auth_url=auth_url,
            user=user,
            message="Tailscale is installed but needs login. Run `tailscale up` or `tailscale up --qr`.",
        )

    return TailscaleStatus(
        state=TailscaleState.STOPPED,
        backend_state=backend_state,
        tailscale_ips=tailscale_ips,
        dns_name=dns_name,
        auth_url=auth_url,
        user=user,
        message="Tailscale is installed but not running. Run `tailscale up`.",
    )


def build_websocket_endpoint(status: TailscaleStatus, port: int) -> str | None:
    host = status.preferred_endpoint_host
    if not host:
        return None
    return f"ws://{host}:{port}"


def _extract_user(payload: dict[str, Any]) -> str | None:
    user_value = payload.get("User")
    if isinstance(user_value, str):
        return user_value

    self_node = payload.get("Self") if isinstance(payload.get("Self"), dict) else {}
    user_id = self_node.get("UserID")
    user_map = payload.get("User") if isinstance(payload.get("User"), dict) else {}
    if user_id is not None and isinstance(user_map, dict):
        user_entry = user_map.get(str(user_id))
        if isinstance(user_entry, dict):
            return _string_or_none(user_entry.get("LoginName")) or _string_or_none(user_entry.get("DisplayName"))
    return None


def _string_or_none(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _with_message(status: TailscaleStatus, message: str) -> TailscaleStatus:
    return TailscaleStatus(
        state=status.state,
        backend_state=status.backend_state,
        tailscale_ips=status.tailscale_ips,
        dns_name=status.dns_name,
        auth_url=status.auth_url,
        user=status.user,
        message=message,
    )


def _format_command(command: list[str]) -> str:
    return " ".join(command)
