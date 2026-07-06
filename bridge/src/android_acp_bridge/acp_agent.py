from __future__ import annotations

import json
import queue
import shutil
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class AcpAgentError(RuntimeError):
    pass


@dataclass(frozen=True)
class AcpPromptRequest:
    chat_id: str
    agent_id: str
    workspace_path: str
    prompt: str


class AcpAgentManager:
    def __init__(self) -> None:
        self._sessions: dict[str, AcpAgentSession] = {}

    def prompt(self, request: AcpPromptRequest) -> list[dict[str, Any]]:
        session = self._sessions.get(request.chat_id)
        if session is None:
            session = AcpAgentSession.start(request.agent_id, request.workspace_path)
            self._sessions[request.chat_id] = session
        return session.prompt(request.prompt)

    def list_sessions(self, agent_id: str, workspace_path: str) -> list[dict[str, Any]]:
        session = AcpAgentSession.start_without_session(agent_id, workspace_path)
        try:
            return session.list_sessions(workspace_path)
        finally:
            session.stop()

    def load_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str) -> list[dict[str, Any]]:
        old_session = self._sessions.pop(chat_id, None)
        if old_session is not None:
            old_session.stop()
        session, updates = AcpAgentSession.load(agent_id, workspace_path, session_id)
        self._sessions[chat_id] = session
        return updates

    def set_config_option(self, chat_id: str, config_id: str, value: str) -> list[dict[str, Any]]:
        session = self._sessions.get(chat_id)
        if session is None:
            raise AcpAgentError("No active ACP session exists for this chat. Send a prompt before changing model.")
        return session.set_config_option(config_id, value)


class AcpAgentSession:
    def __init__(self, process: subprocess.Popen[str], output_queue: queue.Queue[dict[str, Any]], session_id: str) -> None:
        self._process = process
        self._output_queue = output_queue
        self._session_id = session_id
        self._next_id = 3

    @classmethod
    def start(cls, agent_id: str, workspace_path: str) -> AcpAgentSession:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        result, updates = session._request(
            "session/new",
            {
                "cwd": str(workspace),
                "mcpServers": [],
            },
            timeout_seconds=60,
        )
        session._session_id = _extract_session_id(result)
        return session

    @classmethod
    def start_without_session(cls, agent_id: str, workspace_path: str) -> AcpAgentSession:
        workspace = _resolve_workspace(workspace_path)
        command = _agent_command(agent_id, workspace)
        process = subprocess.Popen(
            command,
            cwd=str(workspace),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        if process.stdin is None or process.stdout is None:
            process.kill()
            raise AcpAgentError("Failed to open ACP agent stdio pipes.")

        output_queue: queue.Queue[dict[str, Any]] = queue.Queue()
        stderr_queue: queue.Queue[str] = queue.Queue()
        _start_json_reader(process.stdout, output_queue)
        if process.stderr is not None:
            _start_stderr_reader(process.stderr, stderr_queue)

        session = cls(process, output_queue, "")
        session._request(
            "initialize",
            {
                "protocolVersion": 1,
                "clientCapabilities": {},
                "clientInfo": {
                    "name": "agentlink-bridge",
                    "title": "AgentLink Bridge",
                    "version": "0.1.0",
                },
            },
            timeout_seconds=30,
        )
        return session

    @classmethod
    def load(cls, agent_id: str, workspace_path: str, session_id: str) -> tuple[AcpAgentSession, list[dict[str, Any]]]:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        _result, updates = session._request(
            "session/load",
            {
                "sessionId": session_id,
                "cwd": str(workspace),
                "mcpServers": [],
            },
            timeout_seconds=120,
        )
        session._session_id = session_id
        return session, updates

    def prompt(self, prompt: str) -> list[dict[str, Any]]:
        _result, updates = self._request(
            "session/prompt",
            {
                "sessionId": self._session_id,
                "prompt": [{"type": "text", "text": prompt}],
            },
            timeout_seconds=300,
        )
        return updates

    def list_sessions(self, workspace_path: str) -> list[dict[str, Any]]:
        workspace = _resolve_workspace(workspace_path)
        result, _updates = self._request(
            "session/list",
            {"cwd": str(workspace)},
            timeout_seconds=60,
        )
        sessions = result.get("sessions", [])
        return sessions if isinstance(sessions, list) else []

    def set_config_option(self, config_id: str, value: str) -> list[dict[str, Any]]:
        result, _updates = self._request(
            "session/set_config_option",
            {
                "sessionId": self._session_id,
                "configId": config_id,
                "value": value,
            },
            timeout_seconds=60,
        )
        config_options = result.get("configOptions", [])
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "config_option_update",
                    "configOptions": config_options if isinstance(config_options, list) else [],
                },
            }
        ]

    def stop(self) -> None:
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)

    def _request(self, method: str, params: dict[str, Any], timeout_seconds: int) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        request_id = self._next_id
        self._next_id += 1
        self._write_json({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})

        updates: list[dict[str, Any]] = []
        while True:
            try:
                message = self._output_queue.get(timeout=timeout_seconds)
            except queue.Empty as exc:
                raise AcpAgentError(f"Timed out waiting for ACP response to {method}.") from exc

            if message.get("id") == request_id:
                if "error" in message:
                    raise AcpAgentError(f"ACP {method} failed: {message['error']}")
                result = message.get("result")
                return (result if isinstance(result, dict) else {}, updates)

            method_name = message.get("method")
            if method_name == "session/update":
                params_value = message.get("params") if isinstance(message.get("params"), dict) else {}
                update = params_value.get("update") if isinstance(params_value, dict) else None
                if isinstance(update, dict):
                    updates.append({"type": "session/update", "update": update})
            elif method_name == "session/request_permission":
                self._handle_permission_request(message)

    def _handle_permission_request(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        options = params.get("options") if isinstance(params, dict) else []
        option_id = "allow-once"
        if isinstance(options, list) and options:
            allow = next((item for item in options if isinstance(item, dict) and str(item.get("kind", "")).startswith("allow")), None)
            option_id = str((allow or options[0]).get("optionId", option_id))
        self._write_json({"jsonrpc": "2.0", "id": request_id, "result": {"outcome": {"outcome": "selected", "optionId": option_id}}})

    def _write_json(self, message: dict[str, Any]) -> None:
        if self._process.stdin is None:
            raise AcpAgentError("ACP agent stdin is closed.")
        self._process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self._process.stdin.flush()


def _agent_command(agent_id: str, workspace: Path) -> list[str]:
    if agent_id == "copilot-cli":
        copilot = shutil.which("copilot")
        if not copilot:
            raise AcpAgentError("GitHub Copilot CLI is not installed or not on PATH.")
        return [copilot, "--acp", "--allow-all", "--add-dir", str(workspace)]
    if agent_id == "claude-code":
        claude = shutil.which("claude")
        if not claude:
            raise AcpAgentError("Claude Code CLI is not installed or not on PATH.")
        return [claude, "--acp"]
    raise AcpAgentError(f"Unsupported agent: {agent_id}")


def _resolve_workspace(workspace_path: str) -> Path:
    workspace = Path(workspace_path).expanduser().resolve()
    if not workspace.exists() or not workspace.is_dir():
        raise AcpAgentError(f"Workspace does not exist or is not a directory: {workspace}")
    return workspace


def _extract_session_id(result: dict[str, Any]) -> str:
    session_id = result.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        raise AcpAgentError(f"ACP session/new did not return a sessionId: {result}")
    return session_id


def _start_json_reader(stream: Any, output_queue: queue.Queue[dict[str, Any]]) -> None:
    def read() -> None:
        for line in stream:
            stripped = line.strip()
            if not stripped:
                continue
            try:
                output_queue.put(json.loads(stripped))
            except json.JSONDecodeError:
                output_queue.put({"method": "agent/log", "params": {"text": stripped}})

    threading.Thread(target=read, daemon=True).start()


def _start_stderr_reader(stream: Any, stderr_queue: queue.Queue[str]) -> None:
    def read() -> None:
        for line in stream:
            stderr_queue.put(line.rstrip("\n"))

    threading.Thread(target=read, daemon=True).start()
