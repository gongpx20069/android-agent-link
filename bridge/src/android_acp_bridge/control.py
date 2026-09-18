"""Authenticated shared control requests; transport credentials never enter responses."""
from __future__ import annotations

import hashlib
import os
import secrets
import subprocess
import sqlite3
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

from .acp_agent import AcpAgentError
from .shared_state import ControlError, required_text

if TYPE_CHECKING:
    from .runtime import BridgeRuntime


def control_response(runtime: BridgeRuntime, payload: dict[str, Any]) -> list[dict[str, Any]]:
    response: dict[str, Any] = {"type": "control.result", "requestId": payload.get("requestId")}
    try:
        response.update(status="ok", data=execute(runtime, payload))
    except ControlError as error:
        response.update(status="error", code=error.code, message=str(error))
    except AcpAgentError as error:
        response.update(status="error", code="PROVIDER_ERROR", message=str(error)[:1024])
    except sqlite3.Error:
        runtime.console.message("error", "shared_state.unavailable")
        response.update(status="error", code="PROVIDER_ERROR", message="Shared state storage failed; acceptance is uncertain. Inspect task state before retrying.")
    except (OSError, subprocess.SubprocessError):
        response.update(status="error", code="PROVIDER_ERROR",
                        message="Workspace operation failed or timed out. Inspect the target before retrying; no files were deleted.")
    return [response, {"type": "bridge.done", "chatId": payload.get("chatId")}]


def integer(payload: dict[str, Any], name: str, default: int, minimum: int, maximum: int) -> int:
    value = payload.get(name, default)
    if type(value) is not int or not minimum <= value <= maximum:
        raise ControlError("INVALID_ARGS", f"{name} must be between {minimum} and {maximum}.")
    return value


def execute(runtime: BridgeRuntime, payload: dict[str, Any]) -> dict[str, Any]:
    action = required_text(payload.get("action"), "action", 64)
    if action == "workspace.list":
        return {**runtime.workspaces_response(), "agents": runtime.agents_response()["agents"],
                "workspaceCreationEnabled": bool(runtime.config.workspace_roots)}
    if action == "workspace.create":
        return {"workspace": create_workspace(runtime, payload)}
    if action == "chat.list":
        chats = runtime.shared.chats()
        workspace = payload.get("workspaceId")
        if workspace is not None:
            chats = [chat for chat in chats if chat["workspaceId"] == workspace]
        offset = integer(payload, "offset", 0, 0, 1000000)
        limit = integer(payload, "limit", 50, 1, 100)
        # Catalog doesn't inline potentially large provider config definitions.
        return {"chats": [{k: v for k, v in chat.items() if k != "configOptions"} for chat in chats[offset:offset + limit]],
                "hasMore": offset + limit < len(chats), "nextOffset": offset + limit,
                "eventGeneration": runtime.shared.generation}
    if action in {"chat.create", "chat.register"}:
        if action == "chat.create":
            workspace_id = required_text(payload.get("workspaceId"), "workspaceId", 256)
            workspace = next((w for w in runtime.shared.workspaces() if w["id"] == workspace_id), None)
            if workspace is None:
                raise ControlError("NOT_FOUND", "Workspace not found.")
            payload = {**payload, "workspacePath": workspace["absolutePath"],
                       "chatId": payload.get("chatId") or "chat_" + secrets.token_hex(16)}
        agent = required_text(payload.get("agentId"), "agentId", 128)
        if agent not in {"copilot-cli", "claude-code"}:
            raise ControlError("INVALID_ARGS", "Unsupported execution agent.")
        with runtime.shared.lock:
            if action == "chat.create":
                identity = required_text(payload.get("chatId"), "chatId", 256)
                existing = runtime.shared.db.execute("SELECT 1 FROM chats WHERE id=?", (identity,)).fetchone()
                if existing:
                    known = runtime.shared.chat(identity)
                    if known["workspaceId"] != workspace_id or known["agentId"] != agent:
                        raise ControlError("CONFLICT", "Chat ID already belongs to a different workspace or agent.")
            chat = runtime.shared.register(payload)
        if chat is None:
            raise ControlError("INVALID_ARGS", "chatId and workspacePath are required.")
        if runtime.local_client:
            runtime.local_client.observe_request({"type": "chat.attach", **chat})
        return {"chat": chat}

    chat_id = required_text(payload.get("chatId"), "chatId", 256)
    if action == "chat.read":
        # Approval publication takes its own lock before the event lock.
        # Snapshot separately rather than invert that ordering during a read.
        approvals = [item for item in runtime.local_approvals() if item["chatId"] == chat_id]
        with runtime._prompt_lock, runtime._event_lock:
            chat = runtime.shared.chat(chat_id)
            page = runtime.shared.events(chat_id, integer(payload, "afterEventId", 0, 0, 2**53),
                                         integer(payload, "limit", 30, 1, 100))
            return {"chat": chat, **page, "tasks": runtime.shared.tasks(chat_id),
                    "approvals": approvals,
                    "online": True, "observedAt": int(time.time() * 1000)}
    if action == "chat.send":
        content = required_text(payload.get("content"), "content", 64000)
        operation = required_text(payload.get("operationId"), "operationId", 256)
        source = "mochi" if payload.get("source") == "mochi" else "human"
        with runtime._prompt_lock:
            chat = runtime.shared.chat(chat_id)
            existing = runtime.shared.task(chat_id, operation)
            if existing is not None:
                if existing.get("contentDigest") != hashlib.sha256(content.encode()).hexdigest():
                    raise ControlError("CONFLICT", "Operation ID already used with different content.")
                return {**existing, "duplicate": True}
            if source == "mochi" and (type(payload.get("expectedHumanRevision")) is not int
                                      or payload["expectedHumanRevision"] != chat["humanRevision"]):
                raise ControlError("CONFLICT", "Human input changed or was not read. Read the chat and get renewed user direction before continuing.")
            for identity in runtime._active_prompts:
                if identity != chat_id:
                    try:
                        other = runtime.shared.chat(identity)
                    except ControlError:
                        continue
                    if other["workspaceId"] == chat["workspaceId"]:
                        raise ControlError("CONFLICT", "Another task is using this workspace. Wait or use a separate worktree.")
            immediate: list[dict[str, Any]] = []
            def receive(event: dict[str, Any]) -> None:
                # A bounded acceptance sink, not another chat subscription.
                if event.get("type") in {"operation.accepted", "operation.done"} and len(immediate) < 4:
                    immediate.append(event)
            direct = runtime.websocket_responses({"type": "chat.prompt", **chat, "content": content,
                                                  "operationId": operation, "source": source}, emit=receive)
            immediate.extend(direct[:4])
            failed = next((event for event in immediate if event.get("error")), None)
            if failed:
                raise ControlError("CONFLICT", str(failed["error"]))
            accepted = next((event for event in immediate if event["type"] == "operation.accepted"), None)
            if accepted is None:
                raise ControlError("PROVIDER_ERROR", "Task acceptance was not confirmed; query by operationId before retrying.")
            return {"taskId": operation, "chatId": chat_id, "state": accepted["state"]}
    if action == "task.cancel":
        identity = required_text(payload.get("operationId"), "operationId", 256)
        runtime.shared.chat(chat_id)
        with runtime._prompt_lock:
            active = runtime._active_prompts.get(chat_id)
            if active and any(member.operation_id == identity for member in active.batch_members or [active]):
                cancel = getattr(runtime.agent_manager, "cancel_prompt", None)
                if cancel is None:
                    raise ControlError("UNSUPPORTED", "This agent cannot cancel a running task.")
                cancel(chat_id)
                for approval in runtime.local_approvals():
                    if approval["chatId"] == chat_id:
                        runtime._resolve_approval(approval["approvalId"], "denied", chat_id)
                return {"taskId": identity, "state": "cancellation_requested",
                        "notice": "Agent cancellation requested; already executed actions cannot be undone."}
            result = runtime.websocket_responses({"type": "chat.prompt.remove", "chatId": chat_id, "operationId": identity})
            return {"taskId": identity, "state": result[0].get("status", "unknown"), "events": result[:1]}
    if action == "chat.configure":
        chat = runtime.shared.chat(chat_id)
        message = {**chat, "type": "session.refreshConfigOptions"}
        if payload.get("configId") is not None:
            message.update(type="session.setConfigOption",
                           configId=required_text(payload.get("configId"), "configId", 256),
                           value=required_text(payload.get("value"), "value", 1024))
        responses = runtime.websocket_responses(message)
        error = next((event.get("update", {}).get("content", {}).get("error")
                      for event in responses if isinstance(event.get("update", {}).get("content"), dict)
                      and event["update"].get("status") == "failed"), None)
        if error:
            raise ControlError("CONFLICT", str(error))
        return {"chat": runtime.shared.chat(chat_id)}
    raise ControlError("INVALID_ARGS", "Unknown control action.")


def create_workspace(runtime: BridgeRuntime, payload: dict[str, Any]) -> dict[str, Any]:
    path = Path(required_text(payload.get("path"), "path", 4096)).expanduser()
    if not path.is_absolute():
        raise ControlError("INVALID_ARGS", "Workspace path must be absolute.")
    resolved = path.resolve()
    roots = [Path(root).resolve() for root in runtime.config.workspace_roots]
    if not roots or not any(resolved != root and resolved.is_relative_to(root) for root in roots):
        raise ControlError("PERMISSION_DENIED", "Workspace must be below a configured --workspace-root.")
    mode = payload.get("mode", "directory")
    if mode == "register_existing":
        if not resolved.is_dir():
            raise ControlError("NOT_FOUND", "Workspace directory does not exist.")
    else:
        if resolved.exists():
            raise ControlError("CONFLICT", "Target already exists; register it explicitly or choose a new path.")
        if not resolved.parent.is_dir():
            raise ControlError("INVALID_ARGS", "Workspace parent must already exist.")
        if mode == "directory":
            resolved.mkdir()
        elif mode in {"clone", "worktree"}:
            if mode == "clone":
                url = required_text(payload.get("repositoryUrl"), "repositoryUrl", 2048)
                parsed = urlsplit(url)
                if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                    raise ControlError("INVALID_ARGS", "Clone requires an HTTPS repository URL without credentials/query/fragment.")
                command = ["git", "-c", "protocol.file.allow=never", "clone", "--", url, str(resolved)]
            else:
                source = next((w for w in runtime.shared.workspaces() if w["id"] == payload.get("sourceWorkspaceId")), None)
                if source is None:
                    raise ControlError("NOT_FOUND", "Source workspace not found.")
                branch = required_text(payload.get("branch"), "branch", 200)
                if branch.startswith("-") or any(c.isspace() for c in branch):
                    raise ControlError("INVALID_ARGS", "Invalid branch name.")
                command = ["git", "-C", source["absolutePath"], "worktree", "add", "-b", branch, "--", str(resolved)]
            result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                    env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GCM_INTERACTIVE": "Never"},
                                    timeout=20, check=False)
            if result.returncode:
                raise ControlError("PROVIDER_ERROR", "Git workspace creation failed. Inspect the target before retrying.")
        else:
            raise ControlError("INVALID_ARGS", "Unknown workspace creation mode.")
    return runtime.shared.workspace(str(resolved))
