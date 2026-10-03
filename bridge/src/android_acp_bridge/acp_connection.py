from __future__ import annotations

import logging
import queue
import threading
import time
from collections import deque
from typing import Any, Callable


class AcpConnection:
    """One ordered reader for responses, notifications and reverse RPCs."""

    def __init__(
        self, incoming: queue.Queue, write: Callable, update: Callable,
        permission: Callable, error_type: type[RuntimeError],
    ) -> None:
        self.incoming, self.write = incoming, write
        self.update, self.permission, self.error_type = update, permission, error_type
        self.condition = threading.Condition()
        self.request_lock = threading.Lock()
        self.next_id = 3
        self.pending: dict[str, Any] | None = None
        self.sink: Callable | None = None
        self.backlog: deque = deque()
        self.closed = threading.Event()
        self.cancelled = threading.Event()
        self.fault: RuntimeError | None = None
        self.reader: threading.Thread | None = None
        self.permissions: set[Any] = set()
        self.dispatching = False

    def fail(self, message: str) -> None:
        with self.condition:
            first = self.fault is None
            if self.fault is None:
                self.fault = self.error_type(message)
                logging.getLogger(__name__).error("%s", message)
            self.condition.notify_all()
            sink = self.sink if self.pending is None and not self.closed.is_set() else None
        if first and sink is not None:
            try:
                sink({"type": "session/update", "update": {
                    "sessionUpdate": "tool_call_update", "toolCallId": "acp_transport",
                    "title": "Agent connection failed", "kind": "other", "status": "failed",
                    "rawOutput": message,
                }})
                sink({"type": "chat.status", "status": "failed"})
            except Exception:
                logging.getLogger(__name__).exception("Could not deliver ACP transport failure.")

    def close(self) -> None:
        self.closed.set()
        self.cancelled.set()
        with self.condition:
            self.condition.notify_all()

    def _read(self) -> None:
        while not self.closed.is_set():
            try:
                message = self.incoming.get(timeout=0.1)
            except queue.Empty:
                continue
            if self.closed.is_set():
                return
            try:
                if not isinstance(message, dict) or "_transportError" in message:
                    raise self.error_type(message.get("_transportError", "Invalid ACP message.")
                                          if isinstance(message, dict) else "Invalid ACP message.")
                method = message.get("method")
                if method == "session/update":
                    with self.condition:
                        self.dispatching = True
                    params = message.get("params", {})
                    raw = params.get("update") if isinstance(params, dict) else None
                    if not isinstance(raw, dict):
                        raise self.error_type("Invalid ACP session update.")
                    for event in self.update(params):
                        with self.condition:
                            current = self.pending
                            callback = current["callback"] if current else self.sink
                            if current:
                                current["count"] += 1
                                current["last_update"] = time.monotonic()
                                if current["count"] > 100_000:
                                    raise self.error_type("ACP replay exceeded the recovery safety limit.")
                            if callback is None:
                                target = current["updates"] if current else self.backlog
                                if current is None and len(target) >= 512:
                                    raise self.error_type("ACP event backlog overflow; history is incomplete.")
                                if current is None or current["collect"]:
                                    target.append(event)
                        if callback is not None:
                            callback(event)
                    with self.condition:
                        self.dispatching = False
                        self.condition.notify_all()
                elif method in {"session/request_permission", "elicitation/create"}:
                    request_id = message.get("id")
                    with self.condition:
                        if request_id in self.permissions or len(self.permissions) >= 32:
                            raise self.error_type("Too many or duplicate ACP interaction requests.")
                        self.permissions.add(request_id)
                    threading.Thread(target=self._decide, args=(message,), daemon=True).start()
                elif method is not None:
                    if "id" in message:
                        self.write({"jsonrpc": "2.0", "id": message["id"],
                                    "error": {"code": -32601, "message": "Client method not supported."}})
                    else:
                        logging.getLogger(__name__).info("Unsupported ACP notification: %s", method)
                else:
                    with self.condition:
                        if self.pending and message.get("id") == self.pending["id"]:
                            self.pending["response"] = message
                            self.pending["last_update"] = time.monotonic()
                            self.condition.notify_all()
                        else:
                            raise self.error_type("Unexpected ACP response ID.")
            except Exception as exc:
                self.fail(f"ACP event dispatch failed: {exc}")
                return

    def _decide(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        try:
            self.permission({**message, "_cancelEvent": self.cancelled})
        except Exception as exc:
            if not self.closed.is_set():
                self.fail(f"ACP interaction failed: {exc}")
        finally:
            with self.condition:
                self.permissions.discard(request_id)
                self.condition.notify_all()

    def request(
        self, method: str, params: dict, timeout: float, callback: Callable | None = None,
        *, drain_idle: float = 0, drain_timeout: float = 0, max_updates: int = 100_000,
        collect: bool = True,
    ) -> tuple[dict, list, int, bool]:
        with self.request_lock:
            with self.condition:
                if self.closed.is_set():
                    raise self.error_type("ACP connection is closed.")
                if self.fault:
                    raise self.fault
                request = {"id": self.next_id, "callback": callback, "updates": deque(maxlen=max_updates),
                           "count": 0, "collect": collect, "response": None, "last_update": time.monotonic()}
                self.next_id += 1
                self.pending = request
                if method == "session/prompt":
                    self.cancelled = threading.Event()
                    self.sink = callback
                backlog = list(self.backlog)
                self.backlog.clear()
            try:
                for event in backlog:
                    if callback:
                        callback(event)
                    elif collect:
                        request["updates"].append(event)
                self.write({"jsonrpc": "2.0", "id": request["id"], "method": method, "params": params})
                with self.condition:
                    if self.reader is None:
                        self.reader = threading.Thread(target=self._read, daemon=True)
                        self.reader.start()
                    ready = self.condition.wait_for(
                        lambda: request["response"] is not None or self.fault or self.closed.is_set(), timeout,
                    )
                    if not ready:
                        self.fail(f"Timed out waiting for ACP response to {method}; execution is not confirmed.")
                    if self.fault:
                        raise self.fault
                    if self.closed.is_set():
                        raise self.error_type("ACP connection closed before completion.")
                    deadline = time.monotonic() + drain_timeout
                    while drain_idle and "error" not in request["response"]:
                        remaining = min(deadline, request["last_update"] + drain_idle) - time.monotonic()
                        if remaining <= 0:
                            break
                        self.condition.wait(remaining)
                        if self.fault:
                            raise self.fault
                        if self.closed.is_set():
                            raise self.error_type("ACP connection closed during history replay.")
                    return request["response"], list(request["updates"]), request["count"], (
                        bool(drain_idle) and time.monotonic() >= deadline
                    )
            finally:
                with self.condition:
                    self.pending = None
