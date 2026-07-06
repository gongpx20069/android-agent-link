from __future__ import annotations

import re
import shutil
import subprocess
import sys
import threading
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, TextIO


DEV_TUNNEL_DOWNLOAD_URL_WINDOWS_X64 = "https://aka.ms/TunnelsCliDownload/win-x64"
DEFAULT_TUNNEL_ID = "agentlink"


@dataclass(frozen=True)
class DevTunnelConfig:
    cli_path: str
    tunnel_id: str
    local_port: int
    connect_token: str
    https_url: str
    websocket_endpoint: str


@dataclass
class DevTunnelHost:
    config: DevTunnelConfig
    process: subprocess.Popen[str]
    output_thread: threading.Thread

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        if self.output_thread.is_alive():
            self.output_thread.join(timeout=2)


class CommandRunner(Protocol):
    def __call__(self, args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
        ...


class CommandExists(Protocol):
    def __call__(self, command: str) -> str | None:
        ...


def default_runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)


def ensure_devtunnel_cli(
    bridge_root: Path,
    runner: CommandRunner = default_runner,
    command_exists: CommandExists = shutil.which,
) -> str:
    existing = command_exists("devtunnel")
    if existing:
        return existing

    local_cli = bridge_root / ".tools" / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
    if local_cli.exists():
        return str(local_cli)

    if sys.platform != "win32":
        raise RuntimeError("Install the devtunnel CLI first: https://learn.microsoft.com/azure/developer/dev-tunnels/get-started")

    local_cli.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading devtunnel CLI to {local_cli}...", flush=True)
    urllib.request.urlretrieve(DEV_TUNNEL_DOWNLOAD_URL_WINDOWS_X64, local_cli)
    return str(local_cli)


def ensure_devtunnel_login(cli_path: str, runner: CommandRunner = default_runner) -> None:
    show = runner([cli_path, "user", "show"], 30)
    if show.returncode == 0:
        return

    print("Dev Tunnel login required. Starting device-code login...", flush=True)
    login = subprocess.run([cli_path, "user", "login", "-d"], check=False)
    if login.returncode != 0:
        raise RuntimeError("devtunnel user login failed.")


def create_or_reuse_tunnel(cli_path: str, tunnel_id: str, runner: CommandRunner = default_runner) -> None:
    show = runner([cli_path, "show", tunnel_id], 30)
    if show.returncode == 0:
        return

    create = runner([cli_path, "create", tunnel_id], 60)
    if create.returncode != 0:
        raise RuntimeError(_command_error("devtunnel create", create))


def ensure_tunnel_port(cli_path: str, tunnel_id: str, port: int, runner: CommandRunner = default_runner) -> None:
    create_port = runner([cli_path, "port", "create", tunnel_id, "-p", str(port), "--protocol", "http"], 60)
    if create_port.returncode == 0:
        return

    output = (create_port.stdout + "\n" + create_port.stderr).lower()
    if "already" in output or "exists" in output or "conflict" in output:
        return
    raise RuntimeError(_command_error("devtunnel port create", create_port))


def issue_connect_token(cli_path: str, tunnel_id: str, runner: CommandRunner = default_runner) -> str:
    token = runner([cli_path, "token", tunnel_id, "--scopes", "connect"], 30)
    if token.returncode != 0:
        raise RuntimeError(_command_error("devtunnel token", token))
    return parse_connect_token(token.stdout)


def start_devtunnel_host(
    *,
    cli_path: str,
    tunnel_id: str,
    local_port: int,
    connect_token: str,
    startup_timeout_seconds: int = 60,
) -> DevTunnelHost:
    process = subprocess.Popen(
        [cli_path, "host", tunnel_id],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    if process.stdout is None:
        raise RuntimeError("Failed to capture devtunnel host output.")

    ready = threading.Event()
    state: dict[str, str | None] = {"https_url": None}

    def drain_output(stream: TextIO) -> None:
        for line in stream:
            print(line, end="", flush=True)
            if state["https_url"] is None:
                parsed = parse_host_url(line, local_port)
                if parsed is not None:
                    state["https_url"] = parsed
                    ready.set()

    output_thread = threading.Thread(target=drain_output, args=(process.stdout,), daemon=True)
    output_thread.start()

    if not ready.wait(startup_timeout_seconds):
        if process.poll() is not None:
            raise RuntimeError(f"devtunnel host exited before printing a tunnel URL, code {process.returncode}.")
        process.terminate()
        raise RuntimeError("Timed out waiting for devtunnel host to print a tunnel URL.")

    https_url = state["https_url"]
    if https_url is None:
        raise RuntimeError("devtunnel host did not provide a tunnel URL.")

    config = DevTunnelConfig(
        cli_path=cli_path,
        tunnel_id=tunnel_id,
        local_port=local_port,
        connect_token=connect_token,
        https_url=https_url,
        websocket_endpoint=to_websocket_endpoint(https_url),
    )
    return DevTunnelHost(config=config, process=process, output_thread=output_thread)


def setup_devtunnel(
    *,
    bridge_root: Path,
    tunnel_id: str,
    local_port: int,
    cli_path: str | None = None,
    runner: CommandRunner = default_runner,
) -> DevTunnelHost:
    resolved_cli = cli_path or ensure_devtunnel_cli(bridge_root, runner)
    ensure_devtunnel_login(resolved_cli, runner)
    create_or_reuse_tunnel(resolved_cli, tunnel_id, runner)
    ensure_tunnel_port(resolved_cli, tunnel_id, local_port, runner)
    connect_token = issue_connect_token(resolved_cli, tunnel_id, runner)
    print("Starting private Dev Tunnel host...", flush=True)
    return start_devtunnel_host(
        cli_path=resolved_cli,
        tunnel_id=tunnel_id,
        local_port=local_port,
        connect_token=connect_token,
    )


def parse_host_url(output: str, port: int) -> str | None:
    pattern = re.compile(r"https://[^\s]+-" + re.escape(str(port)) + r"\.[^\s/]+\.devtunnels\.ms/?")
    match = pattern.search(output)
    if match:
        return match.group(0).rstrip("/")
    fallback = re.search(r"https://[^\s]+\.devtunnels\.ms/?", output)
    return fallback.group(0).rstrip("/") if fallback else None


def to_websocket_endpoint(https_url: str) -> str:
    url = https_url.strip().rstrip("/")
    if not url.startswith("https://"):
        raise ValueError("Dev Tunnel URL must start with https://.")
    return "wss://" + url.removeprefix("https://")


def parse_connect_token(output: str) -> str:
    jwt_match = re.search(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", output)
    if jwt_match:
        return jwt_match.group(0)

    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("devtunnel token did not print a connect token.")
    return lines[-1]


def _command_error(command_name: str, completed: subprocess.CompletedProcess[str]) -> str:
    output = (completed.stderr or completed.stdout or "").strip()
    return f"{command_name} failed with exit code {completed.returncode}: {output}"
