"""Native Copilot lifecycle, projected onto AgentLink's existing display protocol."""
from __future__ import annotations

import asyncio
import concurrent.futures
import logging
import queue
import shutil
import threading
from typing import Any

from .acp_agent import (
    SESSION_RESTORE_MAX_EVENTS, AcpAgentError, AcpAgentSession, AcpSessionNotFoundError, UpdateCallback, _resolve_workspace,
)


class CopilotEventProjection:
    def __init__(self) -> None:
        self._streamed: dict[tuple[str, str], int] = {}
        self._tool_output: dict[str, str] = {}

    def updates(self, event: dict[str, Any], *, history: bool = False) -> list[dict[str, Any]]:
        kind, data = event["type"], event.get("data", {})
        update: dict[str, Any] | None = None
        parent = data.get("parentToolCallId") or event.get("agentId")
        if kind in {"assistant.message_delta", "assistant.message", "assistant.reasoning_delta", "assistant.reasoning"}:
            # A child reply is activity, not the parent's answer/completion preview.
            thought = "reasoning" in kind or bool(parent)
            identity = str(data.get("messageId") or data.get("reasoningId") or event.get("id", ""))
            key = ("thought" if thought else "message", identity)
            delta = kind.endswith("_delta")
            if history and delta:
                return []
            text = str(data.get("deltaContent" if delta else "content", data.get("reasoningText", "")))
            if delta:
                self._streamed[key] = self._streamed.get(key, 0) + len(text)
            else:
                text = text[self._streamed.pop(key, 0):]
            if text:
                update = {"sessionUpdate": "agent_thought_chunk" if thought else "agent_message_chunk",
                          "messageId": identity, "content": {"type": "text", "text": text}}
        elif kind == "user.message" and history and not parent and data.get("source", "user") == "user":
            update = {"sessionUpdate": "user_message_chunk", "messageId": str(event["id"]),
                      "content": {"type": "text", "text": data.get("content", "")}}
        elif kind == "tool.execution_start":
            update = {"sessionUpdate": "tool_call", "toolCallId": data["toolCallId"],
                      "title": data["toolName"], "kind": "other", "status": "in_progress",
                      "rawInput": data.get("arguments")}
        elif kind == "tool.execution_complete":
            self._tool_output.pop(data["toolCallId"], None)
            update = {"sessionUpdate": "tool_call_update", "toolCallId": data["toolCallId"],
                      "status": "completed" if data["success"] else "failed",
                      "rawOutput": data.get("result") if data["success"] else data.get("error")}
        elif kind == "tool.execution_partial_result":
            identity = data["toolCallId"]
            output = self._tool_output.get(identity, "") + data.get("partialOutput", "")
            if len(output) > 128 * 1024:
                output = "[Earlier streaming output omitted]\n" + output[-128 * 1024:]
            self._tool_output[identity] = output
            update = {"sessionUpdate": "tool_call_update", "toolCallId": data["toolCallId"],
                      "status": "in_progress", "rawOutput": output}
        elif kind in {"subagent.started", "subagent.completed", "subagent.failed"}:
            status = "in_progress" if kind.endswith("started") else (
                "failed" if kind.endswith("failed") or data.get("cancelled") else "completed")
            update = {"sessionUpdate": "tool_call_update", "toolCallId": "agent:" + data["toolCallId"],
                      "title": data.get("agentDisplayName", "Background agent"), "kind": "other", "status": status}
        if update is not None:
            return [{"type": "session/update", "update": update}]
        return []


class CopilotAgentSession(AcpAgentSession):
    """One native client/session, one permanent ordered event consumer.

    The SDK reader never waits for UI persistence or human permission decisions.
    The prompt waiter only observes completion after earlier events were delivered.
    """

    HEARTBEAT_SECONDS = 5

    def __init__(self, workspace_path: str) -> None:
        self._workspace = str(_resolve_workspace(workspace_path))
        self._session_id = ""
        self._resumable = False
        self.permission_callback = None
        self._latest_config_options: list[dict[str, Any]] = []
        self._pending_updates: list[dict[str, Any]] = []
        self._condition = threading.Condition()
        self._active = False
        self._started = False
        self._idle = False
        self._cancelled = False
        self._fault: AcpAgentError | None = None
        self._closed = False
        self._sink: UpdateCallback | None = None
        self._projection = CopilotEventProjection()
        self._events: queue.Queue[dict[str, Any] | None] = queue.Queue(maxsize=512)
        self._loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self._loop_thread.start()
        self._event_thread = threading.Thread(target=self._dispatch_events, daemon=True)
        self._event_thread.start()
        self._client = None
        self._native = None
        try:
            self._run(self._connect())
        except Exception:
            self.stop()
            raise

    async def _connect(self) -> None:
        from copilot import CopilotClient, RuntimeConnection

        executable = shutil.which("copilot")
        if not executable:
            raise AcpAgentError("GitHub Copilot CLI is not installed or not on PATH.")
        self._client = CopilotClient(
            connection=RuntimeConnection.for_stdio(path=executable, args=["--no-auto-update"]),
            working_directory=self._workspace, log_level="error", mode="copilot-cli",
        )
        await self._client.start()

    def _run(self, coroutine: Any, timeout: float = 60) -> Any:
        future = asyncio.run_coroutine_threadsafe(coroutine, self._loop)
        try:
            return future.result(timeout)
        except concurrent.futures.TimeoutError as exc:
            future.cancel()
            raise AcpAgentError("Copilot SDK request timed out; execution may still be active.") from exc
        except AcpAgentError:
            raise
        except Exception as exc:
            raise AcpAgentError(f"Copilot SDK request failed: {exc}") from exc

    async def _open(self, session_id: str | None = None) -> None:
        options = dict(
            working_directory=self._workspace, streaming=True, include_sub_agent_streaming_events=True,
            on_permission_request=self._permission, on_event=self._receive,
        )
        if session_id is None:
            self._native = await self._client.create_session(**options)
        else:
            known = await self._client.list_sessions()
            if not any(item.session_id == session_id for item in known):
                raise AcpSessionNotFoundError(f"Copilot session was not found: {session_id}")
            # Loading history must not silently restart interrupted work.
            self._native = await self._client.resume_session(session_id, continue_pending_work=False, **options)
        self._session_id = self._native.session_id
        await self._refresh_options()

    def _receive(self, event: Any) -> None:
        try:
            self._events.put_nowait(event.to_dict())
        except queue.Full:
            logging.getLogger(__name__).error("Copilot event queue overflow; completion is not confirmed")
            with self._condition:
                self._fault = AcpAgentError("Copilot event queue overflow; history is incomplete and completion is not confirmed.")
                self._condition.notify_all()

    async def _permission(self, request: Any, invocation: Any) -> Any:
        from copilot.generated.rpc import PermissionDecisionApproveOnce, PermissionDecisionReject

        callback = self.permission_callback
        if callback is None:
            return PermissionDecisionReject(feedback="No AgentLink approval channel is available.")
        details = request.to_dict()
        params = {
            "toolCall": {"toolCallId": details.get("toolCallId") or details.get("requestId", "permission"),
                         "kind": details.get("kind", "other"),
                         "title": "Copilot requests permission", "rawInput": details},
            "options": [{"optionId": "allow-once", "kind": "allow_once", "name": "Allow once"},
                        {"optionId": "reject-once", "kind": "reject_once", "name": "Reject"}],
        }
        loop = asyncio.get_running_loop()
        response = loop.create_future()

        def deliver(value: str | None, error: Exception | None) -> None:
            if not response.done():
                if error is not None:
                    response.set_exception(error)
                else:
                    response.set_result(value)

        def decide() -> None:
            value, error = None, None
            try:
                value = callback({"params": params})
            except Exception as exc:
                error = exc
            try:
                loop.call_soon_threadsafe(deliver, value, error)
            except RuntimeError:
                logging.getLogger(__name__).info("Permission decision arrived after Copilot session shutdown")

        # A human approval can outlive a cancelled session; it must not prevent
        # the bridge process from exiting like a default-executor worker would.
        threading.Thread(target=decide, daemon=True).start()
        decision = await response
        return PermissionDecisionApproveOnce(approved_interactively=True) if decision == "allow-once" else PermissionDecisionReject()

    def _dispatch_events(self) -> None:
        while True:
            event = self._events.get()
            if event is None:
                return
            try:
                with self._condition:
                    if self._closed:
                        return
                    active = self._active
                    sink = self._sink
                data, kind = event.get("data", {}), event["type"]
                if kind == "agentlink.begin":
                    with self._condition:
                        self._active = True
                        self._started = self._idle = self._cancelled = False
                        self._sink = event["sink"]
                    event["ready"].set()
                    continue
                root = not (event.get("agentId") or data.get("parentToolCallId"))
                updates = self._projection.updates(event)
                if root and kind == "session.model_change":
                    for option in self._latest_config_options:
                        if option["id"] == "model":
                            option["currentValue"] = data["newModel"]
                    updates.extend(self.config_option_updates())
                for update in updates:
                    if sink is not None:
                        sink(update)
                    elif active:
                        with self._condition:
                            self._pending_updates.append(update)
                with self._condition:
                    if active and root and kind in {"user.message", "assistant.turn_start"}:
                        self._started = True
                    if root and kind == "session.error":
                        self._fault = AcpAgentError("Copilot session failed: " + str(data.get("message", "unknown error")))
                    elif root and kind == "session.shutdown" and active:
                        self._fault = AcpAgentError("Copilot session shut down before completing its work.")
                    elif root and kind == "session.idle" and active and self._started and data.get("mode") != "autopilot":
                        self._cancelled = bool(data.get("aborted")) or self._cancelled
                        self._idle = True
                        self._projection = CopilotEventProjection()
                    self._condition.notify_all()
            except Exception as exc:
                logging.getLogger(__name__).error("Copilot event delivery failed (%s)", type(exc).__name__)
                with self._condition:
                    self._fault = AcpAgentError("Copilot event delivery failed; completion is not confirmed.")
                    self._condition.notify_all()

    def prompt(self, prompt: str, update_callback: UpdateCallback | None = None) -> list[dict[str, Any]]:
        with self._condition:
            if self._closed or self._fault:
                raise self._fault or AcpAgentError("Copilot session is closed.")
            if self._active:
                raise AcpAgentError("Copilot session already has an active operation.")
        try:
            ready = threading.Event()
            try:
                self._events.put({"type": "agentlink.begin", "sink": update_callback, "ready": ready}, timeout=10)
            except queue.Full as exc:
                raise AcpAgentError("Copilot event delivery is blocked; prompt was not sent.") from exc
            if not ready.wait(30):
                raise AcpAgentError("Copilot event delivery is blocked; prompt was not sent.")
            with self._condition:
                if self._fault or self._closed:
                    raise self._fault or AcpAgentError("Copilot session closed before sending the prompt.")
            self._run(self._native.send(prompt))
            while True:
                with self._condition:
                    self._condition.wait_for(
                        lambda: self._idle or self._fault or self._closed, timeout=self.HEARTBEAT_SECONDS)
                    if self._fault or self._closed:
                        raise self._fault or AcpAgentError("Copilot session closed during execution.")
                    if self._idle:
                        self._resumable = True
                        updates = self.take_pending_updates()
                        if self._cancelled:
                            updates.append({"type": "session/update", "update": {
                                "sessionUpdate": "agentlink_prompt_cancelled"}})
                        return updates
                # Detect transport loss without imposing a model/tool duration limit.
                self._run(self._client.ping("agentlink"), timeout=10)
        except AcpAgentError as exc:
            with self._condition:
                self._fault = exc
            raise
        finally:
            with self._condition:
                self._active = False

    def cancel_prompt(self) -> None:
        self._run(self._native.abort())

    async def _refresh_options(self) -> None:
        models = await self._client.list_models()
        current = await self._native.rpc.model.get_current()
        mode = await self._native.rpc.permissions.get_mode()
        self._latest_config_options = [
            {"id": "model", "name": "Model", "category": "model", "type": "select",
             "currentValue": current.model_id or "auto",
             "options": [{"value": item.id, "name": item.name} for item in models]},
            {"id": "allow_all", "name": "Allow all", "category": "permissions", "type": "select",
             "currentValue": "true" if mode.mode.value == "allow-all" else "false",
             "options": [{"value": "false", "name": "Request approval"},
                         {"value": "true", "name": "Allow all"}]},
        ]

    def set_config_option(self, config_id: str, value: str) -> list[dict[str, Any]]:
        self._require_idle()
        async def change() -> None:
            from copilot.generated.rpc import PermissionsSetApproveAllRequest
            if config_id == "model":
                await self._native.set_model(value)
            elif config_id == "allow_all" and value in {"true", "false"}:
                result = await self._native.rpc.permissions.set_approve_all(PermissionsSetApproveAllRequest(enabled=value == "true"))
                if not result.success:
                    raise AcpAgentError("Copilot did not accept the requested permission setting.")
            else:
                raise AcpAgentError("Unsupported Copilot configuration option or value.")
            await self._refresh_options()
        self._run(change())
        return self.config_option_updates()

    def refresh_config_options(self) -> list[dict[str, Any]]:
        self._require_idle()
        self._run(self._refresh_options())
        return self.config_option_updates()

    def list_sessions(self, workspace_path: str) -> list[dict[str, Any]]:
        from copilot.client import SessionListFilter

        session_filter = None
        if workspace_path.strip() and workspace_path.strip() != "~":
            session_filter = SessionListFilter(working_directory=str(_resolve_workspace(workspace_path)))
        sessions = self._run(self._client.list_sessions(session_filter))
        return [{"sessionId": s.session_id, "title": s.summary,
                 "cwd": s.context.working_directory if s.context is not None else None,
                 "updatedAt": s.modified_time.isoformat()} for s in sessions if not s.is_remote]

    def _require_idle(self) -> None:
        with self._condition:
            if self._active or self._fault or self._closed:
                raise self._fault or AcpAgentError("Copilot is running or closed; session changes are not safe.")

    def history(self) -> tuple[list[dict[str, Any]], int]:
        self._require_idle()
        events = self._run(self._native.get_events())
        if len(events) > SESSION_RESTORE_MAX_EVENTS:
            raise AcpAgentError("Copilot history exceeds the recovery safety limit.")
        projection = CopilotEventProjection()
        return ([update for event in events for update in projection.updates(event.to_dict(), history=True)], len(events))

    @classmethod
    def start_without_session(cls, agent_id: str, workspace_path: str) -> CopilotAgentSession:
        return cls(workspace_path)

    @classmethod
    def start(cls, agent_id: str, workspace_path: str) -> CopilotAgentSession:
        session = cls(workspace_path)
        try:
            session._run(session._open())
            session._pending_updates = session.config_option_updates()
            return session
        except Exception:
            session.stop()
            raise

    @classmethod
    def load_for_continue(cls, agent_id: str, workspace_path: str, session_id: str, resumable: bool) -> CopilotAgentSession:
        session = cls(workspace_path)
        try:
            session._run(session._open(session_id))
            session._resumable = resumable
            session._pending_updates = session.config_option_updates()
            return session
        except Exception:
            session.stop()
            raise

    @classmethod
    def load(cls, agent_id: str, workspace_path: str, session_id: str) -> tuple[CopilotAgentSession, list[dict[str, Any]]]:
        session = cls.load_for_continue(agent_id, workspace_path, session_id, True)
        try:
            updates, _ = session.history()
            return session, updates
        except Exception:
            session.stop()
            raise

    @classmethod
    def load_recent(cls, agent_id: str, workspace_path: str, session_id: str, limit: int) -> tuple[CopilotAgentSession, list[dict[str, Any]], int, bool]:
        session = cls.load_for_continue(agent_id, workspace_path, session_id, True)
        try:
            updates, scanned = session.history()
            return session, updates, scanned, False
        except Exception:
            session.stop()
            raise

    def stop(self) -> None:
        with self._condition:
            if self._closed:
                return
            self._closed = True
            self._condition.notify_all()
        try:
            self._events.put_nowait(None)
        except queue.Full:
            # A queued event will wake the consumer, which observes _closed.
            pass
        try:
            if self._client is not None:
                self._run(self._client.stop(), timeout=15)
        finally:
            self._loop.call_soon_threadsafe(self._loop.stop)
            self._loop_thread.join(timeout=5)
            self._event_thread.join(timeout=5)
            if not self._loop_thread.is_alive():
                self._loop.close()
