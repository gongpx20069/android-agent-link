from __future__ import annotations

import json
import queue
import shutil
import subprocess
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


class AcpAgentError(RuntimeError):
    pass


class AcpSessionNotFoundError(AcpAgentError):
    pass


@dataclass(frozen=True)
class AcpPromptRequest:
    chat_id: str
    agent_id: str
    workspace_path: str
    prompt: str
    session_id: str | None = None
    session_resumable: bool = False


@dataclass(frozen=True)
class AcpSessionBinding:
    session_id: str
    resumable: bool
    replaced_session_id: str | None = None


PermissionCallback = Callable[[dict[str, Any]], str]
UpdateCallback = Callable[[dict[str, Any]], None]
SessionCallback = Callable[[AcpSessionBinding], None]


class AcpAgentManager:
    def __init__(self) -> None:
        self._sessions: dict[str, AcpAgentSession] = {}
        self._sessions_guard = threading.Lock()
        self._sessions_replacing: set[str] = set()
        self._session_locks: dict[str, threading.RLock] = {}
        self._session_locks_guard = threading.Lock()

    def prompt(
        self,
        request: AcpPromptRequest,
        permission_callback: PermissionCallback | None = None,
        update_callback: UpdateCallback | None = None,
        session_callback: SessionCallback | None = None,
    ) -> list[dict[str, Any]]:
        with self._chat_lock(request.chat_id):
            session, startup_updates, replaced_session_id = self._get_or_create_session(
                request.chat_id,
                request.agent_id,
                request.workspace_path,
                request.session_id,
                request.session_resumable,
            )
            if session_callback is not None:
                session_callback(session.binding(replaced_session_id))
            session.permission_callback = permission_callback
            if update_callback is not None:
                for update in startup_updates:
                    update_callback(update)
                startup_updates = []
            updates = session.prompt(request.prompt, update_callback=update_callback)
            if session_callback is not None:
                session_callback(session.binding())
            return startup_updates + updates

    def list_sessions(self, agent_id: str, workspace_path: str) -> list[dict[str, Any]]:
        session = AcpAgentSession.start_without_session(agent_id, workspace_path)
        try:
            return session.list_sessions(workspace_path)
        finally:
            session.stop()

    def load_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str) -> list[dict[str, Any]]:
        self._set_session_replacing(chat_id, True)
        try:
            with self._chat_lock(chat_id):
                old_session = self._pop_session(chat_id)
                if old_session is not None:
                    old_session.stop()
                session, updates = AcpAgentSession.load(agent_id, workspace_path, session_id)
                self._set_session(chat_id, session)
                return updates
        finally:
            self._set_session_replacing(chat_id, False)

    def restore_session(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str,
        session_resumable: bool,
        session_callback: SessionCallback,
    ) -> None:
        deadline = time.monotonic() + 120
        chat_lock = self._chat_lock(chat_id)
        while True:
            with self._sessions_guard:
                live_session = self._sessions.get(chat_id)
                if live_session is not None and chat_id not in self._sessions_replacing:
                    session_callback(live_session.binding())
                    return

            if chat_lock.acquire(blocking=False):
                try:
                    session, _startup_updates, replaced_session_id = self._get_or_create_session(
                        chat_id,
                        agent_id,
                        workspace_path,
                        session_id,
                        session_resumable,
                    )
                    with self._sessions_guard:
                        session_callback(session.binding(replaced_session_id))
                    return
                finally:
                    chat_lock.release()

            if time.monotonic() >= deadline:
                raise AcpAgentError(f"Timed out restoring ACP session {session_id} for chat {chat_id}.")
            time.sleep(0.01)

    def load_recent_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str, limit: int) -> dict[str, Any]:
        if not workspace_path.strip():
            raise AcpAgentError("Loading session history requires its workspacePath; refusing to bind it to the home directory.")
        _resolve_workspace(workspace_path)
        self._set_session_replacing(chat_id, True)
        try:
            with self._chat_lock(chat_id):
                session, updates, scanned_events, truncated = AcpAgentSession.load_recent(agent_id, workspace_path, session_id, limit)
                old_session = self._pop_session(chat_id)
                if old_session is not None:
                    old_session.stop()
                self._set_session(chat_id, session)
                return {
                    "updates": updates,
                    "scannedEvents": scanned_events,
                    "truncated": truncated,
                }
        finally:
            self._set_session_replacing(chat_id, False)

    def refresh_config_options(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str | None = None,
        session_resumable: bool = False,
        session_callback: SessionCallback | None = None,
    ) -> list[dict[str, Any]]:
        with self._chat_lock(chat_id):
            session, startup_updates, replaced_session_id = self._get_or_create_session(
                chat_id,
                agent_id,
                workspace_path,
                session_id,
                session_resumable,
            )
            if session_callback is not None:
                session_callback(session.binding(replaced_session_id))
            return startup_updates + session.config_option_updates()

    def set_config_option(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        config_id: str,
        value: str,
        session_id: str | None = None,
        session_resumable: bool = False,
        session_callback: SessionCallback | None = None,
    ) -> list[dict[str, Any]]:
        with self._chat_lock(chat_id):
            current = self._get_session(chat_id)
            if current is not None and session_id is not None and current.binding().session_id != session_id:
                raise AcpAgentError("Session changed. Refresh configuration before applying a selection.")
            session, startup_updates, replaced_session_id = self._get_or_create_session(
                chat_id,
                agent_id,
                workspace_path,
                session_id,
                session_resumable,
            )
            if session_callback is not None:
                session_callback(session.binding(replaced_session_id))
            return startup_updates + session.set_config_option(config_id, value)

    def _chat_lock(self, chat_id: str) -> threading.RLock:
        with self._session_locks_guard:
            return self._session_locks.setdefault(chat_id, threading.RLock())

    def _get_session(self, chat_id: str) -> AcpAgentSession | None:
        with self._sessions_guard:
            return self._sessions.get(chat_id)

    def _set_session(self, chat_id: str, session: AcpAgentSession) -> None:
        with self._sessions_guard:
            self._sessions[chat_id] = session

    def _pop_session(self, chat_id: str) -> AcpAgentSession | None:
        with self._sessions_guard:
            return self._sessions.pop(chat_id, None)

    def _set_session_replacing(self, chat_id: str, replacing: bool) -> None:
        with self._sessions_guard:
            if replacing:
                self._sessions_replacing.add(chat_id)
            else:
                self._sessions_replacing.discard(chat_id)

    def _is_session_replacing(self, chat_id: str) -> bool:
        with self._sessions_guard:
            return chat_id in self._sessions_replacing

    def _get_or_create_session(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str | None,
        session_resumable: bool,
    ) -> tuple[AcpAgentSession, list[dict[str, Any]], str | None]:
        session = self._get_session(chat_id)
        if session is not None:
            return session, [], None

        replaced_session_id: str | None = None
        if session_id:
            try:
                loaded = AcpAgentSession.load_for_continue(
                    agent_id,
                    workspace_path,
                    session_id,
                    resumable=session_resumable,
                )
                self._set_session(chat_id, loaded)
                return loaded, loaded.take_pending_updates(), None
            except AcpSessionNotFoundError:
                if session_resumable:
                    raise
                replaced_session_id = session_id

        created = AcpAgentSession.start(agent_id, workspace_path)
        self._set_session(chat_id, created)
        return created, created.take_pending_updates(), replaced_session_id


class AcpAgentSession:
    def __init__(
        self,
        process: subprocess.Popen[str],
        output_queue: queue.Queue[dict[str, Any]],
        session_id: str,
        resumable: bool = False,
    ) -> None:
        self._process = process
        self._output_queue = output_queue
        self._session_id = session_id
        self._resumable = resumable
        self._next_id = 3
        self.permission_callback: PermissionCallback | None = None
        self._pending_updates: list[dict[str, Any]] = []
        self._latest_config_options: list[dict[str, Any]] = []

    @classmethod
    def start(cls, agent_id: str, workspace_path: str) -> AcpAgentSession:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        try:
            result, updates = session._request(
                "session/new",
                {
                    "cwd": str(workspace),
                    "mcpServers": [],
                },
                timeout_seconds=60,
            )
            session._session_id = _extract_session_id(result)
        except Exception:
            session.stop()
            raise
        session._capture_config_options(result)
        session._pending_updates = session._pending_updates + updates
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
            process.wait(timeout=5)
            raise AcpAgentError("Failed to open ACP agent stdio pipes.")

        output_queue: queue.Queue[dict[str, Any]] = queue.Queue()
        stderr_queue: queue.Queue[str] = queue.Queue()
        session = cls(process, output_queue, "")
        try:
            _start_json_reader(process.stdout, output_queue)
            if process.stderr is not None:
                _start_stderr_reader(process.stderr, stderr_queue)
            _result, updates = session._request(
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
        except Exception:
            session.stop()
            raise
        session._pending_updates = updates
        return session

    @classmethod
    def load(cls, agent_id: str, workspace_path: str, session_id: str) -> tuple[AcpAgentSession, list[dict[str, Any]]]:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        try:
            result, updates, _scanned_events, truncated = session._request_and_drain(
                "session/load",
                {
                    "sessionId": session_id,
                    "cwd": str(workspace),
                    "mcpServers": [],
                },
                timeout_seconds=120,
                drain_idle_seconds=0.8,
                drain_timeout_seconds=30,
                max_updates=SESSION_RESTORE_MAX_EVENTS,
            )
            if truncated:
                raise AcpAgentError(
                    f"ACP session/load replay exceeded the recovery safety limit for session {session_id}."
                )
        except Exception:
            session.stop()
            raise
        session._session_id = session_id
        session._resumable = True
        session._capture_config_options(result)
        return session, updates

    @classmethod
    def load_for_continue(
        cls,
        agent_id: str,
        workspace_path: str,
        session_id: str,
        resumable: bool,
    ) -> AcpAgentSession:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        try:
            result, _updates, _scanned_events, truncated = session._request_and_drain(
                "session/load",
                {
                    "sessionId": session_id,
                    "cwd": str(workspace),
                    "mcpServers": [],
                },
                timeout_seconds=120,
                drain_idle_seconds=0.8,
                drain_timeout_seconds=30,
                max_updates=SESSION_RESTORE_MAX_EVENTS,
                collect_updates=False,
            )
            if truncated:
                raise AcpAgentError(
                    f"ACP session/load replay exceeded the recovery safety limit for session {session_id}."
                )
        except Exception:
            session.stop()
            raise
        session._session_id = session_id
        session._resumable = resumable
        session._capture_config_options(result)
        return session

    @classmethod
    def load_recent(cls, agent_id: str, workspace_path: str, session_id: str, limit: int) -> tuple[AcpAgentSession, list[dict[str, Any]], int, bool]:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        try:
            result, updates, scanned_events, truncated = session._request_and_drain(
                "session/load",
                {
                    "sessionId": session_id,
                    "cwd": str(workspace),
                    "mcpServers": [],
                },
                timeout_seconds=120,
                drain_idle_seconds=0.8,
                drain_timeout_seconds=30,
                max_updates=SESSION_RESTORE_MAX_EVENTS,
            )
            if truncated:
                raise AcpAgentError(
                    f"ACP session/load replay exceeded the recovery safety limit for session {session_id}."
                )
        except Exception:
            session.stop()
            raise
        session._session_id = session_id
        session._resumable = True
        session._capture_config_options(result)
        return session, updates, scanned_events, truncated

    def prompt(self, prompt: str, update_callback: UpdateCallback | None = None) -> list[dict[str, Any]]:
        _result, updates = self._request(
            "session/prompt",
            {
                "sessionId": self._session_id,
                "prompt": [{"type": "text", "text": prompt}],
            },
            timeout_seconds=300,
            update_callback=update_callback,
        )
        self._resumable = True
        return updates

    @property
    def session_id(self) -> str:
        return self._session_id

    def binding(self, replaced_session_id: str | None = None) -> AcpSessionBinding:
        return AcpSessionBinding(
            session_id=self._session_id,
            resumable=self._resumable,
            replaced_session_id=replaced_session_id,
        )

    def take_pending_updates(self) -> list[dict[str, Any]]:
        updates = self._pending_updates
        self._pending_updates = []
        return updates

    def list_sessions(self, workspace_path: str) -> list[dict[str, Any]]:
        params: dict[str, Any] = {}
        if workspace_path.strip() and workspace_path.strip() != "~":
            params["cwd"] = str(_resolve_workspace(workspace_path))
        result, _updates = self._request(
            "session/list",
            params,
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
        self._capture_config_options(result)
        return self.config_option_updates()

    def config_option_updates(self) -> list[dict[str, Any]]:
        if not self._latest_config_options:
            return []
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "config_option_update",
                    "configOptions": [option.copy() for option in self._latest_config_options],
                },
            }
        ]

    def _capture_config_options(self, result: dict[str, Any]) -> None:
        config_options = result.get("configOptions")
        if isinstance(config_options, list):
            self._latest_config_options = [option.copy() for option in config_options if isinstance(option, dict)]

    def stop(self) -> None:
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)

    def _request(
        self,
        method: str,
        params: dict[str, Any],
        timeout_seconds: int,
        update_callback: UpdateCallback | None = None,
    ) -> tuple[dict[str, Any], list[dict[str, Any]]]:
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
                    raise _request_error(method, message["error"])
                result = message.get("result")
                return (result if isinstance(result, dict) else {}, updates)

            method_name = message.get("method")
            if method_name == "session/update":
                params_value = message.get("params") if isinstance(message.get("params"), dict) else {}
                update = params_value.get("update") if isinstance(params_value, dict) else None
                if isinstance(update, dict):
                    if update.get("sessionUpdate") == "config_option_update" and isinstance(update.get("configOptions"), list):
                        self._latest_config_options = update["configOptions"]
                    wire_update = {"type": "session/update", "update": update}
                    if update_callback is not None:
                        update_callback(wire_update)
                    else:
                        updates.append(wire_update)
            elif method_name == "session/request_permission":
                self._handle_permission_request(message)

    def _request_and_drain(
        self,
        method: str,
        params: dict[str, Any],
        timeout_seconds: int,
        drain_idle_seconds: float,
        drain_timeout_seconds: float,
        max_updates: int,
        collect_updates: bool = True,
    ) -> tuple[dict[str, Any], list[dict[str, Any]], int, bool]:
        request_id = self._next_id
        self._next_id += 1
        self._write_json({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})

        result: dict[str, Any] = {}
        updates: deque[dict[str, Any]] = deque(maxlen=max_updates)
        seen_result = False
        scanned_events = 0
        truncated = False
        drain_deadline = 0.0

        while True:
            timeout = timeout_seconds if not seen_result else max(0.0, min(drain_idle_seconds, drain_deadline - time.monotonic()))
            if seen_result and timeout <= 0:
                return result, list(updates), scanned_events, True
            try:
                message = self._output_queue.get(timeout=timeout)
            except queue.Empty as exc:
                if seen_result:
                    return result, list(updates), scanned_events, truncated
                raise AcpAgentError(f"Timed out waiting for ACP response to {method}.") from exc

            if message.get("id") == request_id:
                if "error" in message:
                    raise _request_error(method, message["error"])
                result_value = message.get("result")
                result = result_value if isinstance(result_value, dict) else {}
                seen_result = True
                drain_deadline = time.monotonic() + drain_timeout_seconds
                continue

            method_name = message.get("method")
            if method_name == "session/update":
                params_value = message.get("params") if isinstance(message.get("params"), dict) else {}
                update = params_value.get("update") if isinstance(params_value, dict) else None
                if isinstance(update, dict):
                    scanned_events += 1
                    if update.get("sessionUpdate") == "config_option_update" and isinstance(update.get("configOptions"), list):
                        self._latest_config_options = update["configOptions"]
                    if collect_updates:
                        updates.append({"type": "session/update", "update": update})
                    if scanned_events >= SESSION_RESTORE_MAX_EVENTS:
                        return result, list(updates), scanned_events, True
            elif method_name == "session/request_permission":
                self._handle_permission_request(message)

    def _handle_permission_request(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        options = params.get("options") if isinstance(params, dict) else []
        if self.permission_callback is not None:
            option_id = self.permission_callback(message)
        else:
            reject = next((
                item for item in options if isinstance(item, dict) and str(item.get("kind", "")).startswith("reject")
            ), None) if isinstance(options, list) else None
            option_id = str(reject.get("optionId", "")) if reject else ""
        valid_option = isinstance(options, list) and any(
            isinstance(option, dict) and option.get("optionId") == option_id for option in options
        )
        outcome = {"outcome": "selected", "optionId": option_id} if valid_option else {"outcome": "cancelled"}
        self._write_json({"jsonrpc": "2.0", "id": request_id, "result": {"outcome": outcome}})

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
    workspace = Path.home() if not workspace_path.strip() else Path(workspace_path).expanduser().resolve()
    if not workspace.exists() or not workspace.is_dir():
        raise AcpAgentError(f"Workspace does not exist or is not a directory: {workspace}")
    return workspace


def _extract_session_id(result: dict[str, Any]) -> str:
    session_id = result.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        raise AcpAgentError(f"ACP session/new did not return a sessionId: {result}")
    return session_id


def _request_error(method: str, error: Any) -> AcpAgentError:
    if method == "session/load" and isinstance(error, dict) and error.get("code") == -32002:
        return AcpSessionNotFoundError(f"ACP {method} failed: {error}")
    return AcpAgentError(f"ACP {method} failed: {error}")


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


SESSION_RESTORE_MAX_EVENTS = 100_000
