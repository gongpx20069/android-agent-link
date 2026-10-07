from __future__ import annotations

import asyncio
import json
import os
import queue
import secrets
import sqlite3
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

from .runtime import BridgeRuntime
from .acp_agent import AcpAgentError
from .shared_state import ControlError
from .stdlib_server import BridgeHTTPServer
from .terminal_render import MarkdownStream, display_text
from .terminal_state import project_tool

if TYPE_CHECKING:
    from prompt_toolkit import PromptSession


@dataclass
class TerminalChat:
    chat_id: str
    number: int = 0
    title: str = ""
    preview: str = ""
    agent_id: str = ""
    workspace: str = ""
    session_id: str | None = None
    resumable: bool = False
    status: str = "idle"
    queued_count: int = 0
    busy_since: float | None = None
    config_options: list[dict[str, Any]] = field(default_factory=list)
    commands: list[dict[str, str]] = field(default_factory=list)


@dataclass
class TerminalTool:
    title: str
    status: str


@dataclass
class PairingQuestion:
    device: str
    code: str | None
    expires: float
    answered: threading.Event = field(default_factory=threading.Event)
    approved: bool = False


class TerminalClient:
    def __init__(self, write: Callable[[str], None] | None = None) -> None:
        self.write = write or self._write
        self.runtime: BridgeRuntime | None = None
        self.chats: dict[str, TerminalChat] = {}
        self._next_chat_number = 1
        self._deleted_chats: set[str] = set()
        self._undelivered_deletions: set[str] = set()
        self._pending_chats: set[str] = set()
        self._creation: dict[str, Any] | None = None
        self.selected: str | None = None
        self._lock = threading.RLock()
        self._events: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=256)
        self._dropped = 0
        self._closed = False
        self._pairing: PairingQuestion | None = None
        self._shown_pairing: PairingQuestion | None = None
        self._reviewed: set[str] = set()
        self._tools: dict[tuple[str, str], TerminalTool] = {}
        self._stream: tuple[str, str] | None = None
        self._announced_chats: set[str] = set()
        self._choosing_chat = False
        self._chat_choices: dict[str, str] = {}
        self.markdown: MarkdownStream | None = None
        self._markdown_key: tuple[str, str] | None = None
        self.event_sink: Callable[[dict[str, Any]], None] | None = None
        self.pairing_display: str | None = None

    @staticmethod
    def _write(text: str) -> None:
        sys.stdout.write(text)
        sys.stdout.flush()

    def say(self, text: str) -> None:
        if self._stream is not None:
            self.write("\n")
            self._stream = None
        self.write(display_text(text) + "\n")

    def _write_markdown(self, text: str) -> None:
        if not text:
            return
        if self._stream != self._markdown_key:
            self.say("\nAgent >")
            self._stream = self._markdown_key
        self.write(text)

    def _finish_markdown(self) -> None:
        if self.markdown is not None:
            self.markdown.finish()
        self._markdown_key = None

    def _chat(self, chat_id: str) -> TerminalChat | None:
        if chat_id in self._deleted_chats or chat_id in self._pending_chats:
            return None
        chat = self.chats.get(chat_id)
        if chat is None:
            if len(self.chats) >= 256:
                self._dropped += 1
                return None
            chat = TerminalChat(chat_id, number=self._next_chat_number)
            self._next_chat_number += 1
            self.chats[chat_id] = chat
        return chat

    def observe_request(self, payload: dict[str, Any]) -> None:
        chat_id = payload.get("chatId")
        if payload.get("type") not in {"chat.prompt", "chat.attach"} or not isinstance(chat_id, str):
            return
        with self._lock:
            if self._closed:
                return
            chat = self._chat(chat_id)
            if chat is None:
                return
            if isinstance(payload.get("agentId"), str):
                chat.agent_id = payload["agentId"]
            if isinstance(payload.get("workspacePath"), str):
                chat.workspace = payload["workspacePath"]
            if isinstance(payload.get("chatTitle"), str):
                chat.title = " ".join(display_text(payload["chatTitle"][:1024]).split())[:64]
            if isinstance(payload.get("sessionId"), str):
                chat.session_id = payload["sessionId"]
                chat.resumable = payload.get("sessionResumable") is True
            if isinstance(payload.get("status"), str):
                chat.status = payload["status"]
            if isinstance(payload.get("configOptions"), list):
                chat.config_options = payload["configOptions"]

    def chat_label(self, chat_id: str) -> str:
        with self._lock:
            chat = self.chats.get(chat_id)
            if chat is None:
                return "Chat"
            workspace = chat.workspace.rstrip("\\/").replace("\\", "/").rsplit("/", 1)[-1]
            label = f"{chat.number}. {workspace[:48] or 'Workspace'} | {chat.agent_id[:32] or 'Agent'}"
            if chat.title or chat.preview:
                label += f" | {chat.title or chat.preview}"
            return " ".join(display_text(label).split())[:160]

    def prompt_label(self) -> str:
        with self._lock:
            if self._choosing_chat or self._creation is not None:
                return "Choice > "
            return "You > " if self.selected else "> "

    def toolbar(self, width: int) -> list[tuple[str, str]]:
        with self._lock:
            chat = self.chats.get(self.selected or "")
            state = "No chat selected"
            state_style = "class:status"
            if chat is not None:
                state = {"idle": "Ready", "busy": "Working", "waitingApproval": "Approval needed",
                         "failed": "Failed"}.get(chat.status, chat.status)
                if chat.status in {"busy", "waitingApproval"}:
                    state_style = "class:attention"
                    if chat.busy_since is not None:
                        state += f" {int(max(0, time.monotonic() - chat.busy_since))}s"
                elif chat.status == "failed":
                    state_style = "class:failure"
                if chat.queued_count:
                    state += f" | queued {chat.queued_count}"
            heading = self.chat_label(chat.chat_id) if chat else "Open a phone chat, or /chats to choose"
            hint = "/chats switch   /approvals review   /help   /quit"
            hint_style = "class:hint"
            question = self._pairing
            if question and not question.answered.is_set() and time.monotonic() < question.expires:
                hint = "PAIRING: check phone/code, then /pair y or /pair n"
                hint_style = "class:attention"
            elif self._choosing_chat:
                hint = "Enter a listed number; Enter cancels. No chat message will be sent."
            elif self._creation is not None:
                hint = "Follow the choices above; Enter cancels. No prompt is sent."
            elif chat and chat.status == "waitingApproval":
                hint = "Approval needed: /approvals to review, or decide on your phone"
                hint_style = "class:attention"
            else:
                active = next((tool for (chat_id, _), tool in reversed(self._tools.items())
                               if chat_id == self.selected and tool.status not in {"completed", "failed", "cancelled"}), None)
                if active:
                    hint = f"Tool: {active.title} [{active.status}]"
                elif self.markdown and self.markdown.pending:
                    hint = f"Receiving Markdown block ({len(self.markdown.pending)} chars)"
            return (
                fit_toolbar_line([(state_style, f" {state} "), ("class:context", f"| {heading}")], width)
                + [("", "\n")]
                + fit_toolbar_line([(hint_style, " " + hint)], width)
            )

    def _select_chat(self, chat_id: str) -> None:
        if chat_id != self.selected:
            self._finish_markdown()
        self.selected = chat_id
        self._choosing_chat = False
        self._chat_choices.clear()
        self.say(f"\nChat: {self.chat_label(chat_id)}")

    def _show_chats(self, *, choose: bool) -> None:
        with self._lock:
            ready = [chat for chat in self.chats.values() if chat.agent_id and chat.workspace]
            if not ready:
                self.say("No chats yet. Use /new or /resume, or open a chat on your phone.")
                return
            self.say("Chats (* = current):")
            for chat in ready:
                self.say(f"{'*' if chat.chat_id == self.selected else ' '} {self.chat_label(chat.chat_id)} [{chat.status}]")
                self.say(f"   Workspace: {chat.workspace}")
            if choose:
                self._choosing_chat = True
                self._chat_choices = {str(chat.number): chat.chat_id for chat in ready}
                self.say("Enter a chat number, or press Enter to keep the current chat.")
            else:
                self.say("Use /chats to choose, or /use <number> to switch directly.")

    def _refresh_chats(self, allow_auto_select: bool) -> None:
        with self._lock:
            ready = [chat for chat in self.chats.values() if chat.agent_id and chat.workspace]
            new = [chat for chat in ready if chat.chat_id not in self._announced_chats]
            self._announced_chats.update(chat.chat_id for chat in ready)
            if (self.selected is None and len(ready) == 1 and allow_auto_select
                    and not self._choosing_chat and self._creation is None):
                self._select_chat(ready[0].chat_id)
            elif new:
                if self.selected is None:
                    self._show_chats(choose=False)
                else:
                    for chat in new:
                        if chat.chat_id != self.selected:
                            self.say(f"Chat available: {self.chat_label(chat.chat_id)}. /use {chat.number} to switch.")

    def observe_event(self, event: dict[str, Any]) -> None:
        chat_id = event.get("chatId")
        if not isinstance(chat_id, str):
            return
        with self._lock:
            if self._closed:
                return
            if event.get("type") == "chat.deleted":
                self._deleted_chats.add(chat_id)
                self._undelivered_deletions.add(chat_id)
                self.chats.pop(chat_id, None)
                self._announced_chats.discard(chat_id)
                self._chat_choices = {key: value for key, value in self._chat_choices.items() if value != chat_id}
                self._tools = {key: value for key, value in self._tools.items() if key[0] != chat_id}
                if self.selected == chat_id:
                    self.selected = None
                return
            chat = self._chat(chat_id)
            if chat is None:
                return
            kind = event.get("type")
            if kind == "chat.session":
                chat.session_id = event.get("sessionId")
                chat.resumable = event.get("resumable") is True
            if kind == "operation.done" and event.get("status") == "completed" and chat.session_id:
                chat.resumable = True
            if kind == "chat.status":
                chat.status = str(event.get("status", "idle"))
                count = event.get("queuedCount")
                if isinstance(count, int) and not isinstance(count, bool) and count >= 0:
                    chat.queued_count = count
                if chat.status in {"busy", "waitingApproval"}:
                    if chat.busy_since is None:
                        chat.busy_since = time.monotonic()
                else:
                    chat.busy_since = None
            if kind == "approval.resolved":
                self._reviewed.discard(str(event.get("approvalId", "")))
            # Project only display fields; do not queue credentials, tool output or entire events.
            item = {
                "type": kind, "chatId": chat_id, "operationId": event.get("operationId", ""),
                "status": event.get("status"), "approvalId": event.get("approvalId"),
            }
            if kind == "operation.accepted":
                item["content"] = str(event.get("content", ""))[:8192]
                if event.get("image"):
                    item["content"] = "[Image attached]\n" + item["content"]
                item["state"] = event.get("state")
                if not chat.preview:
                    chat.preview = " ".join(display_text(item["content"]).split())[:64]
            elif kind == "session/update":
                update = event.get("update") if isinstance(event.get("update"), dict) else {}
                item["kind"] = update.get("sessionUpdate")
                if item["kind"] == "config_option_update":
                    options = update.get("configOptions")
                    if isinstance(options, list):
                        projected, truncated = project_tool({"content": options}, limit=64 * 1024)
                        if not truncated:
                            chat.config_options = [o for o in projected["content"] if isinstance(o, dict)]
                        else:
                            chat.config_options = []
                            self._dropped += 1
                    return
                if item["kind"] == "available_commands_update":
                    commands = update.get("availableCommands")
                    if isinstance(commands, list):
                        chat.commands = [
                            {"name": c["name"], "description": display_text(str(c.get("description", "")))[:160]}
                            for c in commands[:200] if isinstance(c, dict)
                            and isinstance(c.get("name"), str) and len(c["name"]) <= 80
                            and c["name"] == display_text(c["name"]) and not any(ch.isspace() for ch in c["name"])
                        ]
                    return
                if item["kind"] not in {"agent_message_chunk", "user_message_chunk", "tool_call", "tool_call_update"}:
                    return
                if self.event_sink is None and item["kind"] == "agent_message_chunk" and self.selected is not None and chat_id != self.selected:
                    return
                item["tool"] = str(update.get("toolCallId", "tool"))[:128]
                item["status"] = update.get("status")
                item["title"] = str(update.get("title") or "")[:160]
                content = update.get("content")
                text = update.get("text") or (content.get("text", "") if isinstance(content, dict) else "")
                if item["kind"] in {"agent_message_chunk", "user_message_chunk"}:
                    item["text"] = str(text)[:8192]
                    if len(str(text)) > 8192:
                        self._dropped += 1
                elif self.event_sink is not None:
                    item["fields"], item["truncated"] = project_tool(update)
            elif kind not in {"operation.done", "approval.requested", "approval.resolved", "chat.session.error"}:
                return
            try:
                self._events.put_nowait(item)
            except queue.Full:
                self._dropped += 1

    def confirm_pairing(self, device_name: str, code: str | None) -> bool:
        question = PairingQuestion(device_name, code, time.monotonic() + 120)
        with self._lock:
            if self._closed or self._pairing is not None:
                return False
            self._pairing = question
        question.answered.wait(120)
        with self._lock:
            if self._pairing is question:
                self._pairing = None
            return question.approved and time.monotonic() < question.expires and not self._closed

    def close(self) -> None:
        self._finish_markdown()
        with self._lock:
            self._closed = True
            if self._pairing is not None:
                self._pairing.answered.set()
            self._reviewed.clear()
            self._tools.clear()
            self._announced_chats.clear()
            self._chat_choices.clear()
            self.chats.clear()
            while not self._events.empty():
                self._events.get_nowait()

    def drain(self, *, allow_auto_select: bool = True) -> None:
        with self._lock:
            deleted = self._undelivered_deletions.copy()
            self._undelivered_deletions.clear()
        for chat_id in deleted:
            self._finish_markdown()
            if self.event_sink is not None:
                self.event_sink({"type": "chat.deleted", "chatId": chat_id})
            self.say("Shared chat removed. The agent's saved session history is unchanged.")
        self._refresh_chats(allow_auto_select)
        with self._lock:
            dropped, self._dropped = self._dropped, 0
            question = self._pairing
        if dropped:
            self.say(f"Terminal display limit reached ({dropped} omitted items). Android delivery is unchanged. Use /approvals for pending requests.")
        if question is not None and question is not self._shown_pairing:
            self._shown_pairing = question
            self.say(f"PAIRING: unverified device label: {question.device}")
            if question.code:
                self.say(f"Compare this code with YOUR phone: {question.code}")
            self.say("Use /pair y to approve after checking, or /pair n to deny (120s limit).")
        if question is None and self._shown_pairing is not None:
            self._shown_pairing = None
            self.say("Pairing confirmation closed.")
        fragments: list[str] = []
        fragment_key: tuple[str, str] | None = None

        def flush() -> None:
            nonlocal fragment_key
            if not fragments or fragment_key is None:
                return
            if self.markdown is not None:
                if self._markdown_key != fragment_key:
                    self._finish_markdown()
                    self._markdown_key = fragment_key
                self.markdown.feed("".join(fragments))
            else:
                if self._stream != fragment_key:
                    self.say("\nAgent >")
                    self._stream = fragment_key
                self.write(display_text("".join(fragments)))
            fragments.clear()

        for _ in range(256):
            try:
                item = self._events.get_nowait()
            except queue.Empty:
                break
            chat_id = item["chatId"]
            if chat_id in self._deleted_chats:
                continue
            label = self.chat_label(chat_id)
            selected = chat_id == self.selected
            kind = item["type"]
            if self.event_sink is not None:
                self.event_sink(item)
            if kind == "session/update" and item.get("kind") == "agent_message_chunk":
                if selected and self.event_sink is None:
                    key = (chat_id, str(item["operationId"]))
                    if key != fragment_key:
                        flush()
                    fragment_key = key
                    fragments.append(item.get("text", ""))
                continue
            flush()
            if kind == "operation.accepted":
                if selected and self.event_sink is None:
                    source = "You" if str(item["operationId"]).startswith("terminal_") else "Phone"
                    self.say(f"\n{source} > {item.get('content', '')}")
                    if item.get("state") == "queued":
                        self.say("Queued behind the active task.")
                elif not selected:
                    self.say(f"[{label}] New task. /chats to switch.")
            elif kind == "operation.done":
                if self._markdown_key == (chat_id, str(item["operationId"])):
                    self._finish_markdown()
                prefix = "" if selected else f"[{label}] "
                if self.event_sink is None or not selected:
                    self.say(f"{prefix}Task {item.get('status', 'finished')}.")
                self._tools = {key: value for key, value in self._tools.items() if key[0] != chat_id}
            elif kind == "session/update" and item.get("kind") in {"tool_call", "tool_call_update"}:
                key = (chat_id, item["tool"])
                previous = self._tools.get(key)
                status = item.get("status") or (previous.status if previous else "pending")
                title = item["title"] or (previous.title if previous else "Tool")
                if selected and self.event_sink is None and status in {"completed", "failed", "cancelled"} and (previous is None or previous.status != status):
                    self.say(f"  Tool [{status}]: {title}")
                if len(self._tools) >= 512 and key not in self._tools:
                    self._tools.pop(next(iter(self._tools)))
                    self.say("Terminal tool display limit reached; older status summaries may repeat.")
                self._tools[key] = TerminalTool(title, status)
            elif kind == "approval.requested":
                self.say(f"[{label}] Approval needed: {item['approvalId']}. Use /approvals to review.")
            elif kind == "approval.resolved":
                self.say(f"[{label}] Approval {item['approvalId']}: {item['status']}")
            elif kind == "chat.session.error":
                self.say(f"[{label}] Session restore failed; check the Android error details before retrying.")
        flush()

    def _review_approvals(self) -> None:
        assert self.runtime is not None
        approvals = self.runtime.local_approvals()
        with self._lock:
            self._reviewed.clear()
            for item in approvals:
                self.say(f"[{self.chat_label(item['chatId'])}] {item['approvalId']}: {item['summary']}")
                details = json.dumps(item.get("details", {}), ensure_ascii=False, indent=2)
                self.say(details[:8192])
                if item.get("interaction") == "question":
                    self.say(f"/answer {item['approvalId']} <JSON object matching the question fields>")
                else:
                    for option in item.get("options", []):
                        self.say(f"  {option.get('optionId')}: {option.get('name', option.get('kind'))} ({option.get('kind')})")
                    self.say(f"/choose {item['approvalId']} <option-id> selects that exact permission")
                if len(details) <= 8192:
                    self._reviewed.add(item["approvalId"])
                else:
                    self.say("Details truncated. Review and approve on Android; terminal denial is still available.")
        if not approvals:
            self.say("No pending approvals.")

    def command(self, line: str) -> bool:
        assert self.runtime is not None
        line = line.strip()
        if self._creation is not None:
            if line.startswith("/"):
                self._creation = None
                self.say("Selection cancelled. No message was sent.")
            else:
                self._creation_input(line)
                return True
        if self._choosing_chat and not line.startswith("/"):
            with self._lock:
                if not line:
                    self._choosing_chat = False
                    self._chat_choices.clear()
                    self.say("Chat selection cancelled. Current chat unchanged.")
                elif line in self._chat_choices:
                    self._select_chat(self._chat_choices[line])
                else:
                    self.say("Enter a number from the list, or press Enter to cancel. No message was sent.")
            return True
        if not line:
            return True
        command, _, argument = line.partition(" ")
        argument = argument.strip()
        if command == "/help":
            self.say("/chats (choose by number) | /use <number>\n"
                     "/new [agent-id absolute-workspace] (new shared chat, guided when omitted)\n"
                     "/resume (restore a saved session in the CURRENT shared chat)\n"
                     "/model (model picker) | /tools (focus tool group) | /qrcode or /pairing (phone QR/link)\n"
                     "/allow-all (session permissions; choose then y to confirm)\n"
                     "/config [id] (list agent settings or open a setting; choose then y to confirm)\n"
                     "/copy (latest reply source) | Ctrl+Y (drag-selected text, otherwise focused message/tool)\n"
                     "Left-drag text without Shift, release then Ctrl+Y | Right scrollbar: click/drag\n"
                     "/mouse (toggle app mouse handling for native text selection)\n"
                     "Tab focus | Enter expand | arrows/PgUp/PgDn browse | Left/Right page | Esc input\n"
                     "/approvals | /approve <approval-id> | /deny <approval-id> | /pair y|n\n"
                     "/choose <approval-id> <option-id> | /answer <approval-id> <JSON answers>\n"
                     "/send <text> (including a leading /) | /quit (stops bridge; /quit! while busy)")
        elif command == "/chats":
            for shared in self.runtime.shared.chats():
                self.observe_request({"type": "chat.attach", **shared})
            self._show_chats(choose=True)
        elif command == "/use":
            with self._lock:
                chat = next((chat for chat in self.chats.values() if str(chat.number) == argument), None)
                if chat is None:
                    chat = self.chats.get(argument)
                if not argument:
                    self._show_chats(choose=True)
                elif chat is None or not chat.agent_id or not chat.workspace:
                    self.say("Unknown chat. Use /chats to choose by number.")
                else:
                    self._select_chat(chat.chat_id)
        elif command == "/new":
            self._start_creation(argument)
        elif command == "/resume":
            self._start_resume(argument)
        elif command == "/approvals":
            self._review_approvals()
        elif command in {"/choose", "/answer"}:
            identity, _, value = argument.partition(" ")
            approvals = {item["approvalId"]: item for item in self.runtime.local_approvals()}
            item = approvals.get(identity)
            if item is None or identity not in self._reviewed:
                self.say("Review the current complete request with /approvals first.")
            elif command == "/answer":
                try:
                    answers = json.loads(value)
                    if not isinstance(answers, dict) or item.get("interaction") != "question":
                        raise ValueError("Expected a JSON object for a question.")
                    self._dispatch({"type": "approval.decide", "chatId": item["chatId"],
                                    "approvalId": identity, "decision": "approved", "answers": answers})
                except (ValueError, json.JSONDecodeError):
                    self.say("Use /answer <approval-id> <JSON object matching the displayed question schema>.")
            else:
                option = next((option for option in item.get("options", []) if option.get("optionId") == value), None)
                if option is None or item.get("interaction") == "question":
                    self.say("Choose an exact option ID from /approvals.")
                else:
                    self._dispatch({"type": "approval.decide", "chatId": item["chatId"], "approvalId": identity,
                                    "decision": "approved" if option.get("kind", "").startswith("allow") else "denied",
                                    "optionId": value})
        elif command in {"/approve", "/deny"}:
            with self._lock:
                reviewed = argument in self._reviewed
            approvals = {item["approvalId"]: item for item in self.runtime.local_approvals()}
            if argument not in approvals:
                self.say("Approval is missing, expired or already resolved.")
            elif command == "/approve" and not reviewed:
                self.say("Review full request details with /approvals before approving.")
            else:
                self._dispatch({"type": "approval.decide", "chatId": approvals[argument]["chatId"],
                                "approvalId": argument, "decision": "approved" if command == "/approve" else "denied"})
        elif command == "/pair":
            with self._lock:
                question = self._pairing
                if question is None or question.answered.is_set() or time.monotonic() >= question.expires:
                    self.say("No current pairing request.")
                elif question is not self._shown_pairing:
                    self.say("Wait for the pairing details to be displayed before answering.")
                else:
                    question.approved = argument.lower() in {"y", "yes"}
                    question.answered.set()
                    self.say("Pairing approval submitted." if question.approved else "Pairing denial submitted.")
        elif command in {"/quit", "/quit!"}:
            with self._lock:
                busy = any(c.status in {"busy", "waitingApproval"} for c in self.chats.values())
            if busy and command != "/quit!":
                self.say("Tasks are active. Wait for completion, or /quit! to stop the bridge and disconnect Android.")
            else:
                return False
        elif command.startswith("/") and command != "/send" and command not in {
            "/" + c["name"].lstrip("/") for c in self.chats.get(self.selected or "", TerminalChat("")).commands
        }:
            self.say("Unknown command. Use /help. Terminal input is never executed as a shell command.")
        else:
            with self._lock:
                chat = self.chats.get(self.selected or "")
                if chat is None or not chat.agent_id or not chat.workspace:
                    self.say("No chat selected. Open a chat on your phone, then /chats to choose. No message was sent.")
                    return True
                payload = {
                    "type": "chat.prompt", "chatId": chat.chat_id, "agentId": chat.agent_id,
                    "workspacePath": chat.workspace, "sessionId": chat.session_id,
                    "sessionResumable": chat.resumable, "operationId": "terminal_" + secrets.token_hex(16),
                    "content": argument if command == "/send" else line,
                }
            if not payload["content"]:
                self.say("Enter a nonempty message.")
            else:
                self._dispatch(payload)
        return True

    def _start_creation(self, argument: str) -> None:
        assert self.runtime is not None
        if len(self.chats) >= 256:
            self.say("Terminal chat limit reached; remove a shared chat on Android first.")
            return
        available = [a["id"] for a in self.runtime.agents_response()["agents"] if a["status"] == "available"]
        if not available:
            self.say("No installed agent is available. Install/configure an agent on this computer first.")
            return
        self._choosing_chat = False
        self._chat_choices.clear()
        self._creation = {"stage": "agent", "agents": available}
        if argument:
            agent, _, workspace = argument.partition(" ")
            if agent not in available:
                self._creation = None
                self.say("Choose an installed agent: " + ", ".join(available))
                return
            self._creation.update(agent=agent, stage="workspace", workspaces=[], page=0)
            self._creation_input(workspace)
        else:
            self.say("Choose an agent by number (Enter cancels):\n" +
                     "\n".join(f"{index}. {agent}" for index, agent in enumerate(available, 1)))

    def _start_resume(self, argument: str) -> None:
        assert self.runtime is not None
        if argument:
            self.say("Use /resume without arguments. It restores a session for the current chat's agent and workspace.")
            return
        if self.selected is None:
            self.say("Choose a chat with /chats, or create one with /new before using /resume.")
            return
        try:
            chat = self.runtime.shared.chat(self.selected)
            if chat["status"] in {"busy", "waitingApproval"}:
                raise ValueError("Chat is busy. Finish its task or approval before resuming.")
            state = {"chatId": chat["chatId"], "agent": chat["agentId"], "workspace": chat["workspacePath"],
                     "expectedHumanRevision": chat["humanRevision"], "expectedSessionId": chat.get("sessionId"),
                     "stage": "session", "page": 0}
            result = self.runtime.websocket_responses({"type": "session.list", "agentId": state["agent"],
                                                      "workspacePath": state["workspace"]})[0]
            if result.get("error"):
                raise ValueError(str(result["error"]))
            sessions = result.get("sessions", [])
            if not sessions:
                self.say("No saved sessions in this workspace. Current chat unchanged.")
                return
            state["sessions"] = sessions
            self._choosing_chat = False
            self._chat_choices.clear()
            self._creation = state
            self._show_creation_page()
        except (ValueError, OSError, sqlite3.Error, ControlError, AcpAgentError) as error:
            self.say("Cannot list sessions: " + str(error))

    def _creation_input(self, line: str) -> None:
        assert self.runtime is not None and self._creation is not None
        state = self._creation
        if not line:
            self._creation = None
            self.say("Selection cancelled. Current chat unchanged.")
            return
        if state["stage"] in {"workspace", "session"} and line in {"n", "p"}:
            values = state["workspaces"] if state["stage"] == "workspace" else state["sessions"]
            page = state["page"] + (1 if line == "n" else -1)
            if not 0 <= page * 15 < len(values):
                self.say("No page in that direction. Choose a displayed number, or Enter to cancel.")
                return
            state["page"] = page
            self._show_creation_page()
            return
        if state["stage"] == "agent":
            agents = state["agents"]
            if not line.isascii() or not line.isdecimal() or not 1 <= int(line) <= len(agents):
                self.say("Enter a listed agent number, or Enter to cancel. No message was sent.")
                return
            workspaces = self.runtime.shared.workspaces()
            state.update(agent=agents[int(line) - 1], stage="workspace", workspaces=workspaces, page=0)
            self._show_creation_page()
            return
        if state["stage"] == "workspace":
            workspaces = state["workspaces"]
            workspace = (workspaces[int(line) - 1]["absolutePath"]
                         if line.isascii() and line.isdecimal()
                         and state["page"] * 15 < int(line) <= min((state["page"] + 1) * 15, len(workspaces))
                         else line.strip('"'))
            try:
                valid = Path(workspace).is_absolute() and Path(workspace).is_dir()
            except (ValueError, OSError):
                valid = False
            if not valid:
                self.say("Workspace must be an existing absolute directory. Try again, or Enter to cancel.")
                return
            state["workspace"] = workspace
            self._creation = None
            self._create_shared_chat(state)
            return
        if state["stage"] == "session":
            sessions = state["sessions"]
            if (not line.isascii() or not line.isdecimal()
                    or not state["page"] * 15 < int(line) <= min((state["page"] + 1) * 15, len(sessions))):
                self.say("Enter a listed session number, or Enter to cancel. No message was sent.")
                return
            state.update(stage="confirm", session=sessions[int(line) - 1])
            self.say("Restore this saved session in the CURRENT shared chat? Type y to confirm; Enter cancels.\n"
                     + self.chat_label(state["chatId"]) + "\n"
                     + f"{state['agent']} | {state['workspace']}\n"
                     + str(state["session"].get("title") or "")[:160] + "\n"
                     + str(state["session"].get("sessionId")))
            return
        if line.lower() != "y":
            self.say("Type y to confirm, or Enter to cancel. No message was sent.")
            return
        self._creation = None
        self._resume_shared_chat(state)

    def _show_creation_page(self) -> None:
        assert self._creation is not None
        state = self._creation
        workspace = state["stage"] == "workspace"
        values = state["workspaces"] if workspace else state["sessions"]
        start = state["page"] * 15
        lines = ["Choose a workspace number or enter an existing absolute directory:" if workspace else
                 "Choose a saved session for the CURRENT shared chat:"]
        for index, value in enumerate(values[start:start + 15], start + 1):
            label = value["absolutePath"] if workspace else (
                str(value.get("title") or value.get("sessionId"))[:160] + " | " + str(value.get("updatedAt", ""))[:64]
                + (" [context only; no history replay]" if value.get("historyReplaySupported") is False else ""))
            lines.append(f"{index}. {label}")
        lines.append(f"Page {state['page'] + 1}/{max(1, (len(values) + 14) // 15)}. n = next, p = previous; Enter cancels.")
        self.say("\n".join(lines))

    def _create_shared_chat(self, state: dict[str, Any]) -> None:
        assert self.runtime is not None
        chat_id = "chat_" + secrets.token_hex(16)
        payload = {"chatId": chat_id, "agentId": state["agent"], "workspacePath": state["workspace"]}
        with self._lock:
            if len(self.chats) >= 256:
                self.say("Terminal chat limit reached. No chat was created.")
                return
            self._pending_chats.add(chat_id)
        published = False
        try:
            self.runtime.shared.register(payload, pending=True)
            with self.runtime._event_lock, self._lock:
                chat = self.runtime.shared.publish_chat(chat_id)
                published = True
                self._pending_chats.discard(chat_id)
                self.observe_request({"type": "chat.attach", **chat})
                self._announced_chats.add(chat_id)
                self._select_chat(chat_id)
                offset = 0
                while True:
                    page = self.runtime.shared.events(chat_id, after=offset)
                    for event in page["events"]:
                        self.observe_event(event)
                    if not page["hasMore"]:
                        break
                    offset = page["nextEventId"]
            self.say("Shared chat ready. Android synchronizes it automatically while open. No prompt was sent.")
        except (ValueError, OSError, sqlite3.Error, ControlError, AcpAgentError) as error:
            self.say("Cannot create shared chat: " + str(error))
        finally:
            with self._lock:
                self._pending_chats.discard(chat_id)
            if not published:
                self.runtime.delete_shared_chat(chat_id)

    def _resume_shared_chat(self, state: dict[str, Any]) -> None:
        assert self.runtime is not None
        if self.selected != state["chatId"]:
            self.say("Selected chat changed. Run /resume again; no session was changed.")
            return
        session = state["session"]
        replay = session.get("historyReplaySupported") is not False
        responses = self.runtime.websocket_responses({
            "type": "session.loadRecent" if replay else "session.resume",
            "chatId": state["chatId"], "agentId": state["agent"], "workspacePath": state["workspace"],
            "sessionId": session["sessionId"], "expectedHumanRevision": state["expectedHumanRevision"],
            "expectedSessionId": state["expectedSessionId"],
            "publishHistory": True, "limit": 5,
        })
        failure = next((r for r in responses if r.get("error")), None)
        if failure:
            self.say("Cannot resume session: " + str(failure["error"]))
            return
        self.say("Session restored in the current shared chat; Android uses the same chat. No prompt was sent."
                 + (" Latest five available message bubbles imported; older history remains with the agent." if replay else
                    " Context only: transcript replay is unavailable."))

    def _dispatch(self, payload: dict[str, Any]) -> None:
        assert self.runtime is not None
        # The observer receives sequenced events once; this callback only handles
        # unsequenced failures, without replacing Android's chat subscription.
        def immediate(event: dict[str, Any]) -> None:
            if "eventId" not in event and event.get("error"):
                self.say("Request failed: " + str(event["error"])[:1024])
            elif event.get("type") == "approval.decide.result" and not event.get("resolved"):
                self.say("Approval could not be resolved; it may have expired or changed. Use /approvals to refresh.")

        responses = self.runtime.websocket_responses(payload, emit=immediate)
        for response in responses:
            immediate(response)


async def terminal_loop(client: TerminalClient, server_alive: Callable[[], bool], session: PromptSession[str]) -> None:
    async def read_line() -> tuple[str, str]:
        try:
            line = await session.prompt_async(client.prompt_label)
            return "line", line
        except KeyboardInterrupt:
            return "cancel", ""
        except EOFError:
            return "eof", ""

    async def render() -> None:
        while True:
            client.drain(allow_auto_select=not session.default_buffer.text)
            if not server_alive():
                raise RuntimeError("Bridge listener stopped unexpectedly.")
            await asyncio.sleep(0.1)

    renderer = asyncio.create_task(render())
    prompt: asyncio.Task | None = None
    try:
        while True:
            prompt = asyncio.create_task(read_line())
            done, _ = await asyncio.wait({prompt, renderer}, return_when=asyncio.FIRST_COMPLETED)
            if renderer in done:
                await renderer
            action, line = prompt.result()
            if action == "cancel":
                client.say("Input cancelled. Use /quit to stop the bridge; running agent tasks are unchanged.")
                continue
            if action == "eof":
                client.say("Terminal closed; stopping the bridge.")
                break
            client.drain(allow_auto_select=False)
            if not client.command(line):
                break
    finally:
        for task in (prompt, renderer):
            if task is not None:
                task.cancel()
        await asyncio.gather(*(task for task in (prompt, renderer) if task is not None), return_exceptions=True)
        client.close()


def fit_toolbar_line(fragments: list[tuple[str, str]], width: int) -> list[tuple[str, str]]:
    from prompt_toolkit.utils import get_cwidth

    clean = [(style, " ".join(display_text(text).splitlines()).replace("\t", " ")) for style, text in fragments]
    width = max(0, width)
    if sum(get_cwidth(text) for _, text in clean) <= width:
        return clean
    suffix = "." * min(3, width)
    remaining = width - len(suffix)
    result: list[tuple[str, str]] = []
    for style, text in clean:
        part = ""
        for character in text:
            size = get_cwidth(character)
            if size > remaining:
                return result + [(style, part), ("class:hint", suffix)]
            part += character
            remaining -= size
        result.append((style, part))
    return result + [("class:hint", suffix)]


def create_terminal_session(client: TerminalClient) -> PromptSession[str]:
    from prompt_toolkit import PromptSession
    from prompt_toolkit.application import get_app
    from prompt_toolkit.history import DummyHistory
    from prompt_toolkit.output import ColorDepth
    from prompt_toolkit.styles import Style

    # Styled fragments keep untrusted titles out of HTML/ANSI parsers.
    session: PromptSession[str] = PromptSession(
        history=DummyHistory(),
        bottom_toolbar=lambda: client.toolbar(max(0, get_app().output.get_size().columns - 1)),
        refresh_interval=0.5,
        erase_when_done=True,
        color_depth=ColorDepth.DEPTH_1_BIT if os.environ.get("NO_COLOR") else None,
        style=Style.from_dict({
            "bottom-toolbar": "bg:ansiblack ansiwhite",
            "status": "bg:ansiblack ansigreen bold",
            "attention": "bg:ansiblack ansiyellow bold",
            "failure": "bg:ansiblack ansired bold",
            "context": "bg:ansiblack ansiwhite",
            "hint": "bg:ansiblack ansibrightblack",
        }),
    )
    client.markdown = MarkdownStream(
        client._write_markdown, client.say, lambda: max(4, session.output.get_size().columns - 1),
    )
    return session


def run_interactive(runtime: BridgeRuntime, client: TerminalClient) -> None:
    from .terminal_ui import FullScreenTerminal

    client.runtime = runtime
    with BridgeHTTPServer((runtime.config.host, runtime.config.port), runtime) as server:
        worker = threading.Thread(target=server.serve_forever, name="bridge-interactive-server", daemon=True)
        worker.start()
        try:
            ui = FullScreenTerminal(client, worker.is_alive)
            asyncio.run(ui.run())
        finally:
            client.close()
            if worker.is_alive():
                server.shutdown()
            worker.join()
