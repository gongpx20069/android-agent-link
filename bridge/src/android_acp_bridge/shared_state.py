"""Bridge-owned catalog and bounded event journal shared by all control surfaces."""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
import weakref
from pathlib import Path
from typing import Any

from .device_tokens import DeviceTokenStore


class ControlError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def required_text(value: Any, name: str, limit: int = 1024) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit or "\x00" in value:
        raise ControlError("INVALID_ARGS", f"{name} must be nonempty text of at most {limit} characters.")
    return value


class SharedState:
    EVENT_LIMIT = 10000
    EVENT_BYTES = 128 * 1024

    def __init__(self, path: Path | None = None) -> None:
        self.lock = threading.RLock()
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            DeviceTokenStore._make_private(path.parent)
            if path.exists():
                DeviceTokenStore._make_private(path)
        self.db = sqlite3.connect(str(path) if path else ":memory:", check_same_thread=False)
        self._finalize = weakref.finalize(self, self.db.close)
        if path:
            DeviceTokenStore._make_private(path)
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS workspaces(id TEXT PRIMARY KEY, path TEXT UNIQUE NOT NULL, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY, data TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events(chat TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL,
                PRIMARY KEY(chat,seq));
            CREATE TABLE IF NOT EXISTS tasks(chat TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL,
                PRIMARY KEY(chat,id));
        """)
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO metadata VALUES('generation',?)", (secrets.token_hex(16),))
            # Process restart cannot prove that an external command stopped or completed.
            for chat in self.chats():
                if chat["status"] in {"busy", "waitingApproval"}:
                    chat["status"] = "interrupted"
                    self._put_chat(chat)
            for chat, identity, raw in self.db.execute("SELECT chat,id,data FROM tasks").fetchall():
                task = json.loads(raw)
                if task["state"] in {"starting", "running", "queued"}:
                    task["state"] = "interrupted"
                    self.db.execute("UPDATE tasks SET data=? WHERE chat=? AND id=?", (json.dumps(task), chat, identity))
        self.generation = self.db.execute("SELECT value FROM metadata WHERE key='generation'").fetchone()[0]

    def close(self) -> None:
        with self.lock:
            self._finalize()

    def workspace(self, path: str, identity: str | None = None, name: str | None = None) -> dict[str, Any]:
        key = os.path.normcase(str(Path(path).expanduser().resolve()))
        with self.lock, self.db:
            existing = self.db.execute("SELECT data FROM workspaces WHERE path=?", (key,)).fetchone()
            if existing:
                return json.loads(existing[0])
            item = {"id": identity or "ws_" + hashlib.sha256(key.encode()).hexdigest()[:24],
                    "displayName": name or Path(path).name or path, "absolutePath": path}
            self.db.execute("INSERT INTO workspaces VALUES(?,?,?)", (item["id"], key, json.dumps(item)))
            return item

    def workspaces(self) -> list[dict[str, Any]]:
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute("SELECT data FROM workspaces ORDER BY id")]

    def _put_chat(self, chat: dict[str, Any]) -> None:
        self.db.execute("INSERT OR REPLACE INTO chats VALUES(?,?)", (chat["chatId"], json.dumps(chat)))

    def register(self, payload: dict[str, Any]) -> dict[str, Any] | None:
        identity, path = payload.get("chatId"), payload.get("workspacePath")
        if not isinstance(identity, str) or not identity or not isinstance(path, str) or not path:
            return None
        required_text(identity, "chatId", 256)
        required_text(path, "workspacePath", 4096)
        with self.lock:
            existing = self.db.execute("SELECT data FROM chats WHERE id=?", (identity,)).fetchone()
            if existing:
                # Old client caches may be stale. Never let an attach replace authoritative bindings.
                return json.loads(existing[0])
            workspace = self.workspace(path)
            chat = {"chatId": identity, "chatTitle": str(payload.get("chatTitle") or payload.get("title") or workspace["displayName"])[:256],
                    "workspaceId": workspace["id"], "workspacePath": path,
                    "agentId": str(payload.get("agentId") or "copilot-cli"),
                    "sessionId": payload.get("sessionId"), "sessionResumable": payload.get("sessionResumable") is True,
                    "status": "idle", "revision": 0, "humanRevision": 0, "configOptions": [],
                    "eventGeneration": self.generation,
                    "updatedAt": int(time.time() * 1000)}
            with self.db:
                self._put_chat(chat)
            return chat

    def chat(self, identity: str) -> dict[str, Any]:
        with self.lock:
            row = self.db.execute("SELECT data FROM chats WHERE id=?", (identity,)).fetchone()
            if not row:
                raise ControlError("NOT_FOUND", "Shared chat was not found.")
            return json.loads(row[0])

    def bind_session(self, identity: str, session: str) -> None:
        with self.lock, self.db:
            chat = self.chat(identity)
            chat.update(sessionId=session, sessionResumable=True, status="idle",
                        humanRevision=chat["humanRevision"] + 1, updatedAt=int(time.time() * 1000))
            self._put_chat(chat)

    def chats(self) -> list[dict[str, Any]]:
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute("SELECT data FROM chats ORDER BY id")]

    def task(self, chat: str, identity: str) -> dict[str, Any] | None:
        with self.lock:
            row = self.db.execute("SELECT data FROM tasks WHERE chat=? AND id=?", (chat, identity)).fetchone()
            return json.loads(row[0]) if row else None

    def tasks(self, chat: str) -> list[dict[str, Any]]:
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute(
                "SELECT data FROM tasks WHERE chat=? ORDER BY rowid DESC LIMIT 100", (chat,))]

    def append(self, chat_id: str, event: dict[str, Any]) -> None:
        with self.lock, self.db:
            original = event
            raw = json.dumps(event, ensure_ascii=False)
            if len(raw.encode("utf-8")) > self.EVENT_BYTES:
                event = {key: value for key, value in event.items()
                         if key in {"type", "chatId", "operationId", "eventId", "timestamp", "status", "source"}}
                event["truncated"] = True
                event["notice"] = "Event exceeds shared journal limit; details are incomplete."
                raw = json.dumps(event)
            self.db.execute("INSERT OR REPLACE INTO events VALUES(?,?,?)", (chat_id, event["eventId"], raw))
            self.db.execute("DELETE FROM events WHERE chat=? AND seq <= ?", (chat_id, event["eventId"] - self.EVENT_LIMIT))
            try:
                chat = self.chat(chat_id)
            except ControlError:
                return
            event = original
            chat["revision"] = event["eventId"]
            chat["updatedAt"] = event["timestamp"]
            kind = event["type"]
            if kind == "chat.status":
                chat["status"] = event.get("status", "unknown")
            elif kind == "chat.session":
                chat["sessionId"] = event["sessionId"]
                chat["sessionResumable"] = event.get("resumable", False)
            elif kind == "operation.accepted" and event.get("source") != "mochi":
                chat["humanRevision"] += 1
            elif kind == "session/update" and event.get("update", {}).get("sessionUpdate") == "config_option_update":
                chat["configOptions"] = event["update"].get("configOptions", [])
            self._put_chat(chat)
            if kind in {"operation.accepted", "operation.started", "operation.done", "chat.prompt.remove.result"}:
                identity = event.get("operationId")
                if identity:
                    task = self.task(chat_id, identity) or {"taskId": identity, "chatId": chat_id}
                    if event.get("status") == "already_started":
                        return
                    task.update(state=event.get("status") or event.get("state") or "running",
                                updatedAt=event["timestamp"])
                    if "source" in event:
                        task["source"] = event["source"]
                    if kind == "operation.accepted":
                        task["contentDigest"] = hashlib.sha256(str(event.get("content", "")).encode()).hexdigest()
                    self.db.execute("INSERT OR REPLACE INTO tasks VALUES(?,?,?)", (chat_id, identity, json.dumps(task)))

    def events(self, chat: str, after: int = 0, limit: int = 50, budget: int = 180000) -> dict[str, Any]:
        with self.lock:
            bounds = self.db.execute("SELECT MIN(seq),MAX(seq) FROM events WHERE chat=?", (chat,)).fetchone()
            rows = self.db.execute("SELECT seq,data FROM events WHERE chat=? AND seq>? ORDER BY seq LIMIT ?",
                                   (chat, after, limit + 1)).fetchall()
            events, size = [], 0
            for _, raw in rows[:limit]:
                count = len(raw.encode("utf-8"))
                if events and size + count > budget:
                    break
                events.append(json.loads(raw))
                size += count
            last = events[-1]["eventId"] if events else after
            return {"events": events, "latestEventId": bounds[1] or 0, "nextEventId": last,
                    "hasMore": bool(rows and rows[-1][0] > last),
                    "truncated": bool(bounds[0] and after < bounds[0] - 1),
                    "eventGeneration": self.chat(chat).get("eventGeneration", self.generation) if self.db.execute(
                        "SELECT 1 FROM chats WHERE id=?", (chat,)).fetchone() else self.generation}

    def reset_events(self, chat: str) -> str:
        with self.lock, self.db:
            self.db.execute("DELETE FROM events WHERE chat=?", (chat,))
            generation = secrets.token_hex(16)
            row = self.db.execute("SELECT data FROM chats WHERE id=?", (chat,)).fetchone()
            if row:
                data = json.loads(row[0])
                data.update(revision=0, eventGeneration=generation, humanRevision=data["humanRevision"] + 1)
                self._put_chat(data)
            return generation
