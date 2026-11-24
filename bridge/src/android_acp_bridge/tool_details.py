"""Bounded tool snapshots; chat journals carry references, never tool bodies."""
from __future__ import annotations

import json
import secrets
import time
from typing import Any

from .shared_state import ControlError, SharedState

TOOL_UPDATES = {"tool_call", "tool_call_update"}


class ToolDetails:
    MAX_DETAIL_BYTES = 4 * 1024 * 1024
    QUOTA_BYTES = 64 * 1024 * 1024
    TTL = 7 * 86400
    SNAPSHOT_TTL = 1800
    PAGE_CHARS = 2048  # At most 24 KiB including escaped surrogate pairs.

    def __init__(self, shared: SharedState) -> None:
        self.shared = shared
        with shared.lock, shared.db:
            shared.db.executescript("""
                CREATE TABLE IF NOT EXISTS tool_details(
                    ref TEXT PRIMARY KEY, chat TEXT NOT NULL, scope TEXT NOT NULL,
                    tool TEXT NOT NULL, version INTEGER NOT NULL, updated REAL NOT NULL, data TEXT NOT NULL,
                    size INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS tool_identity ON tool_details(chat,scope,tool,updated);
                CREATE INDEX IF NOT EXISTS tool_budget ON tool_details(updated,size);
                CREATE TABLE IF NOT EXISTS tool_snapshots(
                    id TEXT PRIMARY KEY, ref TEXT NOT NULL, chat TEXT NOT NULL,
                    version INTEGER NOT NULL, updated REAL NOT NULL, data TEXT NOT NULL, size INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS tool_snapshot_budget ON tool_snapshots(updated,size);
            """)
            self._purge()
        # Upgrade existing journals once; old retained bodies must not bypass lazy reads.
        with shared.lock:
            for chat, seq, raw in shared.db.execute("SELECT chat,seq,data FROM events ORDER BY chat,seq"):
                event = json.loads(raw)
                if self.is_tool(event) and "detailRef" not in event["update"]:
                    projected = self.capture(chat, event, self.scope(chat))
                    with shared.db:
                        shared.db.execute("UPDATE events SET data=? WHERE chat=? AND seq=?",
                                          (json.dumps(projected), chat, seq))

    @staticmethod
    def is_tool(event: dict[str, Any]) -> bool:
        update = event.get("update")
        return isinstance(update, dict) and update.get("sessionUpdate") in TOOL_UPDATES

    def scope(self, chat: str) -> str:
        with self.shared.lock:
            row = self.shared.db.execute("SELECT data FROM chats WHERE id=?", (chat,)).fetchone()
            data = json.loads(row[0]) if row else {}
            return str(data.get("eventGeneration", self.shared.generation)) + ":" + str(data.get("sessionId", ""))

    def capture(self, chat: str, event: dict[str, Any], scope: str) -> dict[str, Any]:
        if event.get("sessionUpdate") in TOOL_UPDATES:
            event = {"type": "session/update", "update": event}
        if not self.is_tool(event):
            return event
        update = event["update"]
        if "detailRef" in update:
            keys = {"sessionUpdate", "toolCallId", "title", "status", "kind", "parentToolCallId",
                    "detailRef", "detailVersion", "detailAvailable", "detailsTruncated"}
            return {**event, "update": {key: value for key, value in update.items() if key in keys}}
        tool = str(update.get("toolCallId") or secrets.token_hex(16))
        now = time.time()
        with self.shared.lock, self.shared.db:
            row = self.shared.db.execute(
                "SELECT ref,version,data FROM tool_details WHERE chat=? AND scope=? AND tool=? ORDER BY updated DESC LIMIT 1",
                (chat, scope, tool),
            ).fetchone()
            if update.get("sessionUpdate") == "tool_call":
                row = None
            ref, version, body = (row[0], row[1] + 1, json.loads(row[2])) if row else (secrets.token_hex(24), 1, {})
            body.update(update)  # ACP arrays are replacement snapshots, not append deltas.
            body["toolCallId"] = tool
            body.setdefault("title", str(update.get("kind") or "Tool call"))
            body.setdefault("status", "in_progress")
            title, status = str(body["title"])[:256], str(body["status"])[:32]
            raw = json.dumps(body, ensure_ascii=False, indent=2)
            if len(raw.encode("utf-8")) > self.MAX_DETAIL_BYTES:
                body = {"title": title, "status": status, "truncated": True,
                        "notice": "Tool details exceeded the 4 MiB retention limit; this snapshot is incomplete."}
                raw = json.dumps(body)
            self.shared.db.execute("INSERT OR REPLACE INTO tool_details VALUES(?,?,?,?,?,?,?,?)",
                                   (ref, chat, scope, tool, version, now, raw, len(raw.encode("utf-8"))))
            self._purge(protect=ref)
        summary = {"sessionUpdate": update["sessionUpdate"], "toolCallId": tool,
                   "title": title, "status": status, "detailRef": ref, "detailVersion": version,
                   "detailAvailable": True}
        for key in ("kind", "parentToolCallId"):
            if key in body:
                summary[key] = str(body[key])[:256]
        if body.get("truncated"):
            summary["detailsTruncated"] = True
        return {**event, "update": summary}

    def page(self, chat: str, ref: str, snapshot: str | None = None, before: int = 0) -> dict[str, Any]:
        if not isinstance(ref, str) or len(ref) != 48 or type(before) is not int or before < 0:
            raise ControlError("INVALID_ARGS", "Invalid tool detail reference or cursor.")
        if snapshot is not None and (not isinstance(snapshot, str) or len(snapshot) != 48):
            raise ControlError("INVALID_ARGS", "Invalid tool snapshot.")
        with self.shared.lock, self.shared.db:
            self.shared.chat(chat)  # Enforces chat existence/deletion as well as ref ownership.
            self._purge()
            if snapshot is None:
                if before:
                    raise ControlError("INVALID_ARGS", "Pagination requires a snapshot.")
                row = self.shared.db.execute("SELECT version,data FROM tool_details WHERE ref=? AND chat=?",
                                             (ref, chat)).fetchone()
                if row is None:
                    raise ControlError("NOT_FOUND", "Tool details expired or are unavailable.")
                version, raw = row
                existing = self.shared.db.execute(
                    "SELECT id FROM tool_snapshots WHERE ref=? AND chat=? AND version=?", (ref, chat, version)).fetchone()
                snapshot = existing[0] if existing else secrets.token_hex(24)
                self.shared.db.execute("INSERT OR REPLACE INTO tool_snapshots VALUES(?,?,?,?,?,?,?)",
                                       (snapshot, ref, chat, version, time.time(), raw, len(raw.encode("utf-8"))))
                self._purge(protect=ref, protect_snapshot=snapshot)
            else:
                row = self.shared.db.execute(
                    "SELECT version,data FROM tool_snapshots WHERE id=? AND ref=? AND chat=?",
                    (snapshot, ref, chat)).fetchone()
                if row is None:
                    raise ControlError("NOT_FOUND", "Tool snapshot expired; refresh to read the latest version.")
                version, raw = row
            if before > len(raw):
                raise ControlError("INVALID_ARGS", "Invalid tool detail cursor.")
            end = min(len(raw), before + self.PAGE_CHARS)
            return {"ref": ref, "snapshot": snapshot, "version": version, "offset": before,
                    "text": raw[before:end], "next": end if end < len(raw) else None,
                    "complete": end == len(raw)}

    def _purge(self, protect: str = "", protect_snapshot: str = "") -> None:
        db = self.shared.db
        now = time.time()
        db.execute("DELETE FROM tool_snapshots WHERE updated<?", (now - self.SNAPSHOT_TTL,))
        db.execute("DELETE FROM tool_details WHERE updated<?", (now - self.TTL,))
        total = sum(db.execute(f"SELECT COALESCE(SUM(size),0) FROM {table}").fetchone()[0]
                    for table in ("tool_details", "tool_snapshots"))
        if total <= self.QUOTA_BYTES:
            return
        rows = db.execute("""
            SELECT 'tool_snapshots',id,size,updated FROM tool_snapshots WHERE id!=?
            UNION ALL
            SELECT 'tool_details',ref,size,updated FROM tool_details WHERE ref!=?
            ORDER BY updated
        """, (protect_snapshot, protect)).fetchall()
        for table, identity, size, _ in rows:
            key = "id" if table == "tool_snapshots" else "ref"
            db.execute(f"DELETE FROM {table} WHERE {key}=?", (identity,))
            total -= size
            if total <= self.QUOTA_BYTES:
                break
