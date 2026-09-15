from __future__ import annotations

import json
import secrets
import threading
import time
from copy import deepcopy
from dataclasses import dataclass
from typing import Any


class HistoryError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class HistorySnapshot:
    chat_id: str
    session_id: str
    rows: list[dict[str, Any]]
    scanned_events: int
    expires_at: float


class HistoryStore:
    def __init__(self, max_snapshots: int = 16, ttl_seconds: float = 1800) -> None:
        self._snapshots: dict[str, HistorySnapshot] = {}
        self._lock = threading.Lock()
        self.max_snapshots = max_snapshots
        self.ttl_seconds = ttl_seconds

    def create(self, chat_id: str, session_id: str, updates: list[Any], scanned_events: int, limit: int) -> dict[str, Any]:
        history_id = "history_" + secrets.token_urlsafe(16)
        rows = _history_rows(updates, history_id)
        with self._lock:
            self._purge()
            for key, snapshot in list(self._snapshots.items()):
                if snapshot.chat_id == chat_id:
                    del self._snapshots[key]
            while len(self._snapshots) >= self.max_snapshots:
                del self._snapshots[next(iter(self._snapshots))]
            snapshot = HistorySnapshot(chat_id, session_id, rows, scanned_events, time.monotonic() + self.ttl_seconds)
            self._snapshots[history_id] = snapshot
            return self._page(history_id, snapshot, len(rows), limit)

    def page(self, chat_id: str, session_id: str, history_id: str, before: Any, limit: int) -> dict[str, Any]:
        with self._lock:
            self._purge()
            snapshot = self._snapshots.get(history_id)
            if snapshot is None:
                raise HistoryError("history_expired", "History snapshot expired or is unavailable; explicitly reload the session.")
            if (snapshot.chat_id, snapshot.session_id) != (chat_id, session_id):
                raise HistoryError("history_mismatch", "History snapshot belongs to a different chat or session.")
            if type(before) is not int or not 0 <= before <= len(snapshot.rows):
                raise HistoryError("invalid_cursor", "History before must be a valid exclusive row offset.")
            return self._page(history_id, snapshot, before, limit)

    def _purge(self) -> None:
        now = time.monotonic()
        for key, snapshot in list(self._snapshots.items()):
            if now >= snapshot.expires_at:
                del self._snapshots[key]

    @staticmethod
    def _page(history_id: str, snapshot: HistorySnapshot, before: int, limit: int) -> dict[str, Any]:
        text_indices = [index for index, row in enumerate(snapshot.rows[:before]) if row["kind"] == "text"]
        start = text_indices[-limit] if len(text_indices) > limit else 0
        # Leading activities belong to the following bubble, not to the older page.
        while start > 0 and snapshot.rows[start - 1]["kind"] != "text":
            start -= 1
        return {
            "chatId": snapshot.chat_id,
            "sessionId": snapshot.session_id,
            "historyId": history_id,
            "messages": deepcopy(snapshot.rows[start:before]),
            "nextBefore": start if start > 0 else None,
            "totalMessages": sum(row["kind"] == "text" for row in snapshot.rows),
            "hasMore": start > 0,
            "scannedEvents": snapshot.scanned_events,
            "truncated": False,
        }


def _history_rows(updates: list[Any], history_id: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    message_ids: dict[int, str] = {}
    for event in updates:
        if not isinstance(event, dict):
            continue
        update = event.get("update")
        if not isinstance(update, dict):
            update = event
        update_type = update.get("sessionUpdate", event.get("type", "control"))
        role = {"user_message_chunk": "user", "agent_message_chunk": "agent"}.get(update_type)
        timestamp = event.get("timestampMillis", event.get("timestamp", update.get("timestampMillis", 0)))
        timestamp = timestamp if isinstance(timestamp, (int, float)) else 0
        if role:
            content = update.get("content")
            text = update.get("text") or (content.get("text") if isinstance(content, dict) else "")
            if not isinstance(text, str) or not text:
                continue
            message_id = update.get("messageId", "")
            if rows and rows[-1]["kind"] == "text" and rows[-1]["role"] == role and (
                not message_id or message_ids.get(len(rows) - 1) == message_id
            ):
                rows[-1]["text"] += text
                continue
            message_ids[len(rows)] = message_id
            row = {"role": role, "text": text, "kind": "text", "title": "", "details": "", "activityId": ""}
        else:
            kind = "activity" if update_type in {"tool_call", "tool_call_update"} else "plan" if update_type == "plan" else "control"
            row = {
                "role": "system",
                "text": "",
                "kind": kind,
                "title": str(update.get("title") or update_type),
                "details": json.dumps(update, ensure_ascii=False, separators=(",", ":")),
                "activityId": str(update.get("toolCallId") or ""),
            }
        row["timestampMillis"] = int(timestamp)
        row["historyItemId"] = f"{history_id}:{len(rows)}"
        rows.append(row)
    return rows
