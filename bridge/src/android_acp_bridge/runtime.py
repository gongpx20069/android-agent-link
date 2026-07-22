from __future__ import annotations

import secrets
import threading
import json
import time
from collections import deque
from dataclasses import dataclass
from typing import Any, Callable, Protocol

from . import __version__
from .acp_agent import AcpAgentError, AcpAgentManager, AcpPromptRequest, AcpSessionBinding
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


@dataclass
class PendingApproval:
    options: list[dict[str, Any]]
    condition: threading.Condition
    decision: str | None = None


@dataclass
class AgentChunkLogBuffer:
    text: str = ""
    suppressed: bool = False


@dataclass
class PromptOperation:
    chat_id: str
    operation_id: str
    agent_id: str
    workspace_path: str
    content: str
    session_id: str | None
    session_resumable: bool
    emit: Callable[[dict[str, Any]], None] | None
    responses: list[dict[str, Any]]
    completed: threading.Event
    state: str = "queued"
    waiters: list[Callable[[dict[str, Any]], None]] | None = None
    batch_members: list["PromptOperation"] | None = None


class AgentManager(Protocol):
    def prompt(
        self,
        request: AcpPromptRequest,
        permission_callback: Callable[[dict[str, Any]], str] | None = None,
        update_callback: Callable[[dict[str, Any]], None] | None = None,
        session_callback: Callable[[AcpSessionBinding], None] | None = None,
    ) -> list[dict[str, Any]]:
        ...

    def list_sessions(self, agent_id: str, workspace_path: str) -> list[dict[str, Any]]:
        ...

    def load_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str) -> list[dict[str, Any]]:
        ...

    def load_recent_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str, limit: int) -> dict[str, Any]:
        ...

    def restore_session(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str,
        session_resumable: bool,
        session_callback: Callable[[AcpSessionBinding], None],
    ) -> None:
        ...

    def refresh_config_options(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str | None = None,
        session_resumable: bool = False,
        session_callback: Callable[[AcpSessionBinding], None] | None = None,
    ) -> list[dict[str, Any]]:
        ...

    def set_config_option(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        config_id: str,
        value: str,
        session_id: str | None = None,
        session_resumable: bool = False,
        session_callback: Callable[[AcpSessionBinding], None] | None = None,
    ) -> list[dict[str, Any]]:
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
        self._pending_approvals: dict[str, PendingApproval] = {}
        self._approval_lock = threading.Lock()
        self._agent_chunk_logs: dict[str, AgentChunkLogBuffer] = {}
        self._prompt_queues: dict[str, deque[PromptOperation]] = {}
        self._active_prompts: dict[str, PromptOperation] = {}
        self._prompt_operations: dict[tuple[str, str], PromptOperation] = {}
        self._pre_cancelled_prompt_ids: set[tuple[str, str]] = set()
        self._cancelled_prompt_ids: dict[tuple[str, str], None] = {}
        self._prompt_lock = threading.RLock()
        self._chat_emitters: dict[str, Callable[[dict[str, Any]], None]] = {}
        self._event_logs: dict[str, list[dict[str, Any]]] = {}
        self._next_event_ids: dict[str, int] = {}
        self._event_generation = secrets.token_hex(16)
        self._chat_status: dict[str, str] = {}
        self._event_lock = threading.RLock()

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

    def websocket_responses(self, payload: Any, emit: Callable[[dict[str, Any]], None] | None = None) -> list[dict[str, Any]]:
        if not isinstance(payload, dict):
            responses = [{"type": "bridge.echo", "payload": payload}, {"type": "bridge.done"}]
            self._log_responses(responses)
            return responses

        message_type = payload.get("type")
        if message_type == "chat.prompt":
            self._log_client_prompt(payload)

        def logging_emit(response: dict[str, Any]) -> None:
            self._log_response(response)
            if emit is not None:
                emit(response)
        if emit is not None:
            setattr(logging_emit, "_connection_id", getattr(emit, "_connection_id", id(emit)))

        if message_type == "chat.attach":
            responses = self._chat_attach_response(payload, logging_emit if emit is not None else None)
            self._log_responses(responses)
            return responses
        if message_type == "chat.prompt":
            responses = self._chat_prompt_updates(payload, logging_emit if emit is not None else None)
            self._log_responses(responses)
            return responses
        if message_type == "chat.prompt.remove":
            responses = self._remove_queued_prompt(payload, logging_emit if emit is not None else None)
            self._log_responses(responses)
            return responses
        if message_type == "session.list":
            responses = self._session_list_response(payload)
            self._log_responses(responses)
            return responses
        if message_type == "session.load":
            responses = self._session_load_response(payload)
            self._log_responses(responses)
            return responses
        if message_type == "session.loadRecent":
            responses = self._session_load_recent_response(payload)
            self._log_responses(responses)
            return responses
        if message_type == "session.refreshConfigOptions":
            responses = self._session_refresh_config_options_response(payload)
            self._log_responses(responses)
            return responses
        if message_type == "session.setConfigOption":
            responses = self._session_set_config_option_response(payload)
            self._log_responses(responses)
            return responses
        if message_type == "approval.decide":
            responses = self._approval_decision_updates(payload)
            self._log_responses(responses)
            return responses
        responses = [{"type": "bridge.echo", "payload": payload}, {"type": "bridge.done"}]
        self._log_responses(responses)
        return responses

    def confirm_pairing(self, device: DeviceInfo) -> bool:
        if not self.require_local_pairing_confirmation:
            return True

        prompt = f"Allow {device.name} ({device.platform}) to pair with this machine? [y/N] "
        try:
            answer = input(prompt)
        except EOFError:
            return False
        return answer.strip().lower() in {"y", "yes"}

    def _chat_prompt_updates(self, payload: dict[str, Any], emit: Callable[[dict[str, Any]], None] | None) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        operation_id = _string_or_default(payload.get("operationId"), "op_" + secrets.token_urlsafe(8))
        prompt = _string_or_default(payload.get("content"), "")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        session_id = _optional_string(payload.get("sessionId"))
        session_resumable = _bool_or_default(payload.get("sessionResumable"), False)
        responses: list[dict[str, Any]] = []
        operation = PromptOperation(
            chat_id=chat_id,
            operation_id=operation_id,
            agent_id=agent_id,
            workspace_path=workspace_path,
            content=prompt,
            session_id=session_id,
            session_resumable=session_resumable,
            emit=emit,
            responses=responses,
            completed=threading.Event(),
            waiters=[],
        )
        with self._prompt_lock:
            operation_key = (chat_id, operation_id)
            if operation_key in self._pre_cancelled_prompt_ids:
                self._pre_cancelled_prompt_ids.remove(operation_key)
                self._remember_cancelled_prompt_id(operation_key)
            if operation_key in self._cancelled_prompt_ids:
                duplicate = {
                    "type": "operation.accepted",
                    "chatId": chat_id,
                    "operationId": operation_id,
                    "operationType": "chat.prompt",
                    "state": "cancelled",
                    "duplicate": True,
                }
                if emit is None:
                    responses.extend([duplicate, {"type": "bridge.done", "chatId": chat_id}])
                else:
                    emit(duplicate)
                    emit({"type": "bridge.done", "chatId": chat_id})
                return responses
            existing = self._prompt_operations.get(operation_key)
            if existing is not None:
                duplicate = {
                    "type": "operation.accepted",
                    "chatId": chat_id,
                    "operationId": operation_id,
                    "operationType": "chat.prompt",
                    "state": existing.state,
                    "duplicate": True,
                }
                if emit is None:
                    responses.append(duplicate)
                    if existing.completed.is_set():
                        responses.append({"type": "bridge.done", "chatId": chat_id})
                else:
                    if not existing.completed.is_set() and not _same_emitter(emit, [existing.emit] if existing.emit is not None else []):
                        existing.waiters = existing.waiters or []
                        if not _same_emitter(emit, existing.waiters):
                            existing.waiters.append(emit)
                    emit(duplicate)
                    if existing.completed.is_set():
                        emit({"type": "bridge.done", "chatId": chat_id})
                return responses

            queue_for_chat = self._prompt_queues.setdefault(chat_id, deque())
            starts_immediately = chat_id not in self._active_prompts
            queue_position = 0 if starts_immediately else len(queue_for_chat) + 1
            operation.state = "starting" if starts_immediately else "queued"
            self._prompt_operations[(chat_id, operation_id)] = operation
            if starts_immediately:
                self._active_prompts[chat_id] = operation
            else:
                queue_for_chat.append(operation)
            self._publish_prompt_event(
                operation,
                {
                    "type": "operation.accepted",
                    "chatId": chat_id,
                    "operationId": operation_id,
                    "operationType": "chat.prompt",
                    "state": operation.state,
                    "queuePosition": queue_position,
                    "content": prompt,
                },
            )
            active_operation_id = self._active_prompts[chat_id].operation_id
            self._publish_prompt_event(
                operation,
                self._chat_status_event(chat_id, "busy", active_operation_id, queued_count=queue_position),
            )
        if starts_immediately:
            threading.Thread(target=self._run_prompt_queue, args=(chat_id,), daemon=True).start()

        if emit is None:
            operation.completed.wait(timeout=310)
        return responses

    def _run_prompt_queue(self, chat_id: str) -> None:
        while True:
            with self._prompt_lock:
                operation = self._active_prompts.get(chat_id)
                if operation is None:
                    return
                batch = operation.batch_members or [operation]
                for member in batch:
                    member.state = "running"
                    self._publish_prompt_event(
                        member,
                        {
                            "type": "operation.started",
                            "chatId": chat_id,
                            "operationId": member.operation_id,
                            "operationType": "chat.prompt",
                            "content": member.content,
                            "batchSize": len(batch),
                        },
                    )

            try:
                last_binding: AcpSessionBinding | None = None

                def emit_session(binding: AcpSessionBinding) -> None:
                    nonlocal last_binding
                    if binding == last_binding:
                        return
                    last_binding = binding
                    self._publish_prompt_event(operation, self._session_binding_event(chat_id, binding))

                def emit_update(update: dict[str, Any]) -> None:
                    update.setdefault("chatId", chat_id)
                    update.setdefault("operationId", operation.operation_id)
                    self._publish_prompt_event(operation, update)

                updates = self.agent_manager.prompt(
                    AcpPromptRequest(
                        chat_id=chat_id,
                        agent_id=operation.agent_id,
                        workspace_path=operation.workspace_path,
                        prompt="\n\n".join(member.content for member in batch),
                        session_id=operation.session_id,
                        session_resumable=operation.session_resumable,
                    ),
                    permission_callback=lambda message: self._request_permission(
                        chat_id,
                        message,
                        lambda event: self._publish_prompt_event(operation, event),
                    ),
                    update_callback=emit_update if operation.emit is not None or chat_id in self._chat_emitters else None,
                    session_callback=emit_session,
                )
                operation_status = "completed"
            except AcpAgentError as exc:
                updates = [
                    {
                        "type": "session/update",
                        "chatId": chat_id,
                        "operationId": operation.operation_id,
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
                operation_status = "failed"

            for update in updates:
                update.setdefault("chatId", chat_id)
                update.setdefault("operationId", operation.operation_id)
                self._publish_prompt_event(operation, update)

            with self._prompt_lock:
                queue_for_chat = self._prompt_queues.setdefault(chat_id, deque())
                next_batch = list(queue_for_chat)
                queue_for_chat.clear()
                next_operation = next_batch[0] if next_batch else None
                if next_operation is None:
                    self._active_prompts.pop(chat_id, None)
                else:
                    self._active_prompts[chat_id] = next_operation
                    next_operation.batch_members = next_batch
                    for member in next_batch:
                        member.state = "starting"
                for index, member in enumerate(batch):
                    member.state = operation_status
                    queue_remaining = len(batch) - index - 1 + len(next_batch)
                    self._publish_prompt_event(
                        member,
                        {
                            "type": "operation.done",
                            "chatId": chat_id,
                            "operationId": member.operation_id,
                            "operationType": "chat.prompt",
                            "status": operation_status,
                            "queueRemaining": queue_remaining,
                            "batchSize": len(batch),
                        },
                    )
                if next_operation is None:
                    self._publish_prompt_event(operation, self._chat_status_event(chat_id, "idle"))
                else:
                    self._publish_prompt_event(
                        next_operation,
                        self._chat_status_event(
                            chat_id,
                            "busy",
                            next_operation.operation_id,
                            queued_count=max(0, len(next_batch) - 1),
                        ),
                    )
                for member in batch:
                    self._finish_prompt_transport(member)
                    member.completed.set()
                    member.emit = None
                    member.content = ""
                    member.waiters = []
                    member.batch_members = None
                self._evict_prompt_operation_history()

            if next_operation is None:
                return

    def _remove_queued_prompt(
        self,
        payload: dict[str, Any],
        emit: Callable[[dict[str, Any]], None] | None,
    ) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        operation_id = _string_or_default(payload.get("operationId"), "")
        responses: list[dict[str, Any]] = []
        removed: PromptOperation | None = None
        with self._prompt_lock:
            queue_for_chat = self._prompt_queues.setdefault(chat_id, deque())
            operation_key = (chat_id, operation_id)
            existing = self._prompt_operations.get(operation_key)
            pre_cancelled = operation_key in self._pre_cancelled_prompt_ids
            cancelled = operation_key in self._cancelled_prompt_ids
            for operation in queue_for_chat:
                if operation.operation_id == operation_id:
                    removed = operation
                    break
            if removed is not None:
                queue_for_chat.remove(removed)
                removed.state = "cancelled"
                self._remember_cancelled_prompt_id(operation_key)
                cancelled = True
                queue_remaining = len(queue_for_chat)
            else:
                queue_remaining = len(queue_for_chat)
                if existing is None and not pre_cancelled and not cancelled:
                    self._pre_cancelled_prompt_ids.add(operation_key)
                    pre_cancelled = True
                elif existing is not None and existing.state == "cancelled":
                    self._remember_cancelled_prompt_id(operation_key)
                    cancelled = True

            with self._event_lock:
                result = self._append_event(
                    chat_id,
                    {
                        "type": "operation.done",
                        "chatId": chat_id,
                        "operationId": operation_id,
                        "operationType": "chat.prompt",
                        "status": (
                            "cancelled"
                            if (
                                removed is not None
                                or pre_cancelled
                                or cancelled
                            )
                            else "already_started"
                        ),
                        "queueRemaining": queue_remaining,
                    },
                )
                targets: list[Callable[[dict[str, Any]], None]] = []
                chat_emitter = self._chat_emitters.get(chat_id)
                if chat_emitter is not None:
                    targets.append(chat_emitter)
                if removed is not None and removed.emit is not None and not _same_emitter(removed.emit, targets):
                    targets.append(removed.emit)
                if emit is not None and not _same_emitter(emit, targets):
                    targets.append(emit)
                if removed is not None:
                    for waiter in removed.waiters or []:
                        if not _same_emitter(waiter, targets):
                            targets.append(waiter)
                if emit is None:
                    responses.extend([result, {"type": "bridge.done", "chatId": chat_id}])
                for target in targets:
                    target(result)
                if removed is not None and removed.emit is not None:
                    removed.emit({"type": "bridge.done", "chatId": chat_id})
                elif removed is not None:
                    removed.responses.extend([result, {"type": "bridge.done", "chatId": chat_id}])
                if removed is not None:
                    for waiter in removed.waiters or []:
                        if removed.emit is None or not _same_emitter(waiter, [removed.emit]):
                            waiter({"type": "bridge.done", "chatId": chat_id})
                if emit is not None and (removed is None or not _same_emitter(emit, [removed.emit] if removed.emit is not None else [])):
                    emit({"type": "bridge.done", "chatId": chat_id})
                if removed is not None:
                    removed.completed.set()
                    removed.emit = None
                    removed.content = ""
                    removed.waiters = []
                self._evict_prompt_operation_history()
        return responses

    def _publish_prompt_event(self, operation: PromptOperation, event: dict[str, Any]) -> None:
        with self._event_lock:
            if event.get("type") == "chat.status":
                self._chat_status[operation.chat_id] = _string_or_default(event.get("status"), "idle")
            enriched = self._append_event(operation.chat_id, event)
            self._publish_prompt_transport(operation, enriched)

    def _publish_prompt_transport(self, operation: PromptOperation, event: dict[str, Any]) -> None:
        targets: list[Callable[[dict[str, Any]], None]] = []
        chat_emitter = self._chat_emitters.get(operation.chat_id)
        if chat_emitter is not None:
            targets.append(chat_emitter)
        if operation.emit is not None and not _same_emitter(operation.emit, targets):
            targets.append(operation.emit)
        if event.get("type") == "operation.done":
            for waiter in operation.waiters or []:
                if not _same_emitter(waiter, targets):
                    targets.append(waiter)
        if operation.emit is None:
            operation.responses.append(event)
        for target in targets:
            target(event)

    def _finish_prompt_transport(self, operation: PromptOperation) -> None:
        done = {"type": "bridge.done", "chatId": operation.chat_id}
        if operation.emit is None:
            operation.responses.append(done)
        else:
            operation.emit(done)
        for waiter in operation.waiters or []:
            if operation.emit is None or not _same_emitter(waiter, [operation.emit]):
                waiter(done)

    def _remember_cancelled_prompt_id(self, operation_key: tuple[str, str]) -> None:
        self._cancelled_prompt_ids.pop(operation_key, None)
        self._cancelled_prompt_ids[operation_key] = None
        while len(self._cancelled_prompt_ids) > PROMPT_OPERATION_HISTORY_LIMIT:
            self._cancelled_prompt_ids.pop(next(iter(self._cancelled_prompt_ids)))

    def _evict_prompt_operation_history(self) -> None:
        overflow = len(self._prompt_operations) - PROMPT_OPERATION_HISTORY_LIMIT
        if overflow <= 0:
            return
        for key, operation in list(self._prompt_operations.items()):
            if overflow <= 0:
                break
            if operation.state in {"completed", "failed", "cancelled"}:
                self._prompt_operations.pop(key, None)
                overflow -= 1

    def _approval_decision_updates(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        approval_id = _string_or_default(payload.get("approvalId"), "unknown-approval")
        decision = _string_or_default(payload.get("decision"), "unknown")
        resolved = self._resolve_approval(approval_id, decision)
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": approval_id,
                    "title": "Approval decision",
                    "kind": "approval",
                    "status": "completed" if resolved else "failed",
                    "content": {"decision": decision, "resolved": resolved},
                },
            },
            {"type": "bridge.done"},
        ]

    def _session_list_response(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        try:
            sessions = self.agent_manager.list_sessions(agent_id, workspace_path)
            return [{"type": "session.list.result", "sessions": sessions}, {"type": "bridge.done"}]
        except AcpAgentError as exc:
            return [{"type": "session.list.result", "sessions": [], "error": str(exc)}, {"type": "bridge.done"}]

    def _session_load_response(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        session_id = _string_or_default(payload.get("sessionId"), "")
        try:
            updates = self.agent_manager.load_session(chat_id, agent_id, workspace_path, session_id)
            updates.insert(0, self._session_binding_event(chat_id, AcpSessionBinding(session_id, resumable=True)))
        except AcpAgentError as exc:
            updates = [
                {
                    "type": "session/update",
                    "chatId": chat_id,
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "session_load",
                        "title": "Resume session",
                        "kind": "other",
                        "status": "failed",
                        "content": {"error": str(exc)},
                    },
                }
            ]
        for update in updates:
            update.setdefault("chatId", chat_id)
        return updates + [{"type": "bridge.done", "chatId": chat_id}]

    def _session_load_recent_response(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        session_id = _string_or_default(payload.get("sessionId"), "")
        limit = max(1, min(_int_or_default(payload.get("limit"), 50), 200))
        try:
            result = self.agent_manager.load_recent_session(chat_id, agent_id, workspace_path, session_id, limit)
            updates = result.get("updates", [])
            messages = _latest_visible_messages(updates if isinstance(updates, list) else [], limit)
            return [
                self._session_binding_event(chat_id, AcpSessionBinding(session_id, resumable=True)),
                {
                    "type": "session.loadRecent.result",
                    "chatId": chat_id,
                    "sessionId": session_id,
                    "messages": messages,
                    "scannedEvents": _int_or_default(result.get("scannedEvents"), 0),
                    "truncated": bool(result.get("truncated", False)),
                },
                {"type": "bridge.done", "chatId": chat_id},
            ]
        except AcpAgentError as exc:
            return [
                {
                    "type": "session.loadRecent.result",
                    "chatId": chat_id,
                    "sessionId": session_id,
                    "messages": [],
                    "scannedEvents": 0,
                    "truncated": False,
                    "error": str(exc),
                },
                {"type": "bridge.done", "chatId": chat_id},
            ]

    def _request_permission(self, chat_id: str, message: dict[str, Any], emit: Callable[[dict[str, Any]], None] | None) -> str:
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        options = params.get("options") if isinstance(params, dict) and isinstance(params.get("options"), list) else []
        tool_call = params.get("toolCall") if isinstance(params, dict) and isinstance(params.get("toolCall"), dict) else {}
        approval_id = "approval_" + secrets.token_urlsafe(12)
        pending = PendingApproval(options=options, condition=threading.Condition())
        with self._approval_lock:
            self._pending_approvals[approval_id] = pending

        requested = {
            "type": "approval.requested",
            "approvalId": approval_id,
            "chatId": chat_id,
            "action": _string_or_default(tool_call.get("kind"), "tool_permission"),
            "summary": _string_or_default(tool_call.get("title"), "Agent requests permission"),
            "details": tool_call,
            "options": options,
        }
        if emit is not None:
            emit(requested)
            emit(self._chat_status_event(chat_id, "waitingApproval"))

        with pending.condition:
            pending.condition.wait(timeout=300)
            decision = pending.decision or "denied"

        with self._approval_lock:
            self._pending_approvals.pop(approval_id, None)

        if emit is not None:
            with self._prompt_lock:
                active = self._active_prompts.get(chat_id)
                queued_count = len(self._prompt_queues.get(chat_id, ()))
            emit(self._chat_status_event(chat_id, "busy", active.operation_id if active is not None else None, queued_count))
        return _select_permission_option(options, decision)

    def _chat_attach_response(
        self,
        payload: dict[str, Any],
        emit: Callable[[dict[str, Any]], None] | None = None,
    ) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        last_event_id = _int_or_default(payload.get("lastEventId"), 0)
        last_event_generation = _optional_string(payload.get("lastEventGeneration"))
        session_id = _optional_string(payload.get("sessionId"))
        binding_error: AcpAgentError | None = None
        if session_id is not None:
            try:
                self.agent_manager.restore_session(
                    chat_id,
                    _string_or_default(payload.get("agentId"), "copilot-cli"),
                    _string_or_default(payload.get("workspacePath"), ""),
                    session_id,
                    _bool_or_default(payload.get("sessionResumable"), False),
                    lambda binding: self._append_event(chat_id, self._session_binding_event(chat_id, binding)),
                )
            except AcpAgentError as exc:
                binding_error = exc
        with self._prompt_lock:
            active_operation = self._active_prompts.get(chat_id)
            queued_count = len(self._prompt_queues.get(chat_id, ()))
            with self._event_lock:
                event_log = self._event_logs.get(chat_id, [])
                latest_event_id = self._next_event_ids.get(chat_id, 1) - 1
                checkpoint_reset = (
                    (last_event_generation is not None and last_event_generation != self._event_generation)
                    or last_event_id > latest_event_id
                )
                effective_last_event_id = 0 if checkpoint_reset else last_event_id
                oldest_event_id = int(event_log[0].get("eventId", 0)) if event_log else latest_event_id + 1
                replay_truncated = effective_last_event_id < oldest_event_id - 1
                events = [event.copy() for event in event_log if int(event.get("eventId", 0)) > effective_last_event_id]
                session_events: list[dict[str, Any]] = []
                if binding_error is not None:
                    session_events.append(
                        self._append_event(
                            chat_id,
                            {
                                "type": "chat.session.error",
                                "chatId": chat_id,
                                "sessionId": session_id,
                                "error": str(binding_error),
                            },
                        )
                    )
                status = "failed" if binding_error is not None else self._chat_status.get(chat_id, "idle")
                self._chat_status[chat_id] = status
                status_payload: dict[str, Any] = {
                    "type": "chat.status",
                    "chatId": chat_id,
                    "status": status,
                    "queuedCount": queued_count,
                }
                if active_operation is not None:
                    status_payload["operationId"] = active_operation.operation_id
                status_event = self._append_event(chat_id, status_payload)
                status_snapshot = {**status_event, "snapshot": True}
                resync_responses = (
                    [
                        {
                            "type": "chat.resyncRequired",
                            "chatId": chat_id,
                            "latestEventId": latest_event_id,
                            "reason": "event log no longer contains requested history",
                        }
                    ]
                    if replay_truncated
                    else []
                )
                responses = [
                    {
                        "type": "chat.attached",
                        "chatId": chat_id,
                        "latestEventId": latest_event_id,
                        "eventGeneration": self._event_generation,
                        "replayed": len(events),
                        "checkpointReset": checkpoint_reset,
                    },
                    *resync_responses,
                    *events,
                    *session_events,
                    status_snapshot,
                ]
                if emit is not None:
                    self._chat_emitters[chat_id] = emit
                    for response in responses:
                        emit(response)
                    return []
                return responses

    def _resolve_approval(self, approval_id: str, decision: str) -> bool:
        with self._approval_lock:
            pending = self._pending_approvals.get(approval_id)
        if pending is None:
            return False
        with pending.condition:
            pending.decision = decision
            pending.condition.notify_all()
        return True

    def _append_event(self, chat_id: str, event: dict[str, Any]) -> dict[str, Any]:
        with self._event_lock:
            event_id = self._next_event_ids.get(chat_id, 1)
            self._next_event_ids[chat_id] = event_id + 1
            enriched = event.copy()
            enriched.setdefault("chatId", chat_id)
            enriched["eventId"] = event_id
            enriched.setdefault("timestamp", int(time.time() * 1000))
            log = self._event_logs.setdefault(chat_id, [])
            log.append(enriched)
            if len(log) > CHAT_EVENT_LOG_LIMIT:
                del log[: len(log) - CHAT_EVENT_LOG_LIMIT]
            return enriched

    def _chat_status_event(
        self,
        chat_id: str,
        status: str,
        operation_id: str | None = None,
        queued_count: int = 0,
    ) -> dict[str, Any]:
        event: dict[str, Any] = {
            "type": "chat.status",
            "chatId": chat_id,
            "status": status,
            "queuedCount": queued_count,
        }
        if operation_id is not None:
            event["operationId"] = operation_id
        return event

    @staticmethod
    def _session_binding_event(chat_id: str, binding: AcpSessionBinding) -> dict[str, Any]:
        event: dict[str, Any] = {
            "type": "chat.session",
            "chatId": chat_id,
            "sessionId": binding.session_id,
            "resumable": binding.resumable,
        }
        if binding.replaced_session_id is not None:
            event["replacedSessionId"] = binding.replaced_session_id
        return event

    def _session_set_config_option_response(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        config_id = _string_or_default(payload.get("configId"), "")
        value = _string_or_default(payload.get("value"), "")
        session_id = _optional_string(payload.get("sessionId"))
        session_resumable = _bool_or_default(payload.get("sessionResumable"), False)
        bindings: list[AcpSessionBinding] = []
        try:
            updates = self.agent_manager.set_config_option(
                chat_id,
                agent_id,
                workspace_path,
                config_id,
                value,
                session_id,
                session_resumable,
                bindings.append,
            )
        except AcpAgentError as exc:
            updates = [
                {
                    "type": "session/update",
                    "chatId": chat_id,
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "set_config_option",
                        "title": "Set config",
                        "kind": "other",
                        "status": "failed",
                        "content": {"error": str(exc)},
                    },
                }
            ]
        for update in updates:
            update.setdefault("chatId", chat_id)
        return [self._session_binding_event(chat_id, binding) for binding in bindings] + updates + [{"type": "bridge.done", "chatId": chat_id}]

    def _session_refresh_config_options_response(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        agent_id = _string_or_default(payload.get("agentId"), "copilot-cli")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        session_id = _optional_string(payload.get("sessionId"))
        session_resumable = _bool_or_default(payload.get("sessionResumable"), False)
        bindings: list[AcpSessionBinding] = []
        try:
            updates = self.agent_manager.refresh_config_options(
                chat_id,
                agent_id,
                workspace_path,
                session_id,
                session_resumable,
                bindings.append,
            )
        except AcpAgentError as exc:
            updates = [
                {
                    "type": "session/update",
                    "chatId": chat_id,
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "config_refresh",
                        "title": "Config options",
                        "kind": "other",
                        "status": "failed",
                        "content": {"error": str(exc)},
                    },
                }
            ]
        for update in updates:
            update.setdefault("chatId", chat_id)
        return [self._session_binding_event(chat_id, binding) for binding in bindings] + updates + [{"type": "bridge.done", "chatId": chat_id}]

    def _log_client_prompt(self, payload: dict[str, Any]) -> None:
        chat_id = _string_or_default(payload.get("chatId"), "unknown-chat")
        agent_id = _string_or_default(payload.get("agentId"), "unknown-agent")
        workspace_path = _string_or_default(payload.get("workspacePath"), "")
        content = _string_or_default(payload.get("content"), "")
        print(
            f"[bridge] <- client chat={chat_id} agent={agent_id} cwd={_truncate_log(workspace_path, 40)} prompt=\"{_truncate_log(content)}\"",
            flush=True,
        )

    def _log_responses(self, responses: list[dict[str, Any]]) -> None:
        for response in responses:
            self._log_response(response)
        if any(response.get("type") == "bridge.done" for response in responses):
            for response in responses:
                chat_id = response.get("chatId")
                if isinstance(chat_id, str):
                    self._flush_agent_chunk_log(chat_id)

    def _log_response(self, response: dict[str, Any]) -> None:
        chat_id = _string_or_default(response.get("chatId"), "unknown-chat")
        if _is_agent_message_chunk(response):
            self._buffer_agent_chunk_log(chat_id, _agent_message_text(response))
            return
        self._flush_agent_chunk_log(chat_id)
        summary = _summarize_response(response)
        if summary is None:
            return
        print(f"[bridge] -> android chat={chat_id} {summary}", flush=True)

    def _buffer_agent_chunk_log(self, chat_id: str, text: str) -> None:
        if not text.strip():
            return
        buffer = self._agent_chunk_logs.setdefault(chat_id, AgentChunkLogBuffer())
        if buffer.suppressed:
            return
        buffer.text += text
        if len(buffer.text) >= 50:
            print(f"[bridge] -> android chat={chat_id} agent_message_chunk \"{_truncate_log(buffer.text, 50)}\"", flush=True)
            buffer.text = ""
            buffer.suppressed = True

    def _flush_agent_chunk_log(self, chat_id: str) -> None:
        buffer = self._agent_chunk_logs.pop(chat_id, None)
        if buffer is None or buffer.suppressed or not buffer.text.strip():
            return
        print(f"[bridge] -> android chat={chat_id} agent_message_chunk \"{_truncate_log(buffer.text, 50)}\"", flush=True)


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


def _optional_string(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _bool_or_default(value: Any, default: bool) -> bool:
    return value if isinstance(value, bool) else default


def _int_or_default(value: Any, default: int) -> int:
    return value if isinstance(value, int) else default


def _same_emitter(
    emitter: Callable[[dict[str, Any]], None],
    others: list[Callable[[dict[str, Any]], None]],
) -> bool:
    emitter_id = getattr(emitter, "_connection_id", id(emitter))
    return any(getattr(other, "_connection_id", id(other)) == emitter_id for other in others)


def _truncate_log(value: Any, limit: int = 80) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    normalized = " ".join(text.split())
    return normalized if len(normalized) <= limit else normalized[: limit - 1] + "…"


def _summarize_response(response: dict[str, Any]) -> str | None:
    response_type = response.get("type")
    if response_type == "approval.requested":
        summary = _string_or_default(response.get("summary"), "Agent requests approval")
        return f"approval.requested \"{_truncate_log(summary)}\""
    if response_type != "session/update":
        return None

    update = response.get("update") if isinstance(response.get("update"), dict) else {}
    update_kind = _string_or_default(update.get("sessionUpdate"), "session/update")
    if update_kind == "agent_message_chunk":
        text = _agent_message_text(response)
        if not text.strip():
            return None
        return f"{update_kind} \"{_truncate_log(text)}\""
    if update_kind in {"tool_call", "tool_call_update"}:
        title = _string_or_default(update.get("title"), _string_or_default(update.get("toolCallId"), "tool"))
        status = _string_or_default(update.get("status"), "")
        content = update.get("content")
        detail = title if content is None else f"{title} {content}"
        status_part = f" status={status}" if status else ""
        return f"{update_kind}{status_part} \"{_truncate_log(detail)}\""
    if update_kind == "config_option_update":
        options = update.get("configOptions")
        count = len(options) if isinstance(options, list) else 0
        return f"{update_kind} \"{count} option(s)\""
    return f"{update_kind} \"{_truncate_log(update)}\""


def _is_agent_message_chunk(response: dict[str, Any]) -> bool:
    if response.get("type") != "session/update":
        return False
    update = response.get("update") if isinstance(response.get("update"), dict) else {}
    return update.get("sessionUpdate") == "agent_message_chunk"


def _agent_message_text(response: dict[str, Any]) -> str:
    update = response.get("update") if isinstance(response.get("update"), dict) else {}
    text = _string_or_default(update.get("text"), "")
    if not text and isinstance(update.get("content"), dict):
        text = _string_or_default(update["content"].get("text"), "")
    return text


def _latest_visible_messages(updates: list[Any], limit: int) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for event in updates:
        if not isinstance(event, dict):
            continue
        update = event.get("update") if isinstance(event.get("update"), dict) else {}
        role = _visible_message_role(update)
        if role is None:
            continue
        text = _visible_message_text(update)
        if not text.strip():
            continue
        message_id = _string_or_default(update.get("messageId"), "")
        if messages and messages[-1].get("role") == role and (message_id == "" or messages[-1].get("messageId") == message_id):
            messages[-1]["text"] = str(messages[-1].get("text", "")) + text
            if message_id:
                messages[-1]["messageId"] = message_id
        else:
            message: dict[str, Any] = {"role": role, "text": text}
            if message_id:
                message["messageId"] = message_id
            messages.append(message)
    return messages[-limit:]


def _visible_message_role(update: dict[str, Any]) -> str | None:
    session_update = update.get("sessionUpdate")
    if session_update == "user_message_chunk":
        return "user"
    if session_update == "agent_message_chunk":
        return "agent"
    return None


def _visible_message_text(update: dict[str, Any]) -> str:
    text = _string_or_default(update.get("text"), "")
    if not text and isinstance(update.get("content"), dict):
        text = _string_or_default(update["content"].get("text"), "")
    return text


def _select_permission_option(options: list[dict[str, Any]], decision: str) -> str:
    target_prefix = "allow" if decision == "approved" else "reject"
    fallback = "allow-once" if decision == "approved" else "reject-once"
    match = next((item for item in options if isinstance(item, dict) and str(item.get("kind", "")).startswith(target_prefix)), None)
    if match is None and options:
        match = options[0]
    return str((match or {}).get("optionId", fallback))


CHAT_EVENT_LOG_LIMIT = 500
PROMPT_OPERATION_HISTORY_LIMIT = 1000
