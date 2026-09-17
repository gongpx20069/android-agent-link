from __future__ import annotations

import re
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Callable, Iterator


LEVELS = {"debug": 10, "info": 20, "warning": 30, "error": 40}


def _label(value: Any) -> str:
    return re.sub(r"[^a-zA-Z0-9_.:-]", "_", str(value))[:64] or "-"


@dataclass
class OperationSummary:
    started: float
    reported: float
    chunks: int = 0
    chars: int = 0
    thought_chunks: int = 0
    tool_count: int = 0
    tools: dict[str, tuple[str, float]] = field(default_factory=dict)
    waiting: set[str] = field(default_factory=set)
    responding: bool = False


class ConsoleLog:
    """Metadata-only business summaries; never retain event bodies or credentials."""

    def __init__(
        self,
        level: str = "info",
        clock: Callable[[], float] = time.monotonic,
        write: Callable[[str], None] | None = None,
    ) -> None:
        self.threshold = LEVELS[level]
        self.clock = clock
        self.write = write or (lambda line: print(line, flush=True))
        self._lock = threading.RLock()
        self._operations: dict[tuple[str, str], OperationSummary] = {}
        self._prompt_active = False
        self._deferred = 0
        self._stop = threading.Event()
        self._worker: threading.Thread | None = None

    def message(self, level: str, action: str, **fields: Any) -> None:
        with self._lock:
            if LEVELS[level] < self.threshold:
                return
            if self._prompt_active and LEVELS[level] < LEVELS["warning"]:
                self._deferred += 1
                return
            details = " ".join(f"{key}={_label(value)}" for key, value in fields.items())
            prefix = "\n" if self._prompt_active else ""
            self.write(f"{prefix}{time.strftime('%H:%M:%S')} {level.upper():7} [bridge] {action} {details}".rstrip())

    @contextmanager
    def pairing_prompt(self) -> Iterator[None]:
        with self._lock:
            self._prompt_active = True
        try:
            yield
        finally:
            with self._lock:
                self._prompt_active = False
                count, self._deferred = self._deferred, 0
                if count:
                    self.message("info", "console.summary", deferred=count)

    def start(self) -> None:
        with self._lock:
            if self._worker is not None:
                return
            self._stop.clear()
            self._worker = threading.Thread(target=self._run, name="bridge-log-progress", daemon=True)
            self._worker.start()

    def close(self) -> None:
        self._stop.set()
        worker = self._worker
        if worker is not None:
            worker.join()
        with self._lock:
            self._worker = None
            self._operations.clear()

    def _run(self) -> None:
        while not self._stop.wait(1):
            self.progress()

    def progress(self) -> None:
        with self._lock:
            now = self.clock()
            for (chat, operation), state in self._operations.items():
                if now - state.reported >= 15:
                    state.reported = now
                    self.message(
                        "info", "operation.progress", chat=chat, op=operation,
                        state="awaiting_approval" if state.waiting else "running",
                        duration=f"{now - state.started:.1f}s", chunks=state.chunks,
                        chars=state.chars, tools=state.tool_count,
                    )

    def _state(self, key: tuple[str, str]) -> OperationSummary:
        state = self._operations.get(key)
        if state is None:
            if len(self._operations) >= 256:
                self._operations.pop(next(iter(self._operations)))
                self.message("warning", "logging.capacity", limit=256)
            state = OperationSummary(self.clock(), self.clock())
            self._operations[key] = state
        return state

    def observe(self, event: dict[str, Any], operation_id: str | None = None) -> None:
        with self._lock:
            self._observe(event, operation_id)

    def _observe(self, event: dict[str, Any], operation_id: str | None) -> None:
        kind = event.get("type")
        chat = str(event.get("chatId", "-"))
        operation = str(operation_id or event.get("operationId") or "-")
        key = (chat, operation)
        fields = {"chat": chat, "op": operation}
        now = self.clock()
        if event.get("error") is not None:
            self.message("error", "request.failed", **fields, kind=kind)
            return
        if kind == "operation.started":
            self._state(key)
            self.message("info", "operation.started", **fields, batch=event.get("batchSize", 1))
        elif kind == "operation.accepted":
            self.message("debug", "operation.accepted", **fields, state=event.get("state", "-"))
        elif kind == "operation.done":
            state = self._operations.pop(key, None)
            status = event.get("status", "unknown")
            self.message(
                "error" if status == "failed" else "info", "operation.finished", **fields,
                status=status, duration=f"{now - state.started:.1f}s" if state else "-",
                chunks=state.chunks if state else 0, chars=state.chars if state else 0,
                thought_chunks=state.thought_chunks if state else 0,
                tools=state.tool_count if state else 0,
            )
        elif kind in {"approval.requested", "approval.resolved"}:
            approval = str(event.get("approvalId", "-"))
            state = self._operations.get(key)
            if state is None:
                state = next((s for (c, _), s in self._operations.items() if c == chat and approval in s.waiting), None)
            if state is not None:
                if kind == "approval.requested":
                    if len(state.waiting) < 512:
                        state.waiting.add(approval)
                    else:
                        self.message("warning", "logging.approval_capacity", **fields)
                else:
                    state.waiting.discard(approval)
            self.message("warning" if kind == "approval.requested" else "info",
                         str(kind), **fields, approval=approval, status=event.get("status", "pending"))
        elif kind == "session/update":
            update = event.get("update")
            if not isinstance(update, dict):
                self.message("warning", "event.invalid_update", **fields)
                return
            update_kind = update.get("sessionUpdate")
            if update_kind in {"agent_message_chunk", "agent_thought_chunk"}:
                # Unscoped history/config messages must not create immortal operations.
                state = self._operations.get(key)
                text = update.get("text")
                if not isinstance(text, str):
                    content = update.get("content")
                    text = content.get("text", "") if isinstance(content, dict) else ""
                length = len(text) if isinstance(text, str) else 0
                if state is not None:
                    if update_kind == "agent_message_chunk":
                        state.chunks += 1
                        state.chars += length
                        if not state.responding:
                            state.responding = True
                            self.message("info", "response.started", **fields)
                    else:
                        state.thought_chunks += 1
                self.message("debug", "stream.chunk", **fields, kind=update_kind,
                             chars=length, event=event.get("eventId", "-"))
            elif update_kind in {"tool_call", "tool_call_update"}:
                state = self._state(key)
                tool = str(update.get("toolCallId", "-"))
                previous = state.tools.get(tool) if state else None
                status = update.get("status") or (previous[0] if previous else "pending")
                terminal = status in {"completed", "failed", "cancelled"}
                if state is not None:
                    if previous is None:
                        state.tool_count += 1
                    if len(state.tools) >= 512 and tool not in state.tools:
                        state.tools.pop(next(iter(state.tools)))
                        self.message("warning", "logging.tool_capacity", **fields)
                    state.tools[tool] = (str(status), previous[1] if previous else now)
                if previous is None or (terminal and previous[0] != status):
                    self.message(
                        "error" if status == "failed" else "info",
                        "tool.finished" if terminal else "tool.started", **fields,
                        tool=tool, status=status,
                        duration=f"{now - previous[1]:.1f}s" if terminal and previous else "-",
                    )
                else:
                    self.message("debug", "tool.update", **fields, tool=tool, status=status)
            else:
                self.message("debug", "session.update", **fields, kind=update_kind,
                             event=event.get("eventId", "-"))
        elif kind == "chat.attached":
            self.message("info", "chat.attached", chat=chat, replayed=event.get("replayed", 0))
        elif kind == "chat.resyncRequired":
            self.message("warning", "chat.resync_required", chat=chat)
        elif kind == "bridge.done":
            # One-shot operations may not have operation.started/done.
            self._operations.pop((chat, "-"), None)
        elif kind == "chat.status":
            self.message("debug", "chat.status", **fields, status=event.get("status", "-"))
        elif kind in {"bridge.error", "operation.error", "error"}:
            self.message("error", "operation.error", **fields)
        else:
            self.message("debug", "event", **fields, kind=kind, event=event.get("eventId", "-"))

    def http(self, method: str, status: int) -> None:
        self.message("error" if status >= 500 else "warning" if status >= 400 else "debug",
                     "http.response", method=method, status=status)

    def finish_responses(self, chats: set[str]) -> None:
        with self._lock:
            for chat in chats:
                self._operations.pop((chat, "-"), None)
