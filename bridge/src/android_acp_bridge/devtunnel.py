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


class DevTunnelAuthError(RuntimeError):
    pass


class DevTunnelConflictError(RuntimeError):
    pass


def default_runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)


def default_tunnel_id() -> str:
    return DEFAULT_TUNNEL_ID


def ensure_devtunnel_cli(
    bridge_root: Path,
    runner: CommandRunner = default_runner,
    command_exists: CommandExists = shutil.which,
) -> str:
    repo_cli = bridge_root.parent / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
    if repo_cli.exists():
        return str(repo_cli.resolve())

    existing = command_exists("devtunnel")
    if existing:
        existing_path = Path(existing)
        if existing_path.is_absolute():
            return str(existing_path)
        cwd_cli = Path.cwd() / existing_path
        if cwd_cli.exists():
            return str(cwd_cli.resolve())
        return existing

    local_cli = bridge_root / ".tools" / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
    if local_cli.exists():
        return str(local_cli.resolve())

    if sys.platform != "win32":
        raise RuntimeError("Install the devtunnel CLI first: https://learn.microsoft.com/azure/developer/dev-tunnels/get-started")

    local_cli.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading devtunnel CLI to {local_cli}...", flush=True)
    urllib.request.urlretrieve(DEV_TUNNEL_DOWNLOAD_URL_WINDOWS_X64, local_cli)
    return str(local_cli)


def ensure_devtunnel_login(cli_path: str, runner: CommandRunner = default_runner) -> None:
    show = runner([cli_path, "user", "show"], 30)
    output = (show.stdout + "\n" + show.stderr).lower()
    if show.returncode == 0 and "anonymous" not in output:
        return

    print("Dev Tunnel login required. Starting device-code login...", flush=True)
    login = subprocess.run([cli_path, "user", "login", "-d"], check=False)
    if login.returncode != 0:
        raise RuntimeError("devtunnel user login failed.")


def create_or_reuse_tunnel(cli_path: str, tunnel_id: str, runner: CommandRunner = default_runner) -> str:
    show = runner([cli_path, "show", tunnel_id], 30)
    shown_id = parse_tunnel_id(show.stdout)
    if show.returncode == 0 and shown_id is not None:
        return shown_id

    visible_id = find_visible_tunnel_id(cli_path, tunnel_id, runner)
    if visible_id is not None:
        return visible_id

    create = runner([cli_path, "create", tunnel_id], 60)
    if create.returncode != 0:
        output = (create.stdout + "\n" + create.stderr).lower()
        if "anonymous" in output or "unauthorized" in output or "not permitted" in output:
            raise DevTunnelAuthError(
                "Dev Tunnel creation was rejected because the CLI is not authenticated or lacks create access. "
                "Run `devtunnel user login -d` if devtunnel is on PATH, or "
                "`\\.\\bridge\\.tools\\devtunnel.exe user login -d` when using the bridge-downloaded CLI. "
                "Then retry `python .\\bridge\\run.py start --transport devtunnel`."
            )
        if "conflict with existing entity" in output or "already exists" in output:
            visible_id = find_visible_tunnel_id(cli_path, tunnel_id, runner)
            if visible_id is not None:
                return visible_id
            raise DevTunnelConflictError(
                f"Dev Tunnel ID `{tunnel_id}` is already taken but is not visible to this account. "
                "Retry with a unique ID, for example "
                "`python .\\bridge\\run.py start --transport devtunnel --devtunnel-id agentlink-myname-devbox`."
            )
        raise RuntimeError(_command_error("devtunnel create", create))
    created_id = parse_tunnel_id(create.stdout)
    if created_id is not None:
        return created_id

    show_created = runner([cli_path, "show", tunnel_id], 30)
    if show_created.returncode != 0:
        raise RuntimeError(_command_error("devtunnel show", show_created))
    return parse_tunnel_id(show_created.stdout) or tunnel_id


def find_visible_tunnel_id(cli_path: str, tunnel_id: str, runner: CommandRunner = default_runner) -> str | None:
    tunnels = runner([cli_path, "list"], 30)
    if tunnels.returncode != 0:
        return None
    for visible_id in parse_list_tunnel_ids(tunnels.stdout):
        if visible_id == tunnel_id or visible_id.startswith(f"{tunnel_id}."):
            return visible_id
    return None


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
    resolved_tunnel_id = create_or_reuse_tunnel(resolved_cli, tunnel_id, runner)
    ensure_tunnel_port(resolved_cli, resolved_tunnel_id, local_port, runner)
    connect_token = issue_connect_token(resolved_cli, resolved_tunnel_id, runner)
    print("Starting private Dev Tunnel host...", flush=True)
    return start_devtunnel_host(
        cli_path=resolved_cli,
        tunnel_id=resolved_tunnel_id,
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


def parse_tunnel_id(output: str) -> str | None:
    for line in output.splitlines():
        match = re.match(r"\s*Tunnel ID\s*:\s*(\S+)", line)
        if match:
            return match.group(1)
    return None


def parse_list_tunnel_ids(output: str) -> list[str]:
    ids: list[str] = []
    for line in output.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("Found ") or stripped.startswith("Tunnel ID") or set(stripped) == {"-"}:
            continue
        first_column = stripped.split(maxsplit=1)[0]
        if "." in first_column:
            ids.append(first_column)
    return ids


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
