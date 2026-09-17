"""Bounded, display-only state for the interactive terminal. Never owns ACP routing."""
from __future__ import annotations

import json
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any

from .terminal_render import display_text


def project_tool(update: dict[str, Any], limit: int = 32_768) -> tuple[dict[str, Any], bool]:
    """Copy only bounded display data, without serializing a potentially huge event."""
    remaining = limit
    truncated = False

    def copy(value: Any, depth: int = 0) -> Any:
        nonlocal remaining, truncated
        if remaining <= 0 or depth > 10:
            truncated = True
            return "[display limit]"
        remaining -= 8
        if isinstance(value, str):
            if len(value) > remaining:
                truncated = True
            text = display_text(value[:max(0, remaining)])
            remaining -= len(text)
            return text
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if remaining <= 0:
                    truncated = True
                    break
                result[str(copy(str(key), depth + 1))] = copy(item, depth + 1)
            return result
        if isinstance(value, list):
            result = []
            for item in value:
                if remaining <= 0:
                    truncated = True
                    break
                result.append(copy(item, depth + 1))
            return result
        return value if value is None or isinstance(value, (bool, int, float)) else "[unsupported value]"

    fields = {}
    for name in ("title", "kind", "status", "rawInput", "rawOutput", "content", "locations", "terminalId"):
        if name in update:
            fields[name] = copy(update[name])
    return fields, truncated


@dataclass
class Tool:
    identity: str
    title: str = "Tool"
    status: str = "pending"
    fields: dict[str, Any] = field(default_factory=dict)
    truncated: bool = False
    expanded: bool = False
    page: int = 0


@dataclass
class Entry:
    number: int
    chat_id: str
    operation: str
    kind: str
    text: str = ""
    tools: OrderedDict[str, Tool] = field(default_factory=OrderedDict)
    expanded: bool = False
    page: int = 0
    revision: int = 0
    truncated: bool = False
    weight: int = 0


class Transcript:
    MAX_ENTRIES = 160
    MAX_CHARACTERS = 2 * 1024 * 1024
    MAX_TEXT = 128 * 1024
    MAX_TOOLS = 64
    PAGE_SIZE = 8192

    def __init__(self) -> None:
        self.entries: list[Entry] = []
        self.next_number = 1
        self.characters = 0
        self.evicted = 0
        self.revision = 0

    def add(self, chat: str, operation: str, kind: str, text: str = "") -> Entry:
        row = Entry(self.next_number, chat, operation, kind, display_text(text[:self.MAX_TEXT]),
                    truncated=len(text) > self.MAX_TEXT)
        self.next_number += 1
        self.entries.append(row)
        self.touch(row)
        return row

    def touch(self, row: Entry) -> None:
        self.characters -= row.weight
        row.weight = len(row.text) + sum(len(json.dumps(t.fields, ensure_ascii=False)) + len(t.title) for t in row.tools.values())
        self.characters += row.weight
        row.revision += 1
        self.revision += 1
        while len(self.entries) > self.MAX_ENTRIES or self.characters > self.MAX_CHARACTERS:
            removed = self.entries.pop(0)
            self.characters -= removed.weight
            self.evicted += 1

    def event(self, item: dict[str, Any]) -> None:
        chat, operation = item["chatId"], str(item.get("operationId", ""))
        kind = item["type"]
        if kind == "operation.accepted":
            role = "You" if operation.startswith("terminal_") else "Phone"
            self.add(chat, operation, role, item.get("content", "") + ("\n[Queued]" if item.get("state") == "queued" else ""))
        elif kind == "operation.done":
            self.add(chat, operation, "Notice", "Task " + str(item.get("status", "finished")) + ".")
        elif kind == "session/update":
            update_kind = item.get("kind")
            if update_kind == "agent_message_chunk":
                last = next((r for r in reversed(self.entries) if r.chat_id == chat), None)
                if last is None or last.operation != operation or last.kind != "Agent":
                    last = self.add(chat, operation, "Agent")
                text = item.get("text", "")
                available = max(0, self.MAX_TEXT - len(last.text))
                last.text += display_text(text[:available])
                last.truncated |= len(text) > available
                self.touch(last)
            elif update_kind in {"tool_call", "tool_call_update"}:
                row = next((r for r in reversed(self.entries)
                            if r.chat_id == chat and r.operation == operation and r.kind == "Tools"), None)
                if row is None:
                    row = self.add(chat, operation, "Tools")
                identity = str(item["tool"])
                tool = row.tools.get(identity)
                if tool is None:
                    if len(row.tools) >= self.MAX_TOOLS:
                        row.tools.popitem(last=False)
                        row.truncated = True
                    tool = Tool(identity)
                    row.tools[identity] = tool
                tool.title = " ".join(display_text(item.get("title") or tool.title).split())[:160]
                tool.status = " ".join(display_text(item.get("status") or tool.status).split())[:64]
                # Supplied arrays replace old arrays; absent fields retain earlier values.
                tool.fields.update(item.get("fields", {}))
                tool.fields, clipped = project_tool(tool.fields)
                tool.truncated |= clipped or item.get("truncated", False)
                self.touch(row)

    def visible(self, chat: str | None) -> list[Entry]:
        return [row for row in self.entries if not row.chat_id or row.chat_id == chat]


def model_option(options: list[dict[str, Any]]) -> dict[str, Any] | None:
    return next((option for option in options
                 if option.get("category") == "model" or str(option.get("id", "")).lower() == "model"), None)


def model_choices(option: dict[str, Any]) -> list[tuple[str, str]]:
    choices: list[tuple[str, str]] = []
    for entry in option.get("options", [])[:200]:
        if not isinstance(entry, dict):
            continue
        items = entry.get("options") if isinstance(entry.get("options"), list) else [entry]
        for item in items[:200]:
            if isinstance(item, dict) and isinstance(item.get("value"), str) and len(item["value"]) <= 1024:
                choices.append((item["value"], display_text(str(item.get("name") or item["value"]))[:160]))
            if len(choices) >= 200:
                return choices
    return choices
