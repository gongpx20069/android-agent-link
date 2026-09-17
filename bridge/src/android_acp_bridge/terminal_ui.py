from __future__ import annotations

import asyncio
import json
import os
import queue
from contextlib import redirect_stdout, redirect_stderr
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable

from prompt_toolkit.application import Application
from prompt_toolkit.buffer import Buffer
from prompt_toolkit.completion import Completer, Completion
from prompt_toolkit.data_structures import Point
from prompt_toolkit.filters import Condition, has_focus
from prompt_toolkit.formatted_text import ANSI, to_formatted_text
from prompt_toolkit.formatted_text.utils import split_lines
from prompt_toolkit.history import DummyHistory
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.keys import Keys
from prompt_toolkit.layout import DynamicContainer, Float, FloatContainer, HSplit, Layout, Window
from prompt_toolkit.layout.controls import BufferControl, FormattedTextControl, UIContent, UIControl
from prompt_toolkit.layout.menus import CompletionsMenu
from prompt_toolkit.mouse_events import MouseEventType
from prompt_toolkit.output import ColorDepth
from prompt_toolkit.styles import Style
from prompt_toolkit.utils import get_cwidth

from .terminal_render import MarkdownStream, display_text
from .terminal_clipboard import copy_text
from .terminal_state import Entry, Transcript, allow_all_option, config_value, model_choices, model_option

if TYPE_CHECKING:
    from .terminal import TerminalClient


def wrap_display_line(fragments: list[tuple[str, str]], width: int) -> list[list[tuple[str, str]]]:
    """Physical lines make every part of a long wrapped tool line keyboard-accessible."""
    lines: list[list[tuple[str, str]]] = [[]]
    column = 0
    for style, text in fragments:
        for character in text:
            characters = " " * (4 - column % 4) if character == "\t" else character
            for char in characters:
                size = max(0, get_cwidth(char))
                if column + size > width and column:
                    lines.append([])
                    column = 0
                if lines[-1] and lines[-1][-1][0] == style:
                    old_style, old_text = lines[-1][-1]
                    lines[-1][-1] = (old_style, old_text + char)
                else:
                    lines[-1].append((style, char))
                column += size
    return lines


@dataclass
class ConfigPicker:
    chat_id: str
    session_id: str | None
    config_id: str
    current: str
    choices: list[tuple[str, str]]
    selected: int = 0
    command: str = "/model"
    boolean: bool = False
    confirming: bool = False

    @property
    def title(self) -> str:
        return "Allow all" if self.command == "/allow-all" else "Model"


class SlashCompleter(Completer):
    COMMANDS = {
        "/model": "Choose the model for this chat",
        "/allow-all": "Change session permissions (explicit confirmation)",
        "/tools": "Focus expandable tools",
        "/chats": "Choose shared phone chat by number",
        "/use": "Switch to a chat number",
        "/new": "New terminal-local chat: agent workspace",
        "/approvals": "Review pending approval details",
        "/approve": "Approve a fully reviewed request",
        "/deny": "Deny a request",
        "/pair": "Confirm phone pairing: y / n",
        "/pairing": "Show startup phone pairing QR/link",
        "/qrcode": "Show phone pairing QR/link (Esc returns)",
        "/copy": "Copy latest retained agent reply to the local clipboard",
        "/mouse": "Toggle app mouse handling for native terminal text selection",
        "/send": "Send literal text, including a leading /",
        "/help": "Commands and shortcuts",
        "/quit": "Stop bridge (use /quit! while busy)",
        "/quit!": "Stop bridge even with running tasks",
    }

    def __init__(self, client: TerminalClient) -> None:
        self.client = client

    def get_completions(self, document, complete_event):
        word = document.text_before_cursor
        if not word.startswith("/") or any(c.isspace() for c in word):
            return
        commands = dict(self.COMMANDS)
        with self.client._lock:
            chat = self.client.chats.get(self.client.selected or "")
            for command in chat.commands if chat else []:
                name = "/" + command["name"].lstrip("/")
                if name not in {"/resume", "/allow_all"}:
                    commands.setdefault(name, "Agent: " + command["description"])
        for name, description in commands.items():
            if name.startswith(word):
                yield Completion(name, start_position=-len(word), display_meta=description)


class DraftControl(BufferControl):
    def __init__(self, ui: FullScreenTerminal) -> None:
        super().__init__(buffer=ui.buffer, focus_on_click=True)
        self.ui = ui

    def mouse_handler(self, mouse_event):
        result = super().mouse_handler(mouse_event)
        if mouse_event.event_type == MouseEventType.MOUSE_UP:
            self.ui.conversation.resume_follow()
        return result


class ConversationControl(UIControl):
    def __init__(self, ui: FullScreenTerminal) -> None:
        self.ui = ui
        self.lines: list[list[tuple[str, str]]] = []
        self.targets: dict[int, tuple[int, str | None]] = {}
        self.cursor = 0
        self.follow = True
        self.new_messages = False
        self._key: tuple[Any, ...] | None = None

    def is_focusable(self) -> bool:
        return True

    def create_content(self, width: int, height: int) -> UIContent:
        key = (self.ui.transcript.revision, self.ui.client.selected, width)
        if key != self._key:
            changed_chat = self._key is not None and self._key[1] != key[1]
            anchor = self.target() if self.lines else None
            old_cursor = self.cursor
            old_start = next((line for line, target in self.targets.items() if target == anchor), old_cursor)
            self.lines, self.targets = self.ui.render_conversation(max(4, width - 1))
            if self.follow or changed_chat:
                self.cursor = max(0, len(self.lines) - 1)
                self.follow = True
                self.new_messages = False
            elif anchor is not None:
                matching = [line for line, target in self.targets.items() if target == anchor]
                if matching:
                    self.cursor = matching[0] + max(0, old_cursor - old_start)
                self.new_messages = True
            self.cursor = min(self.cursor, max(0, len(self.lines) - 1))
            self._key = key
        return UIContent(
            get_line=lambda line: self.lines[line], line_count=len(self.lines),
            cursor_position=Point(x=0, y=self.cursor), show_cursor=self.ui.app.layout.has_focus(self),
        )

    def target(self) -> tuple[int, str | None] | None:
        return next((self.targets[line] for line in sorted(self.targets, reverse=True) if line <= self.cursor), None)

    def move(self, delta: int) -> None:
        self.follow = False
        self.cursor = min(max(0, self.cursor + delta), max(0, len(self.lines) - 1))
        self.ui.app.invalidate()

    def resume_follow(self) -> None:
        self.follow = True
        self.new_messages = False
        self.cursor = max(0, len(self.lines) - 1)

    def mouse_handler(self, mouse_event):
        if mouse_event.event_type == MouseEventType.SCROLL_UP:
            self.move(-3)
        elif mouse_event.event_type == MouseEventType.SCROLL_DOWN:
            self.move(3)
        elif mouse_event.event_type == MouseEventType.MOUSE_UP:
            self.ui.app.layout.focus(self)
            self.follow = False
            self.cursor = mouse_event.position.y
            self.ui.toggle()
        else:
            return NotImplemented
        return None


class FullScreenTerminal:
    """One full-screen UI; network work stays off its event loop and never attaches a second subscriber."""

    def __init__(self, client: TerminalClient, server_alive: Callable[[], bool], *, input=None, output=None) -> None:
        self.client = client
        self.server_alive = server_alive
        self.transcript = Transcript()
        self.notices: queue.Queue[str] = queue.Queue(maxsize=128)
        self.notice_drops = 0
        self.review_eviction = 0
        self.client.write = self.write
        self.client.event_sink = self.transcript.event
        self.client.markdown = None
        self.previous_console_write = self.client.runtime.console.write if self.client.runtime else None
        if self.client.runtime:
            self.client.runtime.console.write = self.write
        self.picker: ConfigPicker | None = None
        self.show_pairing = False
        self.pairing_line = 0
        self.pairing_column = 0
        self.config_busy = False
        self.model_request_id = 0
        self.command_busy = False
        self.copy_busy = False
        self.mouse_enabled = True
        self.tasks: set[asyncio.Task] = set()
        self.render_cache: dict[int, tuple[tuple[Any, ...], list[list[tuple[str, str]]]]] = {}
        self.line_cache: dict[int, tuple[tuple[Any, ...], list[list[tuple[str, str]]], dict[int, tuple[int, str | None]]]] = {}
        self.buffer = Buffer(history=DummyHistory(), completer=SlashCompleter(client), complete_while_typing=True,
                             multiline=False)
        self.input_control = DraftControl(self)
        self.input_was_focused = True
        self.conversation = ConversationControl(self)
        self.picker_control = FormattedTextControl(self.picker_text, focusable=True)
        self.pairing_control = FormattedTextControl(
            lambda: [("", self.client.pairing_display or "No startup pairing link available.")],
            focusable=True, get_cursor_position=lambda: Point(self.pairing_column, self.pairing_line),
        )
        kb = self._bindings()
        conversation_window = Window(self.conversation, wrap_lines=True, always_hide_cursor=False)
        pairing_window = Window(self.pairing_control, wrap_lines=False)
        body = HSplit([
            Window(FormattedTextControl(self.heading), height=1, style="class:heading"),
            DynamicContainer(lambda: pairing_window if self.show_pairing else conversation_window),
            Window(FormattedTextControl(self.status), height=3, style="class:status"),
            Window(self.input_control, height=3, wrap_lines=True,
                   get_line_prefix=lambda line, wrap: [("class:prompt", self.client.prompt_label() if not line and not wrap else "      ")]),
        ])
        from prompt_toolkit.layout.containers import ConditionalContainer
        layout = FloatContainer(body, floats=[
            Float(xcursor=True, ycursor=True, content=CompletionsMenu(max_height=10, scroll_offset=1)),
            Float(left=2, right=2, top=2, content=ConditionalContainer(
                Window(self.picker_control, wrap_lines=True, height=14, style="class:picker"),
                filter=Condition(lambda: self.picker is not None),
            )),
        ])
        self.app: Application[None] = Application(
            layout=Layout(layout, focused_element=self.input_control), key_bindings=kb,
            full_screen=True, mouse_support=Condition(lambda: self.mouse_enabled), min_redraw_interval=0.05, refresh_interval=0.5,
            color_depth=ColorDepth.DEPTH_1_BIT if os.environ.get("NO_COLOR") else None,
            style=Style.from_dict({
                "heading": "reverse bold", "status": "reverse", "prompt": "bold",
                "tool": "ansicyan", "failed": "ansired bold", "muted": "ansibrightblack",
                "picker": "bg:ansiblack ansiwhite", "selected": "reverse",
                "notice": "ansiyellow",
            }),
            input=input, output=output,
            before_render=self.sync_input_focus,
        )
        self.write("AgentLink | shared Android chat. /qrcode for phone QR/link; /chats to choose; /help for commands.\n"
                   "Tab: focus conversation/input | Enter: expand tool | Esc: input | /model: choose model\n"
                   "Terminal history is a bounded live view; earlier session history stays on Android.\n")

    def sync_input_focus(self, app: Application) -> None:
        focused = app.layout.has_focus(self.input_control)
        if focused and not self.input_was_focused:
            self.conversation.resume_follow()
        self.input_was_focused = focused

    def write(self, text: str) -> None:
        try:
            self.notices.put_nowait(display_text(text[:Transcript.MAX_TEXT]))
        except queue.Full:
            self.notice_drops += 1
            with self.client._lock:
                self.client._reviewed.clear()

    def heading(self):
        label = self.client.chat_label(self.client.selected) if self.client.selected else "Choose a phone chat with /chats"
        return [("class:heading", " AgentLink | " + label)]

    def status(self):
        with self.client._lock:
            chat = self.client.chats.get(self.client.selected or "")
            option = model_option(chat.config_options) if chat else None
            model = display_text(str(option.get("currentValue", "unknown"))) if option else "not loaded (/model)"
            permission = allow_all_option(chat.config_options) if chat else None
            allow_all = display_text(config_value(permission)) if permission else "unknown"
            state = chat.status if chat else "No chat"
            queued = chat.queued_count if chat else 0
            attention = self.client.toolbar(500)
            hint = "".join(text for _, text in attention).split("\n")[-1].strip()
        extra = " | New messages: Esc/End to follow" if self.conversation.new_messages else ""
        if self.client.runtime:
            pending = self.client.runtime.local_approval_count()
            if pending:
                extra += f" | {pending} approval(s): /approvals"
        if self.config_busy:
            extra += " | Loading/changing config..."
        if self.show_pairing:
            hint = "PAIRING QR/link | arrows scroll | Esc returns to chat | restart bridge if expired"
        return [
            ("", f" {state} | Model: {model[:40]} | Allow all: {allow_all[:20]} | Queue: {queued}{extra}\n"),
            ("", f" {hint}\n"),
            ("", " Tab focus | Enter expand | Ctrl+Y copy row | Esc input/latest | /help"),
        ]

    def _bindings(self) -> KeyBindings:
        kb = KeyBindings()
        browsing = Condition(lambda: self.app.layout.has_focus(self.conversation) and self.picker is None and not self.show_pairing)
        editing = has_focus(self.input_control) & Condition(lambda: self.picker is None)
        picking = Condition(lambda: self.picker is not None)
        pairing = Condition(lambda: self.show_pairing)

        @kb.add("tab", filter=~picking & ~pairing)
        def focus(event):
            if self.app.layout.has_focus(self.input_control):
                self.buffer.cancel_completion()
                self.app.layout.focus(self.conversation)
            else:
                self.app.layout.focus(self.input_control)
                self.conversation.resume_follow()

        @kb.add("escape")
        def escape(event):
            self.model_request_id += 1
            self.picker = None
            self.show_pairing = False
            self.buffer.cancel_completion()
            self.app.layout.focus(self.input_control)
            self.conversation.resume_follow()

        @kb.add("c-c")
        def cancel(event):
            self.model_request_id += 1
            self.picker = None
            self.show_pairing = False
            self.buffer.reset()
            self.app.layout.focus(self.input_control)
            self.write("Input cancelled; running tasks continue. /quit to stop the bridge.")

        @kb.add("c-d", filter=editing)
        def eof(event):
            self.submit("/quit")

        @kb.add("enter", filter=editing)
        def send(event):
            if self.buffer.complete_state is not None and self.buffer.complete_state.current_completion is not None:
                self.buffer.apply_completion(self.buffer.complete_state.current_completion)
                return
            line = self.buffer.text
            if self.submit(line):
                self.buffer.reset()
                if self.app.layout.has_focus(self.input_control):
                    self.conversation.resume_follow()

        @kb.add(Keys.Any, filter=browsing)
        def type_from_conversation(event):
            if event.data and all(character.isprintable() for character in event.data):
                self.app.layout.focus(self.input_control)
                self.conversation.resume_follow()
                self.buffer.insert_text(event.data)

        @kb.add(Keys.BracketedPaste, filter=browsing)
        def paste_from_conversation(event):
            self.app.layout.focus(self.input_control)
            self.conversation.resume_follow()
            self.buffer.insert_text(event.data.replace("\r\n", "\n").replace("\r", "\n"))

        for key, delta in (("up", -1), ("down", 1), ("pageup", -15), ("pagedown", 15)):
            @kb.add(key, filter=browsing)
            def move(event, delta=delta):
                self.conversation.move(delta)

        @kb.add("home", filter=browsing)
        def home(event):
            self.conversation.move(-len(self.conversation.lines))

        @kb.add("end", filter=browsing)
        def end(event):
            self.conversation.resume_follow()

        @kb.add("enter", filter=browsing)
        def toggle(event):
            self.toggle()

        @kb.add("c-y", filter=browsing)
        def copy_selected(event):
            row, tool_id = self.selected_entry()
            self.request_copy(row, tool_id)

        for key, delta in (("left", -1), ("right", 1)):
            @kb.add(key, filter=browsing)
            def page(event, delta=delta):
                self.change_page(delta)

        for key, delta in (("up", -1), ("down", 1)):
            @kb.add(key, filter=picking)
            def pick(event, delta=delta):
                if self.picker and not self.picker.confirming:
                    self.picker.selected = (self.picker.selected + delta) % len(self.picker.choices)

        @kb.add("enter", filter=picking)
        def confirm(event):
            picker = self.picker
            if picker:
                if picker.command == "/allow-all":
                    picker.confirming = True
                    return
                self.picker = None
                self.app.layout.focus(self.input_control)
                self.config_busy = True
                self.start(self.set_config(picker))

        @kb.add("y", filter=picking)
        def confirm_permissions(event):
            picker = self.picker
            if picker and picker.confirming:
                self.picker = None
                self.app.layout.focus(self.input_control)
                self.config_busy = True
                self.start(self.set_config(picker))

        @kb.add("n", filter=picking)
        def decline_permissions(event):
            if self.picker and self.picker.confirming:
                self.picker.confirming = False

        for key, delta in (("up", -1), ("down", 1), ("pageup", -12), ("pagedown", 12)):
            @kb.add(key, filter=pairing)
            def pairing_scroll(event, delta=delta):
                count = len((self.client.pairing_display or "").splitlines())
                self.pairing_line = min(max(0, self.pairing_line + delta), max(0, count - 1))

        for key, delta in (("left", -8), ("right", 8)):
            @kb.add(key, filter=pairing)
            def pairing_horizontal(event, delta=delta):
                lines = (self.client.pairing_display or "").splitlines()
                length = len(lines[self.pairing_line]) if self.pairing_line < len(lines) else 0
                self.pairing_column = min(max(0, self.pairing_column + delta), length)

        return kb

    def start(self, coroutine) -> None:
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    def submit(self, line: str) -> bool:
        command = line.strip().split(" ", 1)[0]
        if command == "/mouse":
            self.mouse_enabled = not self.mouse_enabled
            self.write("App mouse handling enabled." if self.mouse_enabled else
                       "App mouse handling disabled. Drag to select text and use your terminal's Copy action; /mouse restores tool clicks.")
            return True
        if command == "/copy":
            if line.strip() != "/copy":
                self.write("Use /copy for the latest reply, or Ctrl+Y on a conversation row for that message/tool.")
            else:
                rows = [row for row in self.transcript.entries if row.chat_id == self.client.selected and row.kind == "Agent"]
                if rows:
                    reply = [row for row in rows if row.operation == rows[-1].operation]
                    self.request_copy_text("\n\n".join(row.text for row in reply),
                                           any(row.truncated for row in reply) or bool(self.transcript.evicted))
                else:
                    self.write("No retained message to copy.")
            return True
        if command in {"/pairing", "/qrcode"}:
            self.show_pairing = True
            self.pairing_line = self.pairing_column = 0
            self.app.layout.focus(self.pairing_control)
            return True
        if command == "/resume":
            self.write("Use Android's session/permission picker for this action. It is not forwarded as an ordinary terminal command.")
            return True
        if command in {"/model", "/allow-all", "/allow_all"}:
            normalized = "/allow-all" if command == "/allow_all" else command
            if line.strip() != command:
                self.write(f"Use {normalized} and choose an advertised setting; inline values are not accepted.")
            elif self.config_busy:
                self.write("A configuration request is already in progress.")
            elif self.client._choosing_chat:
                self.write(f"Finish or cancel /chats selection before opening {normalized}.")
            else:
                self.config_busy = True
                self.model_request_id += 1
                self.start(self.open_config(self.client.selected or "", self.model_request_id, normalized))
            return True
        if command == "/tools":
            rows = [r for r in self.transcript.visible(self.client.selected) if r.kind == "Tools"]
            if rows:
                row = rows[-1]
                row.expanded = True
                self.transcript.touch(row)
                self.conversation.create_content(max(4, self.app.output.get_size().columns - 1), 30)
                self.conversation.cursor = next((line for line, target in self.conversation.targets.items()
                                                 if target == (row.number, None)), 0)
                self.conversation.follow = False
                self.app.layout.focus(self.conversation)
            else:
                self.write("No tool activity retained for this chat yet.")
            return True
        if self.command_busy:
            self.write("Previous command is still running; draft retained.")
            return False
        self.command_busy = True
        self.start(self.execute(line))
        return True

    def request_copy(self, row: Entry | None, tool_id: str | None = None) -> None:
        if row is None:
            self.write("No retained message to copy.")
            return
        incomplete = row.truncated
        if row.kind == "Tools":
            tools = [row.tools[tool_id]] if tool_id in row.tools else list(row.tools.values())
            text = json.dumps([tool.fields for tool in tools], ensure_ascii=False, indent=2)
            incomplete |= any(tool.truncated for tool in tools)
        else:
            text = row.text
        self.request_copy_text(text, incomplete)

    def request_copy_text(self, text: str, incomplete: bool) -> None:
        if self.copy_busy:
            self.write("Clipboard write already in progress.")
            return
        if not text:
            self.write("This row has no text to copy.")
            return
        self.copy_busy = True
        self.start(self.copy_content(text, incomplete))

    async def copy_content(self, text: str, incomplete: bool) -> None:
        try:
            await asyncio.to_thread(copy_text, text)
            self.write("Copied retained source to this computer's clipboard."
                       + (" WARNING: terminal content was truncated/evicted; this may not be the full output." if incomplete else ""))
        except (RuntimeError, UnicodeError) as error:
            self.write("Copy failed: " + display_text(str(error))[:512])
        finally:
            self.copy_busy = False
            self.app.invalidate()

    async def execute(self, line: str) -> None:
        try:
            keep_running = await asyncio.to_thread(self.client.command, line)
            if not keep_running:
                self.app.exit()
        except Exception as error:
            self.write("Command failed: " + display_text(str(error))[:1024])
        finally:
            self.command_busy = False
            self.app.invalidate()

    def config_payload(self, chat_id: str) -> dict[str, Any]:
        with self.client._lock:
            chat = self.client.chats.get(chat_id)
            if chat is None or not chat.agent_id or not chat.workspace:
                raise ValueError("Choose a chat first with /chats.")
            if chat.status in {"busy", "waitingApproval"}:
                raise ValueError("Chat is busy. Finish its task or approval before changing configuration.")
            return {"chatId": chat_id, "agentId": chat.agent_id, "workspacePath": chat.workspace,
                    "sessionId": chat.session_id, "sessionResumable": chat.resumable}

    async def config_request(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        assert self.client.runtime is not None
        responses = await asyncio.to_thread(self.client.runtime.websocket_responses, payload)
        for event in responses:
            update = event.get("update", {})
            if isinstance(update, dict) and update.get("status") == "failed":
                content = update.get("content", {})
                reason = content.get("error") if isinstance(content, dict) else None
                raise ValueError(str(reason or "Agent rejected the configuration request."))
            if event.get("error"):
                raise ValueError(str(event["error"]))
        options = next((event["update"].get("configOptions", []) for event in reversed(responses)
                        if isinstance(event.get("update"), dict)
                        and event["update"].get("sessionUpdate") == "config_option_update"), [])
        if not isinstance(options, list):
            raise ValueError("Agent returned an invalid configuration response.")
        return options

    async def open_config(self, chat_id: str, request_id: int, command: str) -> None:
        self.config_busy = True
        title = "Allow all" if command == "/allow-all" else "Model"
        try:
            payload = self.config_payload(chat_id)
            options = await self.config_request({**payload, "type": "session.refreshConfigOptions"})
            option = allow_all_option(options) if command == "/allow-all" else model_option(options)
            boolean = command == "/allow-all" and option is not None and option.get("type") == "boolean"
            choices = ([("false", "Off"), ("true", "On")] if boolean else
                       model_choices(option) if option and option.get("type") == "select" else [])
            if not option or not isinstance(option.get("id"), str) or not option["id"] or not choices:
                raise ValueError(f"This agent does not advertise a selectable {title.lower()} setting.")
            if self.client.selected != chat_id:
                self.write(f"{title} list loaded for the previous chat. Use {command} in the selected chat.")
                return
            if (request_id != self.model_request_id or self.buffer.text
                    or not self.app.layout.has_focus(self.input_control)):
                self.write(f"{title} list refreshed without interrupting input. Use {command} when ready.")
                return
            with self.client._lock:
                session = self.client.chats[chat_id].session_id
            current = config_value(option)
            self.picker = ConfigPicker(chat_id, session, option["id"], current, choices,
                                      next((i for i, (value, _) in enumerate(choices) if value == current), 0),
                                      command, boolean)
            self.app.layout.focus(self.picker_control)
        except Exception as error:
            self.write(title + ": " + display_text(str(error))[:1024])
        finally:
            self.config_busy = False
            self.app.invalidate()

    async def set_config(self, picker: ConfigPicker) -> None:
        self.config_busy = True
        try:
            if picker.command == "/allow-all" and not picker.confirming:
                raise ValueError("Permission changes require explicit confirmation.")
            payload = self.config_payload(picker.chat_id)
            if self.client.selected != picker.chat_id or payload["sessionId"] != picker.session_id:
                raise ValueError(f"Session changed. Reopen {picker.command} before selecting.")
            value, label = picker.choices[picker.selected]
            options = await self.config_request({
                **payload, "type": "session.setConfigOption", "configId": picker.config_id, "value": value,
            })
            actual = next((o for o in options if isinstance(o, dict) and o.get("id") == picker.config_id), None)
            actual_value = config_value(actual) if actual is not None and picker.boolean else (
                actual.get("currentValue") if actual is not None else None)
            if actual_value != value:
                raise ValueError(f"Agent did not confirm the selected setting; refresh {picker.command}.")
            self.write(f"[{self.client.chat_label(picker.chat_id)}] {picker.title} changed: {label}")
        except Exception as error:
            self.write(picker.title + " change failed: " + display_text(str(error))[:1024])
        finally:
            self.config_busy = False
            self.app.invalidate()

    def picker_text(self):
        picker = self.picker
        if picker is None:
            return []
        if picker.confirming:
            value, label = picker.choices[picker.selected]
            return [("", " Allow all | " + self.client.chat_label(picker.chat_id) + "\n\n"),
                    ("", f" Current: {display_text(picker.current)}\n Set to: {label} ({display_text(value)})\n\n"),
                    ("class:notice", " Enabling automatic permission may let the agent run commands\n"
                     " and modify files without asking. Scope: this shared session.\n"
                     " Existing pending approvals are not approved by this command.\n\n"),
                    ("", " y: apply | n: back | Esc: cancel (no change)")]
        lines = [("", f" {picker.title} | " + self.client.chat_label(picker.chat_id) + "\n"),
                 ("", " Up/Down choose | Enter confirm | Esc cancel\n\n")]
        start = max(0, picker.selected - 4)
        for i, (value, label) in enumerate(picker.choices[start:start + 8], start):
            style = "class:selected" if i == picker.selected else ""
            lines.append((style, f" {'>' if i == picker.selected else ' '} {i + 1}. {label}"
                          + (" [current]" if value == picker.current else "") + "\n"))
        lines.append(("", f"\n {picker.selected + 1}/{len(picker.choices)}"))
        if len(picker.choices) == 200:
            lines.append(("", " | First 200 choices (display limit)"))
        return lines

    def selected_entry(self) -> tuple[Entry | None, str | None]:
        target = self.conversation.target()
        if target is None:
            return None, None
        return next((r for r in self.transcript.entries if r.number == target[0]), None), target[1]

    def toggle(self) -> None:
        row, tool_id = self.selected_entry()
        if row is None or row.kind != "Tools":
            return
        self.conversation.follow = False
        if tool_id is None:
            row.expanded = not row.expanded
        else:
            tool = row.tools.get(tool_id)
            if tool is not None:
                tool.expanded = not tool.expanded
        self.transcript.touch(row)
        self.app.invalidate()

    def change_page(self, delta: int) -> None:
        row, tool_id = self.selected_entry()
        if row is None:
            return
        if tool_id is None:
            row.page = max(0, min((len(row.text) - 1) // Transcript.PAGE_SIZE, row.page + delta))
        elif tool_id in row.tools:
            tool = row.tools[tool_id]
            size = len(json.dumps(tool.fields, ensure_ascii=False, indent=2))
            tool.page = max(0, min((size - 1) // Transcript.PAGE_SIZE, tool.page + delta))
        self.transcript.touch(row)

    def text_lines(self, row: Entry, width: int) -> list[list[tuple[str, str]]]:
        key = (row.revision, row.page, width)
        cached = self.render_cache.get(row.number)
        if cached is not None and cached[0] == key:
            return cached[1]
        start = row.page * Transcript.PAGE_SIZE
        source = row.text[start:start + Transcript.PAGE_SIZE]
        output: list[str] = []
        notices: list[str] = []
        if row.kind == "Agent" and len(row.text) <= Transcript.PAGE_SIZE:
            renderer = MarkdownStream(output.append, notices.append, lambda: width)
            renderer.feed(source)
            renderer.finish()
            fragments = to_formatted_text(ANSI("".join(output)))
            lines = list(split_lines(fragments))
        else:
            lines = [[("", line)] for line in source.split("\n")]
        if len(row.text) > Transcript.PAGE_SIZE:
            count = (len(row.text) + Transcript.PAGE_SIZE - 1) // Transcript.PAGE_SIZE
            lines.insert(0, [("class:muted", f"Long content shown as source | page {row.page + 1}/{count} | Left/Right")])
        if row.truncated:
            lines.append([("class:notice", "[Terminal display limit: content omitted; Android delivery is unchanged.]")])
        lines.extend([[("class:notice", notice)] for notice in notices])
        self.render_cache[row.number] = (key, lines)
        return lines

    def render_conversation(self, width: int):
        lines: list[list[tuple[str, str]]] = []
        targets: dict[int, tuple[int, str | None]] = {}
        retained = {row.number for row in self.transcript.entries}
        self.render_cache = {key: value for key, value in self.render_cache.items() if key in retained}
        self.line_cache = {key: value for key, value in self.line_cache.items() if key in retained}
        if self.transcript.evicted:
            lines.append([("class:notice", f"[{self.transcript.evicted} old display entries evicted; Android history is unchanged.]")])
        for row in self.transcript.visible(self.client.selected):
            cache_key = (row.revision, width)
            cached = self.line_cache.get(row.number)
            if cached is not None and cached[0] == cache_key:
                offset = len(lines)
                lines.extend(cached[1])
                targets.update({offset + line: target for line, target in cached[2].items()})
                continue
            row_start = len(lines)
            targets[len(lines)] = (row.number, None)
            if row.kind == "Tools":
                failed = sum(t.status == "failed" for t in row.tools.values())
                running = sum(t.status not in {"completed", "failed", "cancelled"} for t in row.tools.values())
                lines.append([("class:failed" if failed else "class:tool",
                               f"{'v' if row.expanded else '>'} Tools | {len(row.tools)} total | {running} running | {failed} failed")])
                if failed and not row.expanded:
                    lines.extend([[("class:failed", "  Failed: " + t.title)] for t in row.tools.values() if t.status == "failed"])
                if row.truncated:
                    lines.append([("class:notice", "  Older tools omitted from this group (terminal display limit).")])
                if row.expanded:
                    for identity, tool in row.tools.items():
                        targets[len(lines)] = (row.number, identity)
                        lines.append([("class:failed" if tool.status == "failed" else "class:tool",
                                       f"  {'v' if tool.expanded else '>'} {tool.title} [{tool.status}]")])
                        if tool.expanded:
                            text = json.dumps(tool.fields, ensure_ascii=False, indent=2)
                            tool.page = min(tool.page, max(0, (len(text) - 1) // Transcript.PAGE_SIZE))
                            part = text[tool.page * Transcript.PAGE_SIZE:(tool.page + 1) * Transcript.PAGE_SIZE]
                            if len(text) > Transcript.PAGE_SIZE:
                                lines.append([("class:muted", f"  Details page {tool.page + 1}/{(len(text) + Transcript.PAGE_SIZE - 1) // Transcript.PAGE_SIZE} | Left/Right")])
                            lines.extend([[("", "    " + line)] for line in part.split("\n")])
                            if tool.truncated:
                                lines.append([("class:notice", "  Details incomplete: terminal display limit. Review full output on Android.")])
            else:
                lines.append([("class:notice" if row.kind == "Notice" else "bold", row.kind + " >")])
                lines.extend(self.text_lines(row, width))
            lines.append([("", "")])
            row_lines = lines[row_start:]
            row_targets = {line - row_start: target for line, target in targets.items() if line >= row_start}
            wrapped: list[list[tuple[str, str]]] = []
            wrapped_targets = {}
            for line_number, fragments in enumerate(row_lines):
                if line_number in row_targets:
                    wrapped_targets[len(wrapped)] = row_targets[line_number]
                wrapped.extend(wrap_display_line(fragments, width))
            lines[row_start:] = wrapped
            targets = {line: target for line, target in targets.items() if line < row_start}
            targets.update({row_start + line: target for line, target in wrapped_targets.items()})
            self.line_cache[row.number] = (cache_key, wrapped, wrapped_targets)
        return lines or [[("", "Waiting for a shared chat...")]], targets

    async def run(self) -> None:
        async def refresh_loop() -> None:
            while True:
                self.client.drain(allow_auto_select=not self.buffer.text and not self.picker)
                for _ in range(128):
                    try:
                        notice = self.notices.get_nowait()
                    except queue.Empty:
                        break
                    self.transcript.add("", "", "Notice", notice.rstrip("\n"))
                if self.notice_drops:
                    self.transcript.add("", "", "Notice", "Terminal notice queue overflow; some notices omitted. /approvals refreshes pending requests.")
                    self.notice_drops = 0
                    with self.client._lock:
                        self.client._reviewed.clear()
                if self.review_eviction != self.transcript.evicted:
                    self.review_eviction = self.transcript.evicted
                    with self.client._lock:
                        self.client._reviewed.clear()
                if not self.server_alive():
                    self.app.exit(exception=RuntimeError("Bridge listener stopped unexpectedly."))
                    return
                self.app.invalidate()
                await asyncio.sleep(0.1)

        async def refresh() -> None:
            try:
                await refresh_loop()
            except Exception as error:
                self.app.exit(exception=error)

        class DisplayStream:
            def write(stream, text: str) -> int:
                self.write(text)
                return len(text)

            def flush(stream) -> None:
                pass

            def isatty(stream) -> bool:
                return False

        renderer = asyncio.create_task(refresh())
        try:
            # Capture Python-side tunnel/runtime output without writing over the alternate screen.
            # Application.output already owns the original terminal stream.
            with redirect_stdout(DisplayStream()), redirect_stderr(DisplayStream()):
                try:
                    await self.app.run_async()
                except EOFError:
                    pass
        finally:
            self.client.close()
            renderer.cancel()
            await asyncio.gather(renderer, return_exceptions=True)
            # Threads already inside a config request finish under the agent's bounded timeout.
            if self.tasks:
                await asyncio.gather(*self.tasks, return_exceptions=True)
            self.client.event_sink = None
            if self.client.runtime and self.previous_console_write:
                self.client.runtime.console.write = self.previous_console_write
