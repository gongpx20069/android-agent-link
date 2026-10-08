from __future__ import annotations

import json
import logging
import queue
import shutil
import subprocess
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .acp_connection import AcpConnection
from .agents import AGENT_SPECS
from .attachments import ImageInput


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
    image: ImageInput | None = None


@dataclass(frozen=True)
class AcpSessionBinding:
    session_id: str
    resumable: bool
    replaced_session_id: str | None = None
    history_replay_supported: bool | None = None


PermissionCallback = Callable[[dict[str, Any]], str | dict[str, Any]]
UpdateCallback = Callable[[dict[str, Any]], None]
SessionCallback = Callable[[AcpSessionBinding], None]


class AcpAgentManager:
    def __init__(self, copilot_transport: str = "acp") -> None:
        if copilot_transport not in {"sdk", "acp"}:
            raise ValueError("Unsupported Copilot transport.")
        self._copilot_transport = copilot_transport
        self._sessions: dict[str, AcpAgentSession] = {}
        self._sessions_guard = threading.Lock()
        self._sessions_replacing: set[str] = set()
        self._session_locks: dict[str, threading.RLock] = {}
        self._session_locks_guard = threading.Lock()
        self._session_claims: dict[tuple[str, str], str] = {}

    def _session_type(self, agent_id: str) -> type[AcpAgentSession]:
        if agent_id == "copilot-cli" and self._copilot_transport == "sdk":
            from .copilot_session import CopilotAgentSession
            return CopilotAgentSession
        return AcpAgentSession

    @contextmanager
    def _claim_session(self, chat_id: str, agent_id: str, session_id: str) -> Iterator[None]:
        key = (agent_id, session_id)
        with self._sessions_guard:
            if key in self._session_claims or any(
                owner != chat_id and session.session_id == session_id
                for owner, session in self._sessions.items()
            ):
                raise AcpAgentError("This agent session already belongs to another chat. Open that chat instead.")
            self._session_claims[key] = chat_id
        try:
            yield
        finally:
            with self._sessions_guard:
                self._session_claims.pop(key, None)

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
            if request.image is not None:
                updates = session.prompt(request.prompt, update_callback=update_callback, image=request.image)
            else:
                updates = session.prompt(request.prompt, update_callback=update_callback)
            if session_callback is not None:
                session_callback(session.binding())
            return startup_updates + updates

    def list_sessions(self, agent_id: str, workspace_path: str) -> list[dict[str, Any]]:
        session = self._session_type(agent_id).start_without_session(agent_id, workspace_path)
        try:
            return [{**item, "historyReplaySupported": session.binding().history_replay_supported is not False}
                    for item in session.list_sessions(workspace_path)]
        finally:
            session.stop()

    def supports_images(self, chat_id: str) -> bool:
        session = self._get_session(chat_id)
        return session is not None and session.image_input_supported

    def resume_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str) -> tuple[AcpSessionBinding, list[dict[str, Any]]]:
        _resolve_workspace(workspace_path)
        self._set_session_replacing(chat_id, True)
        try:
            with self._chat_lock(chat_id):
                live = self._get_session(chat_id)
                if live is not None:
                    live.ensure_replaceable()
                    if live.session_id == session_id:
                        return live.binding(), live.config_option_updates()
                with self._claim_session(chat_id, agent_id, session_id):
                    resumed = self._session_type(agent_id).load_for_continue(agent_id, workspace_path, session_id, True)
                    try:
                        if resumed.binding().session_id != session_id or not resumed.binding().resumable:
                            raise AcpAgentError("Agent did not confirm the requested resumable session.")
                        if live is not None:
                            live.stop()
                    except Exception:
                        resumed.stop()
                        raise
                    self._set_session(chat_id, resumed)
                    return resumed.binding(), resumed.take_pending_updates()
        finally:
            self._set_session_replacing(chat_id, False)

    def load_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str) -> list[dict[str, Any]]:
        self._set_session_replacing(chat_id, True)
        try:
            with self._chat_lock(chat_id):
                live = self._get_session(chat_id)
                if live is not None:
                    live.ensure_replaceable()
                if live is not None and live.session_id == session_id:
                    updates, _ = live.history()
                    return updates
                with self._claim_session(chat_id, agent_id, session_id):
                    session, updates = self._session_type(agent_id).load(agent_id, workspace_path, session_id)
                    old_session = self._pop_session(chat_id)
                    if old_session is not None:
                        old_session.stop()
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
                live = self._get_session(chat_id)
                if live is not None:
                    live.ensure_replaceable()
                if live is not None and live.session_id == session_id:
                    updates, scanned_events = live.history()
                    return {"updates": updates, "scannedEvents": scanned_events, "truncated": False}
                with self._claim_session(chat_id, agent_id, session_id):
                    session, updates, scanned_events, truncated = self._session_type(agent_id).load_recent(agent_id, workspace_path, session_id, limit)
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
            return startup_updates + session.refresh_config_options()

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

    def cancel_prompt(self, chat_id: str) -> None:
        # Cancellation is a notification; acquiring the prompt lock would wait
        # for exactly the operation we are trying to interrupt.
        with self._sessions_guard:
            session = self._sessions.get(chat_id)
            if session is None:
                raise AcpAgentError("Agent session is not ready for cancellation.")
            session.cancel_prompt()

    def release_chat(self, chat_id: str) -> None:
        """Release the idle process, not the provider's persisted conversation."""
        with self._chat_lock(chat_id):
            session = self._get_session(chat_id)
            if session is not None:
                session.stop()
                self._pop_session(chat_id)

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
                with self._claim_session(chat_id, agent_id, session_id):
                    loaded = self._session_type(agent_id).load_for_continue(
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

        created = self._session_type(agent_id).start(agent_id, workspace_path)
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
        self._write_lock = threading.Lock()
        self.permission_callback: PermissionCallback | None = None
        self._pending_updates: list[dict[str, Any]] = []
        self._latest_config_options: list[dict[str, Any]] = []
        self._capabilities: dict[str, Any] | None = None
        self._agent_id = ""
        self._workspace = ""
        self._background_tasks: dict[str, dict[str, str]] = {}
        self._cancellation_inflight = False
        self._connection = AcpConnection(
            output_queue, self._write_json, self._receive_update,
            self._handle_permission_request, AcpAgentError,
        )

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
            if agent_id in {"deepseek-harness", "opencode"}:
                session._resumable = True
        except Exception:
            session.stop()
            raise
        session._capture_config_options(result)
        session._pending_updates = session._pending_updates + updates + session.config_option_updates()
        return session

    @classmethod
    def start_without_session(cls, agent_id: str, workspace_path: str) -> AcpAgentSession:
        workspace = _resolve_workspace(workspace_path)
        command = _agent_command(agent_id, workspace)
        try:
            process = subprocess.Popen(
                command, cwd=str(workspace), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", bufsize=1,
            )
        except OSError as exc:
            raise AcpAgentError(f"Cannot start the ACP adapter: {exc}") from exc
        if process.stdin is None or process.stdout is None:
            process.kill()
            process.wait(timeout=5)
            raise AcpAgentError("Failed to open ACP agent stdio pipes.")

        output_queue: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=512)
        stderr_queue: queue.Queue[str] = queue.Queue(maxsize=32)
        session = cls(process, output_queue, "")
        session._agent_id = agent_id
        session._workspace = str(workspace)
        try:
            _start_json_reader(process.stdout, output_queue, session._connection.closed)
            if process.stderr is not None:
                _start_stderr_reader(process.stderr, stderr_queue)
            result, updates = session._request(
                "initialize",
                {
                    "protocolVersion": 1,
                    "clientCapabilities": ({
                        "elicitation": {"form": {}},
                        "_meta": {"jetbrains": {"air": {"version": 1, "capabilities": ["asyncTasks", "sessionFailure"]}}},
                    } if agent_id == "claude-code" else (
                        {"elicitation": {"form": {}}} if agent_id == "kimi-cli" else {})),
                    "clientInfo": {
                        "name": "agentlink-bridge",
                        "title": "AgentLink Bridge",
                        "version": "0.1.0",
                    },
                },
                timeout_seconds=30,
            )
            if result.get("protocolVersion") != 1 or not isinstance(result.get("agentCapabilities"), dict):
                raise AcpAgentError("The agent did not negotiate ACP protocol version 1.")
            session._capabilities = result["agentCapabilities"]
            if agent_id == "claude-code":
                info = result.get("agentInfo", {})
                if info.get("version") != "0.81.2":
                    raise AcpAgentError("Claude ACP requires validated adapter version 0.81.2. Install @agentclientprotocol/claude-agent-acp@0.81.2.")
                extensions = result.get("_meta", {}).get("jetbrains", {}).get("air", {})
                if "asyncTasks" not in extensions.get("capabilities", []):
                    raise AcpAgentError("Claude ACP did not negotiate background task reporting.")
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
            session._require_capability("loadSession", "History replay")
            session._session_id = session_id
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
        return session, updates + session.config_option_updates()

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
            method = "session/resume" if session._supports_session("resume") else "session/load"
            if method == "session/load":
                session._require_capability("loadSession", "Session recovery")
            session._session_id = session_id
            result, _updates, _scanned_events, truncated = session._request_and_drain(
                method,
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
            if result.get("sessionId", session_id) != session_id:
                raise AcpAgentError("Agent resumed a different session; refusing to replace the current context.")
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
        session._pending_updates = session.config_option_updates()
        return session

    @classmethod
    def load_recent(cls, agent_id: str, workspace_path: str, session_id: str, limit: int) -> tuple[AcpAgentSession, list[dict[str, Any]], int, bool]:
        workspace = _resolve_workspace(workspace_path)
        session = cls.start_without_session(agent_id, workspace_path)
        try:
            session._require_capability("loadSession", "History replay")
            session._session_id = session_id
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
        return session, updates + session.config_option_updates(), scanned_events, truncated

    @property
    def image_input_supported(self) -> bool:
        return (self._capabilities or {}).get("promptCapabilities", {}).get("image") is True

    def prompt(self, prompt: str, update_callback: UpdateCallback | None = None,
               image: ImageInput | None = None) -> list[dict[str, Any]]:
        if image is not None and not self.image_input_supported:
            raise AcpAgentError("This agent did not advertise image input. The image was not sent.")
        self._wait_for_background()
        result, updates = self._request(
            "session/prompt",
            {
                "sessionId": self._session_id,
                "prompt": [{"type": "text", "text": prompt}] + ([image.acp_block()] if image is not None else []),
            },
            timeout_seconds=300,
            update_callback=update_callback,
        )
        self._wait_for_background()
        self._resumable = True
        failure = result.get("_meta", {}).get("jetbrains", {}).get("air", {}).get("sessionFailure", {})
        if failure.get("severity") == "error":
            raise AcpAgentError(str(failure.get("title", "Claude reported a provider failure.")))
        if result.get("stopReason") == "cancelled" or self._connection.cancelled.is_set():
            updates.append({"type": "session/update", "update": {
                "sessionUpdate": "agentlink_prompt_cancelled"}})
        return updates

    def _wait_for_background(self) -> None:
        with self._connection.condition:
            while self._background_tasks or self._connection.permissions or self._connection.dispatching or self._cancellation_inflight:
                if self._connection.fault:
                    raise self._connection.fault
                if self._connection.closed.is_set():
                    raise AcpAgentError("ACP connection closed with outstanding work.")
                self._connection.condition.wait(timeout=1)
            if self._connection.fault:
                raise self._connection.fault

    def cancel_prompt(self) -> None:
        with self._connection.condition:
            if self._cancellation_inflight:
                return
            self._cancellation_inflight = True
        self._connection.cancelled.set()
        try:
            self._write_json({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": self._session_id}})
        except AcpAgentError as exc:
            self._connection.fail(str(exc))
            raise
        def stop_tasks() -> None:
            try:
                with self._connection.request_lock:
                    with self._connection.condition:
                        task_ids = list(self._background_tasks)
                for task_id in task_ids:
                    self._request("_session/async_task/stop", {"sessionId": self._session_id, "asyncTaskId": task_id}, 30,
                                  update_callback=self._connection.sink)
            except AcpAgentError as exc:
                self._connection.fail(str(exc))
            finally:
                with self._connection.condition:
                    self._cancellation_inflight = False
                    self._connection.condition.notify_all()
        threading.Thread(target=stop_tasks, daemon=True).start()

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def history_replay_supported(self) -> bool | None:
        return None if self._capabilities is None else self._capabilities.get("loadSession") is True

    def binding(self, replaced_session_id: str | None = None) -> AcpSessionBinding:
        return AcpSessionBinding(
            session_id=self._session_id,
            resumable=self._resumable,
            replaced_session_id=replaced_session_id,
            history_replay_supported=self.history_replay_supported,
        )

    def take_pending_updates(self) -> list[dict[str, Any]]:
        updates = self._pending_updates
        self._pending_updates = []
        return updates

    def list_sessions(self, workspace_path: str) -> list[dict[str, Any]]:
        if self._capabilities is not None and not self._supports_session("list"):
            raise AcpAgentError("This agent does not support listing saved sessions.")
        params: dict[str, Any] = {}
        if workspace_path.strip() and workspace_path.strip() != "~":
            params["cwd"] = str(_resolve_workspace(workspace_path))
        sessions: dict[str, dict[str, Any]] = {}
        cursors: set[str] = set()
        for _ in range(1000):
            result, _updates = self._request("session/list", params, timeout_seconds=60)
            page = result.get("sessions")
            if not isinstance(page, list) or any(not isinstance(item, dict) or not isinstance(item.get("sessionId"), str) for item in page):
                raise AcpAgentError("Agent returned an invalid session list.")
            for item in page:
                sessions[item["sessionId"]] = item
            cursor = result.get("nextCursor")
            if cursor is None:
                return list(sessions.values())
            if not isinstance(cursor, str) or not cursor or cursor in cursors or len(sessions) > 100_000:
                raise AcpAgentError("Agent returned invalid or excessive session pagination.")
            cursors.add(cursor)
            params["cursor"] = cursor
        raise AcpAgentError("Agent session pagination exceeded the safety limit.")

    def set_config_option(self, config_id: str, value: str) -> list[dict[str, Any]]:
        self.ensure_replaceable()
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

    def refresh_config_options(self) -> list[dict[str, Any]]:
        return self.config_option_updates()

    def history(self) -> tuple[list[dict[str, Any]], int]:
        self._require_capability("loadSession", "History replay")
        self.ensure_replaceable()
        result, updates, count, truncated = self._request_and_drain(
            "session/load", {"sessionId": self._session_id, "cwd": self._workspace, "mcpServers": []},
            120, 0.8, 30, SESSION_RESTORE_MAX_EVENTS,
        )
        if truncated:
            raise AcpAgentError("Session history replay is incomplete.")
        self._capture_config_options(result)
        return updates + self.config_option_updates(), count

    def ensure_replaceable(self) -> None:
        with self._connection.condition:
            if self._background_tasks or self._connection.permissions:
                raise AcpAgentError("Background work or an interaction is active; this session cannot be changed.")

    def _supports_session(self, capability: str) -> bool:
        return capability in (self._capabilities or {}).get("sessionCapabilities", {})

    def _require_capability(self, capability: str, operation: str) -> None:
        if self._capabilities is not None and self._capabilities.get(capability) is not True:
            raise AcpAgentError(f"{operation} is not provided by this agent. Existing local history has not been replaced.")

    def _receive_update(self, params: dict[str, Any]) -> list[dict[str, Any]]:
        update = params["update"].copy()
        session_id = params.get("sessionId")
        if self._session_id and session_id and session_id != self._session_id:
            # Native child sessions were not negotiated. Do not mix their output.
            raise AcpAgentError("Agent sent an update for an unowned session.")
        kind = update.get("sessionUpdate", "")
        parent = update.get("_meta", {}).get("claudeCode", {}).get("parentToolUseId")
        if self._agent_id == "qwen-code":
            parent = update.get("_meta", {}).get("parentToolCallId")
        if parent and kind == "agent_message_chunk":
            update["sessionUpdate"] = "agent_thought_chunk"
            update["messageId"] = "subagent:" + str(parent)
        if kind == "config_option_update":
            self._capture_config_options(update)
        failure = update.get("_meta", {}).get("jetbrains", {}).get("air", {}).get("sessionFailure")
        if isinstance(failure, dict):
            return [{"type": "session/update", "update": {
                "sessionUpdate": "tool_call_update", "toolCallId": "provider:" + str(failure.get("id", "failure")),
                "title": failure.get("title", "Provider notice"),
                "kind": "other", "status": "failed" if failure.get("severity") == "error" else "completed",
                "rawOutput": failure.get("details", failure.get("reason", "")),
            }}]
        if kind.startswith("async_task_"):
            identity = update.get("asyncTaskId")
            if not isinstance(identity, str) or not identity:
                raise AcpAgentError("Invalid background task identity.")
            with self._connection.condition:
                task = self._background_tasks.get(identity, {"id": identity, "name": "Background task", "state": "running"})
                task = {**task, **{key: update[key] for key in ("name", "state") if isinstance(update.get(key), str)}}
                if kind == "async_task_spawned" or (kind == "async_task_state_update" and task["state"] in {"running", "paused"}):
                    if identity not in self._background_tasks and len(self._background_tasks) >= 512:
                        raise AcpAgentError("Agent exceeded the background-task safety limit.")
                    self._background_tasks[identity] = task
                elif kind == "async_task_state_update":
                    self._background_tasks.pop(identity, None)
                tasks = list(self._background_tasks.values())
            return [
                {"type": "session/update", "update": {
                    "sessionUpdate": "tool_call_update", "toolCallId": "background:" + identity,
                    "title": task["name"], "kind": "other",
                    "status": "in_progress" if identity in self._background_tasks else (
                        "failed" if task["state"] == "failed" else "completed"),
                    "rawOutput": update.get("summary", update.get("description", task["state"])),
                }},
                {"type": "session/update", "update": {"sessionUpdate": "agentlink_background_tasks", "tasks": tasks}},
            ]
        return [{"type": "session/update", "update": update}]

    def _capture_config_options(self, result: dict[str, Any]) -> None:
        config_options = result.get("configOptions")
        if isinstance(config_options, list):
            self._latest_config_options = [option.copy() for option in config_options if isinstance(option, dict)]

    def stop(self) -> None:
        self._connection.close()
        if self._process.poll() is None:
            if self._agent_id in {"claude-code", "kimi-cli", "qwen-code", "deepseek-harness", "opencode"} and self._process.stdin is not None:
                try:
                    self._process.stdin.close()
                    self._process.wait(timeout=5)
                    return
                except (OSError, subprocess.TimeoutExpired):
                    logging.getLogger(__name__).warning("%s ACP did not close cleanly; terminating its process.", self._agent_id)
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
        response, updates, _, _ = self._connection.request(method, params, timeout_seconds, update_callback)
        if "error" in response:
            raise _request_error(method, response["error"])
        result = response.get("result")
        if not isinstance(result, dict):
            raise AcpAgentError(f"Invalid ACP response to {method}.")
        return result, updates

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
        response, updates, count, truncated = self._connection.request(
            method, params, timeout_seconds, drain_idle=drain_idle_seconds,
            drain_timeout=drain_timeout_seconds, max_updates=max_updates, collect=collect_updates,
        )
        if "error" in response:
            raise _request_error(method, response["error"])
        result = response.get("result")
        if not isinstance(result, dict):
            raise AcpAgentError(f"Invalid ACP response to {method}.")
        return result, updates, count, truncated

    def _handle_permission_request(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        if self._session_id and params.get("sessionId") and params["sessionId"] != self._session_id:
            raise AcpAgentError("Agent requested interaction for an unowned session.")
        tool = params.get("toolCall", {})
        metadata = tool.get("_meta", {}) if isinstance(tool, dict) else {}
        if self._agent_id == "qwen-code" and isinstance(metadata, dict) and metadata.get("qwenInteractionKind") == "user_question":
            from .elicitation import qwen_question_form, validate_answers
            try:
                form = qwen_question_form(params)
                question = {**message, "method": "elicitation/create", "params": form}
                answer = self.permission_callback(question) if self.permission_callback else {"action": "cancel"}
                result: dict[str, Any] = {"outcome": {"outcome": "cancelled"}}
                if isinstance(answer, dict) and answer.get("action") == "accept" and not self._connection.cancelled.is_set():
                    content = validate_answers(form["requestedSchema"], answer.get("content"))
                    result = {"outcome": {"outcome": "selected", "optionId": "proceed_once"}, "answers": content}
                if not self._connection.closed.is_set():
                    self._write_json({"jsonrpc": "2.0", "id": request_id, "result": result})
            except ValueError as exc:
                logging.getLogger(__name__).warning("Unsupported Qwen question: %s", exc)
                self._write_json({"jsonrpc": "2.0", "id": request_id,
                                  "error": {"code": -32602, "message": str(exc)}})
            return
        if message.get("method") == "elicitation/create":
            from .elicitation import validate_form
            try:
                validate_form(params)
                answer = self.permission_callback(message) if self.permission_callback else {"action": "cancel"}
                if not isinstance(answer, dict):
                    raise ValueError("Invalid question response.")
                if self._connection.cancelled.is_set():
                    answer = {"action": "cancel"}
                if not self._connection.closed.is_set():
                    self._write_json({"jsonrpc": "2.0", "id": request_id, "result": answer})
            except ValueError as exc:
                logging.getLogger(__name__).warning("Unsupported ACP question: %s", exc)
                self._write_json({"jsonrpc": "2.0", "id": request_id,
                                  "error": {"code": -32602, "message": str(exc)}})
            return
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
        if self._connection.cancelled.is_set():
            outcome = {"outcome": "cancelled"}
        if not self._connection.closed.is_set():
            self._write_json({"jsonrpc": "2.0", "id": request_id, "result": {"outcome": outcome}})

    def _write_json(self, message: dict[str, Any]) -> None:
        with self._write_lock:
            if self._process.stdin is None:
                raise AcpAgentError("ACP agent stdin is closed.")
            try:
                self._process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
                self._process.stdin.flush()
            except (OSError, ValueError) as exc:
                raise AcpAgentError("ACP input stream closed; execution is not confirmed.") from exc


def _agent_command(agent_id: str, workspace: Path) -> list[str]:
    if agent_id == "copilot-cli":
        copilot = shutil.which("copilot")
        if not copilot:
            raise AcpAgentError("GitHub Copilot CLI is not installed or not on PATH.")
        return [copilot, "--acp", "--allow-all", "--add-dir", str(workspace)]
    spec = AGENT_SPECS.get(agent_id)
    if spec is None or spec.package is None:
        raise AcpAgentError(f"Unsupported agent: {agent_id}")
    executable = shutil.which(spec.command)
    if not executable:
        raise AcpAgentError(spec.requirements)
    command = _npm_command(executable, spec.command, spec.package)
    if agent_id == "deepseek-harness":
        # DSH's ACP agentInfo version is a protocol implementation version,
        # not the published CLI package version.
        try:
            version = subprocess.run(command + ["--version"], cwd=workspace, capture_output=True,
                                     text=True, encoding="utf-8", errors="replace", timeout=15)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise AcpAgentError("Cannot check DeepSeek Harness version. Install @deepseek-ai/dsh@0.1.7-rc.2.") from exc
        if version.returncode != 0 or version.stdout.strip() != "0.1.7-rc.2":
            raise AcpAgentError("DeepSeek Harness requires baseline @deepseek-ai/dsh@0.1.7-rc.2; preview upgrades require compatibility validation.")
    return command + list(spec.arguments)


def _npm_command(executable: str, command: str, package: str) -> list[str]:
    if Path(executable).suffix.lower() not in {".cmd", ".bat", ".ps1"}:
        return [executable]
    # Resolve only the installed package's declared JS entry point. Neither npm
    # shim text nor workspace paths are evaluated by a command shell.
    node = shutil.which("node")
    parent = Path(executable).parent
    for root in (parent / "node_modules" / package, parent.parent / package):
        manifest = root / "package.json"
        if not manifest.is_file():
            continue
        try:
            info = json.loads(manifest.read_text(encoding="utf-8"))
            binary = info.get("bin") if isinstance(info, dict) else None
            relative = binary.get(command) if isinstance(binary, dict) else binary
            if not isinstance(relative, str) or not relative:
                raise ValueError("Missing bin entry")
            entry = (root / relative).resolve()
            if not node or not entry.is_relative_to(root.resolve()) or not entry.is_file():
                raise ValueError("Invalid JS entry point")
            # OpenCode publishes a Node launcher without a filename extension.
            if entry.suffix not in {".js", ".mjs", ".cjs"}:
                if not (package == "opencode-ai" and command == "opencode" and relative in {"bin/opencode", "./bin/opencode"}):
                    raise ValueError("Invalid JS entry point")
                with entry.open(encoding="utf-8") as launcher:
                    if launcher.readline(128).strip() != "#!/usr/bin/env node":
                        raise ValueError("Invalid OpenCode Node launcher")
        except (OSError, ValueError) as exc:
            raise AcpAgentError(f"Cannot resolve the installed {command} npm launcher. Reinstall {package} and Node.js.") from exc
        return [node, str(entry)]
    raise AcpAgentError(f"Cannot resolve the installed {command} npm launcher. Reinstall {package} and Node.js.")


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
    if method in {"session/load", "session/resume"} and isinstance(error, dict) and error.get("code") == -32002:
        return AcpSessionNotFoundError(f"ACP {method} failed: {error}")
    return AcpAgentError(f"ACP {method} failed: {error}")


def _start_json_reader(
    stream: Any, output_queue: queue.Queue[dict[str, Any]], closed: threading.Event | None = None,
) -> None:
    def enqueue(message: dict[str, Any]) -> None:
        while closed is None or not closed.is_set():
            try:
                output_queue.put(message, timeout=0.1)
                return
            except queue.Full:
                continue

    def read() -> None:
        try:
            for line in stream:
                if closed is not None and closed.is_set():
                    return
                stripped = line.strip()
                if stripped:
                    enqueue(json.loads(stripped))
        except (OSError, ValueError) as exc:
            enqueue({"_transportError": f"ACP stream failed: {type(exc).__name__}"})
        finally:
            stream.close()
            enqueue({"_transportError": "ACP process exited; completion is not confirmed."})

    threading.Thread(target=read, daemon=True).start()


def _start_stderr_reader(stream: Any, stderr_queue: queue.Queue[str]) -> None:
    def read() -> None:
        try:
            for line in stream:
                # Bound diagnostics; never publish stderr (which can contain secrets)
                # into a chat merely because the provider wrote a log line.
                if stderr_queue.full():
                    stderr_queue.get_nowait()
                stderr_queue.put_nowait(line.rstrip("\n")[-4096:])
        finally:
            stream.close()

    threading.Thread(target=read, daemon=True).start()


SESSION_RESTORE_MAX_EVENTS = 100_000
