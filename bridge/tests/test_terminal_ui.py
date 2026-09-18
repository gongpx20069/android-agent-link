from __future__ import annotations

import asyncio
import importlib.util
import json
import threading
import unittest
from unittest.mock import patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.acp_agent import AcpAgentError
from android_acp_bridge.console_log import ConsoleLog
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.terminal import TerminalClient
from android_acp_bridge.terminal_state import Transcript, project_tool, model_choices, allow_all_option, config_value
from test_runtime import FakeAgentManager, BlockingAgentManager

HAS_INTERACTIVE = all(importlib.util.find_spec(module) for module in ("prompt_toolkit", "rich", "markdown_it"))


def update(kind: str, chat: str = "one", operation: str = "op", **fields):
    return {"type": "session/update", "chatId": chat, "operationId": operation,
            "update": {"sessionUpdate": kind, **fields}}


class PermissionAgentManager(FakeAgentManager):
    def __init__(self, option=None, error=None, confirm=True):
        super().__init__()
        self.option = option or {"id": "allow_all", "name": "Allow All", "type": "boolean", "currentValue": False}
        self.changes = []
        self.error = error
        self.confirm = confirm

    def refresh_config_options(self, *args, **kwargs):
        return [update("config_option_update", configOptions=[self.option.copy()])]

    def set_config_option(self, chat_id, agent_id, workspace_path, config_id, value, *args):
        self.changes.append((chat_id, config_id, value))
        if self.error:
            raise AcpAgentError(self.error)
        if self.confirm:
            self.option["currentValue"] = value == "true" if self.option["type"] == "boolean" else value
        return self.refresh_config_options()


class TranscriptTests(unittest.TestCase):
    def test_tool_deltas_keep_fields_replace_arrays_and_isolate_turns(self):
        state = Transcript()
        first = {"type": "session/update", "kind": "tool_call", "chatId": "one", "operationId": "op",
                 "tool": "same", "title": "Read", "fields": {"content": ["first"], "rawInput": {"path": "file"}}}
        state.event(first)
        row = state.entries[0]
        row.expanded = True
        row.tools["same"].expanded = True
        state.event({**first, "kind": "tool_call_update", "title": "", "status": "completed",
                     "fields": {"content": ["replacement"]}})
        self.assertEqual(len(state.entries), 1)
        tool = row.tools["same"]
        self.assertEqual(tool.fields["content"], ["replacement"])
        self.assertEqual(tool.fields["rawInput"], {"path": "file"})
        self.assertEqual(tool.title, "Read")
        self.assertTrue(tool.expanded)
        state.event({**first, "operationId": "next"})
        state.event({**first, "chatId": "two"})
        self.assertEqual(len(state.entries), 3)

    def test_projection_is_bounded_and_strips_terminal_controls(self):
        projected, clipped = project_tool({"rawOutput": "\x1b]52;c;secret\x07" + "x" * 500000,
                                           "content": [{"nested": "y" * 500000}]})
        self.assertTrue(clipped)
        self.assertLess(len(json.dumps(projected)), 34000)
        self.assertNotIn("\x1b", json.dumps(projected))
        self.assertNotIn("secret", json.dumps(projected))

    def test_lifetime_history_and_one_reply_have_explicit_bounds(self):
        state = Transcript()
        for index in range(1000):
            state.add("one", str(index), "Agent", "x" * 20000)
        self.assertLessEqual(len(state.entries), state.MAX_ENTRIES)
        self.assertLessEqual(state.characters, state.MAX_CHARACTERS)
        self.assertGreater(state.evicted, 0)
        state.event({"type": "session/update", "kind": "agent_message_chunk", "chatId": "one",
                     "operationId": "stream", "text": "y" * 500000})
        self.assertTrue(state.entries[-1].truncated)
        self.assertLessEqual(len(state.entries[-1].text), state.MAX_TEXT)

    def test_grouped_model_choices_keep_values_and_literal_labels(self):
        self.assertEqual(model_choices({"options": [
            {"group": "Provider", "options": [{"value": "model-a", "name": "\x1b[2JModel A"}]},
            {"value": "model-b", "name": "Model B"},
        ]}), [("model-a", "Model A"), ("model-b", "Model B")])

    def test_permission_names_match_android_and_booleans_keep_wire_values(self):
        for key in ("allow_all", "allowAll", "Allow All", "allow-all-permissions", "autoApprove", "autoApproval"):
            for field in ("id", "category", "name"):
                option = {field: key}
                self.assertIs(allow_all_option([None, {}, option]), option)
        self.assertIsNone(allow_all_option([{"id": "model"}]))
        self.assertEqual(config_value({"currentValue": True}), "true")
        self.assertEqual(config_value({"currentValue": False}), "false")
        self.assertEqual(config_value({"currentValue": "custom"}), "custom")


class SharedConfigTests(unittest.TestCase):
    def runtime(self, manager=None):
        client = TerminalClient(lambda _: None)
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(),
                                agent_manager=manager or FakeAgentManager(), local_client=client,
                                console=ConsoleLog("error"))
        client.runtime = runtime
        self.addCleanup(client.close)
        return runtime, client

    def test_config_broadcast_preserves_phone_subscriber_and_sequence(self):
        runtime, client = self.runtime()
        phone = []
        subscriber = phone.append
        runtime.websocket_responses({"type": "chat.attach", "chatId": "one",
                                     "agentId": "copilot-cli", "workspacePath": "D:\\repo"}, emit=subscriber)
        responses = runtime.websocket_responses({"type": "session.setConfigOption", "chatId": "one",
                                                "configId": "model", "value": "model-b"})
        config = next(e for e in responses if e.get("update", {}).get("sessionUpdate") == "config_option_update")
        self.assertIn("eventId", config)
        self.assertEqual(phone.count(config), 1)
        self.assertIs(runtime._chat_emitters["one"], subscriber)
        self.assertEqual(client.chats["one"].config_options[0]["currentValue"], "model-b")
        self.assertIn(config, runtime._event_logs["one"])

    def test_busy_chat_rejects_config_without_waiting_on_agent_lock(self):
        manager = BlockingAgentManager()
        runtime, _ = self.runtime(manager)
        runtime.websocket_responses({"type": "chat.prompt", "chatId": "one", "content": "working",
                                     "operationId": "op", "workspacePath": "D:\\repo"}, emit=lambda _: None)
        self.assertTrue(manager.started.wait(2))
        try:
            for kind in ("session.refreshConfigOptions", "session.setConfigOption"):
                responses = runtime.websocket_responses({"type": kind, "chatId": "one", "configId": "model", "value": "x"})
                self.assertEqual(responses[0]["update"]["status"], "failed")
                self.assertIn("busy", responses[0]["update"]["content"]["error"])
        finally:
            manager.release.set()

    def test_config_reservation_rejects_prompt_and_history_then_releases(self):
        entered, release = threading.Event(), threading.Event()

        class Manager(FakeAgentManager):
            def refresh_config_options(self, *args, **kwargs):
                entered.set()
                if not release.wait(3):
                    raise AssertionError("test config stuck")
                return super().refresh_config_options(*args, **kwargs)

        runtime, _ = self.runtime(Manager())
        responses = []
        thread = threading.Thread(target=lambda: responses.extend(runtime.websocket_responses(
            {"type": "session.refreshConfigOptions", "chatId": "one"})))
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            prompt = runtime.websocket_responses({"type": "chat.prompt", "chatId": "one", "content": "do not start"}, emit=lambda _: None)
            self.assertEqual(prompt[0]["status"], "failed")
            self.assertFalse(runtime._active_prompts)
            history = runtime.websocket_responses({"type": "session.loadRecent", "chatId": "one", "sessionId": "session"})
            self.assertIn("error", history[0])
            legacy = runtime.websocket_responses({"type": "session.load", "chatId": "one", "sessionId": "session"})
            self.assertEqual(legacy[0]["update"]["status"], "failed")
            self.assertIn("busy", legacy[0]["update"]["content"]["error"])
        finally:
            release.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertNotIn("one", runtime._configuring_chats)
        self.assertTrue(responses)

    def test_legacy_session_load_also_reserves_configuration(self):
        entered, release = threading.Event(), threading.Event()

        class Manager(FakeAgentManager):
            def load_session(self, *args, **kwargs):
                entered.set()
                if not release.wait(3):
                    raise AssertionError("test load stuck")
                return super().load_session(*args, **kwargs)

        runtime, _ = self.runtime(Manager())
        responses = []
        thread = threading.Thread(target=lambda: responses.extend(runtime.websocket_responses(
            {"type": "session.load", "chatId": "one", "sessionId": "session"})))
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            config = runtime.websocket_responses({"type": "session.refreshConfigOptions", "chatId": "one"})
            self.assertEqual(config[0]["update"]["status"], "failed")
        finally:
            release.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertNotIn("one", runtime._history_loading_chats)
        self.assertTrue(responses)


@unittest.skipUnless(HAS_INTERACTIVE, "Install interactive dependencies")
class FullScreenTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from prompt_toolkit.input import create_pipe_input
        from prompt_toolkit.output import DummyOutput
        from android_acp_bridge.terminal_ui import FullScreenTerminal
        self.pipe_context = create_pipe_input()
        self.pipe = self.pipe_context.__enter__()
        self.client = TerminalClient(lambda _: None)
        self.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(),
                                     agent_manager=FakeAgentManager(), local_client=self.client,
                                     console=ConsoleLog("error"))
        self.client.runtime = self.runtime
        self.phone = []
        self.runtime.websocket_responses({"type": "chat.attach", "chatId": "one", "agentId": "copilot-cli",
                                         "workspacePath": "D:\\repo"}, emit=self.phone.append)
        self.ui = FullScreenTerminal(self.client, lambda: True, input=self.pipe, output=DummyOutput())
        self.task = asyncio.create_task(self.ui.run())
        await asyncio.sleep(0.15)

    async def asyncTearDown(self):
        if not self.task.done():
            self.ui.app.exit()
        await asyncio.wait_for(self.task, 5)
        self.pipe_context.__exit__(None, None, None)

    def screen(self):
        screen = self.ui.app.renderer._last_screen
        self.assertIsNotNone(screen)
        return "\n".join("".join(row[x].char for x in sorted(row)) for _, row in sorted(screen.data_buffer.items()))

    async def test_click_input_after_browsing_restores_visible_typing_and_follow(self):
        for index in range(70):
            self.ui.transcript.add("one", str(index), "Agent", f"History {index}")
        await asyncio.sleep(0.2)
        self.pipe.send_bytes(b"\t\x1b[H")
        await asyncio.sleep(0.2)
        screen = self.ui.app.renderer._last_screen
        _, position = next(
            (window, position) for window, position in screen.visible_windows_to_write_positions.items()
            if window.content is self.ui.input_control
        )
        x, y = position.xpos + 9, position.ypos + 1
        self.pipe.send_text(f"\x1b[<0;{x};{y}M\x1b[<0;{x};{y}m")
        self.pipe.send_text("visible after click")
        await asyncio.sleep(0.25)
        self.assertTrue(self.ui.app.layout.has_focus(self.ui.input_control))
        self.assertEqual(self.ui.buffer.text, "visible after click")
        self.assertIn("visible after click", self.screen())
        self.client.observe_event(update("agent_message_chunk", text="LATEST-AFTER-CLICK"))
        await asyncio.sleep(0.25)
        self.assertIn("LATEST-AFTER-CLICK", self.screen())

    async def test_click_already_focused_input_resumes_after_mouse_scroll(self):
        for index in range(70):
            self.ui.transcript.add("one", str(index), "Agent", f"History {index}")
        await asyncio.sleep(0.2)
        self.pipe.send_text("\x1b[<64;10;4M")
        await asyncio.sleep(0.2)
        self.assertTrue(self.ui.app.layout.has_focus(self.ui.input_control))
        self.assertFalse(self.ui.conversation.follow)
        screen = self.ui.app.renderer._last_screen
        position = next(position for window, position in screen.visible_windows_to_write_positions.items()
                        if window.content is self.ui.input_control)
        self.pipe.send_text(f"\x1b[<0;9;{position.ypos + 1}M\x1b[<0;9;{position.ypos + 1}m")
        await asyncio.sleep(0.2)
        self.assertTrue(self.ui.conversation.follow)
        self.client.observe_event(update("agent_message_chunk", text="LATEST-AFTER-WHEEL"))
        await asyncio.sleep(0.2)
        self.assertIn("LATEST-AFTER-WHEEL", self.screen())

    async def test_paste_from_conversation_is_visible_and_does_not_submit(self):
        self.pipe.send_bytes(b"\t\x1b[H")
        await asyncio.sleep(0.15)
        self.pipe.send_text("\x1b[200~pasted draft\x1b[201~")
        await asyncio.sleep(0.2)
        self.assertEqual(self.ui.buffer.text, "pasted draft")
        self.assertIn("pasted draft", self.screen())
        self.assertTrue(self.ui.conversation.follow)
        self.assertFalse(self.runtime._prompt_operations)

    async def test_input_and_latest_reply_are_visible_on_short_resized_screen(self):
        from prompt_toolkit.data_structures import Size
        for index in range(70):
            self.ui.transcript.add("one", str(index), "Agent", f"History {index}")
        for height, width in ((24, 80), (15, 30)):
            with patch.object(self.ui.app.output, "get_size", return_value=Size(rows=height, columns=width)):
                self.ui.app.invalidate()
                self.ui.buffer.reset()
                self.pipe.send_text("visible draft")
                self.client.observe_event(update("agent_message_chunk", operation=str(width), text=f"LATEST-{width}"))
                for _ in range(40):
                    if "visible draft" in self.screen() and f"LATEST-{width}" in self.screen():
                        break
                    await asyncio.sleep(0.05)
                self.assertIn("visible draft", self.screen())
                self.assertIn(f"LATEST-{width}", self.screen())
                self.assertTrue(self.ui.app.layout.has_focus(self.ui.input_control))

    async def test_typing_from_conversation_and_sending_resumes_latest_view(self):
        for index in range(70):
            self.ui.transcript.add("one", str(index), "Agent", f"History {index}")
        await asyncio.sleep(0.2)
        self.pipe.send_bytes(b"\t\x1b[H")
        await asyncio.sleep(0.2)
        self.pipe.send_text("new question")
        await asyncio.sleep(0.2)
        self.assertEqual(self.ui.buffer.text, "new question")
        self.assertIn("new question", self.screen())
        self.pipe.send_bytes(b"\r")
        await asyncio.sleep(0.25)
        self.client.observe_event(update("agent_message_chunk", operation="last", text="LATEST-AFTER-SEND"))
        await asyncio.sleep(0.25)
        self.assertIn("LATEST-AFTER-SEND", self.screen())
        self.assertTrue(self.ui.conversation.follow)

    async def test_tab_focus_alone_does_not_pause_follow_and_escape_returns_latest(self):
        for index in range(70):
            self.ui.transcript.add("one", str(index), "Agent", f"History {index}")
        await asyncio.sleep(0.2)
        self.pipe.send_bytes(b"\t")
        await asyncio.sleep(0.2)
        self.client.observe_event(update("agent_message_chunk", text="LATEST-WHILE-FOCUSED"))
        await asyncio.sleep(0.25)
        self.assertIn("LATEST-WHILE-FOCUSED", self.screen())
        self.assertTrue(self.ui.conversation.follow)
        self.pipe.send_bytes(b"\x1b[H")
        await asyncio.sleep(0.2)
        self.client.observe_event(update("agent_message_chunk", text="PAUSED-HISTORY-ARRIVAL"))
        await asyncio.sleep(0.2)
        self.assertNotIn("PAUSED-HISTORY-ARRIVAL", self.screen())
        self.pipe.send_bytes(b"\x1b")
        await asyncio.sleep(0.6)
        self.assertIn("PAUSED-HISTORY-ARRIVAL", self.screen())
        self.assertTrue(self.ui.conversation.follow)

    async def test_real_screen_draft_tool_fold_details_and_failure_summary(self):
        self.pipe.send_text("unfinished draft")
        await asyncio.sleep(0.1)
        self.client.observe_event(update("tool_call", toolCallId="tool", title="Read file", status="running",
                                         rawInput={"path": "README"}, rawOutput="SECRET_DETAIL"))
        await asyncio.sleep(0.2)
        self.assertEqual(self.ui.buffer.text, "unfinished draft")
        self.assertIn("> Tools", self.screen())
        self.assertNotIn("SECRET_DETAIL", self.screen())
        self.ui.submit("/tools")
        await asyncio.sleep(0.15)
        self.assertIn("> Read file", self.screen())
        self.pipe.send_bytes(b"\x1b[B\r")  # Select first tool and expand.
        await asyncio.sleep(0.2)
        self.assertIn("SECRET_DETAIL", self.screen())
        self.assertEqual(self.ui.buffer.text, "unfinished draft")
        self.client.observe_event(update("tool_call_update", toolCallId="tool", status="failed"))
        await asyncio.sleep(0.15)
        tool = next(r for r in self.ui.transcript.entries if r.kind == "Tools").tools["tool"]
        self.assertTrue(tool.expanded)
        self.assertEqual(tool.fields["rawInput"], {"path": "README"})
        self.pipe.send_bytes(b"\x1b")
        await asyncio.sleep(0.6)
        self.assertTrue(self.ui.app.layout.has_focus(self.ui.input_control))

    async def test_model_selector_uses_actual_config_and_broadcasts(self):
        self.pipe.send_text("/model\r")
        await asyncio.sleep(0.4)
        self.assertIsNotNone(self.ui.picker)
        self.assertIn("gpt-5.4", self.screen())
        self.assertFalse(self.runtime._prompt_operations)
        self.pipe.send_bytes(b"\r")
        await asyncio.sleep(0.4)
        self.assertIsNone(self.ui.picker)
        config = [e for e in self.phone if e.get("update", {}).get("sessionUpdate") == "config_option_update"]
        self.assertGreaterEqual(len(config), 2)
        self.assertIn("Model changed", self.screen())

    async def test_busy_model_is_rejected_and_unknown_slash_is_not_sent(self):
        self.client.observe_event({"type": "chat.status", "chatId": "one", "status": "busy"})
        self.ui.submit("/model")
        await asyncio.sleep(0.2)
        self.assertIsNone(self.ui.picker)
        self.assertIn("Chat is busy", self.screen())
        self.ui.submit("/unknown-dangerous")
        await asyncio.sleep(0.2)
        self.assertFalse(self.runtime._prompt_operations)

    async def test_slash_menu_and_advertised_command_dispatch(self):
        from prompt_toolkit.document import Document
        from prompt_toolkit.completion import CompleteEvent
        self.client.observe_event(update("available_commands_update", availableCommands=[
            {"name": "explain", "description": "Explain this workspace"}]))
        completions = list(self.ui.buffer.completer.get_completions(Document("/"), CompleteEvent()))
        self.assertIn("/model", [c.text for c in completions])
        self.assertIn("/qrcode", [c.text for c in completions])
        self.assertIn("/explain", [c.text for c in completions])
        self.ui.submit("/explain current changes")
        await asyncio.sleep(0.3)
        accepted = next(e for e in self.phone if e["type"] == "operation.accepted")
        self.assertEqual(accepted["content"], "/explain current changes")

    async def test_scroll_does_not_follow_stream_and_resize_preserves_draft(self):
        from prompt_toolkit.data_structures import Size
        for index in range(20):
            self.ui.transcript.add("one", str(index), "Agent", f"Message {index}")
        await asyncio.sleep(0.2)
        self.pipe.send_text("draft")
        self.pipe.send_bytes(b"\t\x1b[H")
        await asyncio.sleep(0.2)
        self.assertFalse(self.ui.conversation.follow)
        self.client.observe_event(update("agent_message_chunk", text="new message"))
        await asyncio.sleep(0.2)
        self.assertTrue(self.ui.conversation.new_messages)
        self.assertLess(self.ui.conversation.cursor, len(self.ui.conversation.lines) - 1)
        with patch.object(self.ui.app.output, "get_size", return_value=Size(rows=15, columns=30)):
            self.ui.app.invalidate()
            await asyncio.sleep(0.2)
            self.assertFalse(self.task.done())
            self.assertEqual(self.ui.buffer.text, "draft")

    async def test_slow_model_refresh_does_not_freeze_input_and_cannot_retarget(self):
        entered, release = threading.Event(), threading.Event()

        class Manager(FakeAgentManager):
            def refresh_config_options(self, *args, **kwargs):
                entered.set()
                if not release.wait(3):
                    raise AssertionError("stuck")
                return super().refresh_config_options(*args, **kwargs)

        self.runtime.agent_manager = Manager()
        self.ui.submit("/model")
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 2))
            self.pipe.send_text("draft while loading")
            await asyncio.sleep(0.15)
            self.assertEqual(self.ui.buffer.text, "draft while loading")
            self.client.observe_request({"type": "chat.attach", "chatId": "two", "agentId": "copilot-cli",
                                         "workspacePath": "D:\\other"})
            self.client._select_chat("two")
        finally:
            release.set()
        await asyncio.sleep(0.3)
        self.assertIsNone(self.ui.picker)
        self.assertEqual(self.client.selected, "two")
        self.assertIn("previous chat", self.screen())
        self.assertFalse(self.runtime._prompt_operations)

    async def test_unsupported_model_failure_and_stale_picker_are_not_success(self):
        class Unsupported(FakeAgentManager):
            def refresh_config_options(self, *args, **kwargs):
                return []

        self.runtime.agent_manager = Unsupported()
        self.ui.submit("/model")
        await asyncio.sleep(0.2)
        self.assertIsNone(self.ui.picker)
        self.assertIn("does not advertise", self.screen())
        self.runtime.agent_manager = FakeAgentManager()
        self.ui.submit("/model")
        await asyncio.sleep(0.2)
        picker = self.ui.picker
        self.assertIsNotNone(picker)
        self.client.chats["one"].session_id = "changed-session"
        self.pipe.send_bytes(b"\r")
        await asyncio.sleep(0.2)
        self.assertIn("Session changed", self.screen())
        self.assertFalse(self.runtime._configuring_chats)

    async def test_pairing_view_preserves_qr_outside_transcript_and_restores_draft(self):
        self.client.pairing_display = "QR ROW ONE\nQR ROW TWO\nacpclient://pair?private-link"
        self.pipe.send_text("draft")
        await asyncio.sleep(0.1)
        self.ui.submit("/pairing")
        await asyncio.sleep(0.2)
        self.assertIn("QR ROW ONE", self.screen())
        self.assertTrue(self.ui.show_pairing)
        self.assertFalse(any("private-link" in r.text for r in self.ui.transcript.entries))
        self.pipe.send_bytes(b"\x1b")
        await asyncio.sleep(0.6)
        self.assertFalse(self.ui.show_pairing)
        self.assertEqual(self.ui.buffer.text, "draft")

    async def test_qrcode_command_stays_visible_during_streaming(self):
        self.client.pairing_display = "QR-ON-DEMAND\nacpclient://pair?private-link"
        self.assertFalse(self.ui.show_pairing)
        self.pipe.send_text("/qrcode\r")
        await asyncio.sleep(0.2)
        self.assertTrue(self.ui.show_pairing)
        for index in range(30):
            self.client.observe_event(update("agent_message_chunk", text=f"incoming {index}"))
        await asyncio.sleep(0.3)
        self.assertIn("QR-ON-DEMAND", self.screen())
        self.assertNotIn("incoming 29", self.screen())
        self.assertFalse(any("private-link" in row.text for row in self.ui.transcript.entries))
        self.pipe.send_bytes(b"\x1b")
        await asyncio.sleep(0.6)
        self.assertFalse(self.ui.show_pairing)
        self.assertIn("incoming 29", self.screen())

    async def test_copy_latest_uses_retained_source_not_page_or_other_chat(self):
        source = "# Title\n```python\n  print('\u4e2d\u6587')\n```\n" + "body\n" * 3000
        row = self.ui.transcript.add("one", "copy", "Agent", source)
        row.page = 1
        self.ui.transcript.add("two", "other", "Agent", "wrong chat")
        with patch("android_acp_bridge.terminal_ui.copy_text") as copy:
            self.pipe.send_text("/copy\r")
            await asyncio.sleep(0.3)
            copy.assert_called_once_with(source)
        self.assertFalse(self.runtime._prompt_operations)
        self.assertIn("Copied retained source", self.screen())

    async def test_copy_selected_tool_warns_when_truncated_and_preserves_draft(self):
        self.client.observe_event(update("tool_call", toolCallId="tool", rawOutput="x" * 100000))
        await asyncio.sleep(0.2)
        self.pipe.send_text("draft")
        await asyncio.sleep(0.1)
        self.ui.submit("/tools")
        with patch("android_acp_bridge.terminal_ui.copy_text") as copy:
            self.pipe.send_bytes(b"\x1b[B\x19")
            await asyncio.sleep(0.3)
            self.assertEqual(copy.call_count, 1)
            copied = json.loads(copy.call_args.args[0])
            self.assertLess(len(copied[0]["rawOutput"]), 100000)
        self.assertEqual(self.ui.buffer.text, "draft")
        self.assertTrue(any("not be the full output" in row.text for row in self.ui.transcript.entries))

    async def test_copy_includes_reply_segments_around_tools_and_mouse_toggle(self):
        self.ui.transcript.add("one", "op", "Agent", "Before tool")
        self.ui.transcript.add("one", "op", "Tools")
        self.ui.transcript.add("one", "op", "Agent", "After tool")
        with patch("android_acp_bridge.terminal_ui.copy_text") as copy:
            self.ui.submit("/copy")
            await asyncio.sleep(0.2)
            copy.assert_called_once_with("Before tool\n\nAfter tool")
        self.ui.submit("/mouse")
        self.assertFalse(self.ui.app.mouse_support())
        self.ui.submit("/mouse")
        self.assertTrue(self.ui.app.mouse_support())

    async def test_copy_errors_and_no_automatic_clipboard_writes(self):
        with patch("android_acp_bridge.terminal_ui.copy_text", side_effect=RuntimeError("Clipboard unavailable")) as copy:
            self.client.observe_event(update("agent_message_chunk", text="sensitive"))
            await asyncio.sleep(0.2)
            copy.assert_not_called()
            self.ui.submit("/copy")
            await asyncio.sleep(0.25)
            copy.assert_called_once_with("sensitive")
            self.assertIn("Copy failed", self.screen())
            self.assertFalse(self.ui.copy_busy)
            self.assertNotIn("Copied retained source", self.screen())

    async def test_wrapped_unbroken_tool_output_is_scrollable_and_cjk_safe(self):
        from android_acp_bridge.terminal_ui import wrap_display_line
        from prompt_toolkit.utils import get_cwidth
        rows = wrap_display_line([("bold", "\u4e2d\u6587" * 100 + "\tend")], 19)
        self.assertGreater(len(rows), 10)
        self.assertTrue(all(get_cwidth("".join(text for _, text in line)) <= 19 for line in rows))
        self.client.observe_event(update("tool_call", toolCallId="long", title="Long", rawOutput="x" * 20000))
        await asyncio.sleep(0.2)
        self.ui.submit("/tools")
        self.pipe.send_bytes(b"\x1b[B\r")
        await asyncio.sleep(0.2)
        self.assertIn("Details page 1/", self.screen())
        self.pipe.send_bytes(b"\x1b[C")
        await asyncio.sleep(0.2)
        row = next(r for r in self.ui.transcript.entries if r.kind == "Tools")
        self.assertEqual(row.tools["long"].page, 1)

    async def test_model_refresh_cannot_steal_focus_from_a_new_draft(self):
        entered, release = threading.Event(), threading.Event()

        class Manager(FakeAgentManager):
            def refresh_config_options(self, *args, **kwargs):
                entered.set()
                if not release.wait(3):
                    raise AssertionError("stuck")
                return super().refresh_config_options(*args, **kwargs)

        self.runtime.agent_manager = Manager()
        self.ui.submit("/model")
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 2))
            self.pipe.send_text("keep typing")
            await asyncio.sleep(0.15)
        finally:
            release.set()
        await asyncio.sleep(0.2)
        self.assertIsNone(self.ui.picker)
        self.assertTrue(self.ui.app.layout.has_focus(self.ui.input_control))
        self.assertEqual(self.ui.buffer.text, "keep typing")
        self.assertIn("without interrupting", self.screen())

    async def test_reserved_commands_and_display_eviction_cannot_authorize(self):
        self.ui.submit("/resume")
        await asyncio.sleep(0.2)
        self.assertFalse(self.runtime._prompt_operations)
        self.assertIn("Android", self.screen())
        self.client._reviewed.add("old-review")
        for index in range(200):
            self.ui.transcript.add("one", str(index), "Agent", "evict")
        await asyncio.sleep(0.2)
        self.assertFalse(self.client._reviewed)
        self.client._reviewed.add("lost-review")
        for index in range(150):
            self.ui.write(str(index))
        self.assertFalse(self.client._reviewed)

    async def test_allow_all_requires_confirmation_and_syncs_on_and_off(self):
        manager = PermissionAgentManager()
        self.runtime.agent_manager = manager
        subscriber = self.runtime._chat_emitters["one"]
        for command, value, keys in (("/allow-all", "true", b"\x1b[B\r"), ("/allow_all", "false", b"\x1b[A\r")):
            self.pipe.send_text(command + "\r")
            await asyncio.sleep(0.25)
            self.assertIsNotNone(self.ui.picker)
            count = len(manager.changes)
            self.pipe.send_bytes(keys)
            await asyncio.sleep(0.15)
            self.assertTrue(self.ui.picker.confirming)
            self.assertIn("without asking", self.screen())
            self.assertEqual(len(manager.changes), count)
            self.pipe.send_bytes(b"\r")
            await asyncio.sleep(0.1)
            self.assertEqual(len(manager.changes), count)
            self.pipe.send_text("y")
            await asyncio.sleep(0.25)
            self.assertEqual(manager.changes[-1], ("one", "allow_all", value))
            self.assertIsNone(self.ui.picker)
            self.assertIn("Allow all changed", self.screen())
            self.assertEqual(config_value(self.client.chats["one"].config_options[0]), value)
        self.assertIs(self.runtime._chat_emitters["one"], subscriber)
        configs = [e for e in self.phone if e.get("update", {}).get("sessionUpdate") == "config_option_update"]
        self.assertTrue(all("eventId" in e for e in configs))
        self.assertEqual(configs[-1]["update"]["configOptions"][0]["currentValue"], False)
        self.assertFalse(self.runtime._prompt_operations)

    async def test_allow_all_cancel_back_and_inline_values_do_not_apply(self):
        manager = PermissionAgentManager()
        self.runtime.agent_manager = manager
        self.ui.submit("/allow-all true")
        await asyncio.sleep(0.15)
        self.assertIsNone(self.ui.picker)
        self.ui.submit("/allow-all")
        await asyncio.sleep(0.2)
        self.pipe.send_bytes(b"\x1b[B\r")
        await asyncio.sleep(0.15)
        self.pipe.send_text("n")
        await asyncio.sleep(0.1)
        self.assertFalse(self.ui.picker.confirming)
        self.pipe.send_bytes(b"\r\x1b")
        await asyncio.sleep(0.6)
        self.assertIsNone(self.ui.picker)
        self.assertFalse(manager.changes)

    async def test_allow_all_select_preserves_exact_ids_and_values(self):
        manager = PermissionAgentManager({
            "id": "permission-policy", "name": "Auto Approve", "type": "select",
            "currentValue": "ask-first", "options": [
                {"value": "ask-first", "name": "Ask"}, {"value": "all-tools", "name": "Allow"},
            ],
        })
        self.runtime.agent_manager = manager
        self.ui.submit("/allow-all")
        await asyncio.sleep(0.2)
        self.pipe.send_bytes(b"\x1b[B\ry")
        await asyncio.sleep(0.25)
        self.assertEqual(manager.changes, [("one", "permission-policy", "all-tools")])
        self.assertIn("Allow all changed", self.screen())

    async def test_allow_all_unsupported_busy_and_stale_session_fail_closed(self):
        self.ui.submit("/allow-all")
        await asyncio.sleep(0.2)
        self.assertIsNone(self.ui.picker)
        self.assertIn("does not advertise", self.screen())
        manager = PermissionAgentManager()
        self.runtime.agent_manager = manager
        self.ui.submit("/allow-all")
        await asyncio.sleep(0.2)
        self.client.chats["one"].session_id = "replacement"
        self.pipe.send_bytes(b"\x1b[B\ry")
        await asyncio.sleep(0.2)
        self.assertFalse(manager.changes)
        self.assertIn("Session changed", self.screen())
        self.ui.submit("/allow-all")
        await asyncio.sleep(0.2)
        self.client.chats["one"].status = "waitingApproval"
        self.pipe.send_bytes(b"\x1b[B\ry")
        await asyncio.sleep(0.2)
        self.assertFalse(manager.changes)
        self.assertIn("Chat is busy", self.screen())
        self.assertFalse(self.runtime._configuring_chats)

    async def test_allow_all_agent_rejection_or_unconfirmed_value_is_not_success(self):
        for manager in (PermissionAgentManager(error="Permission setting rejected"), PermissionAgentManager(confirm=False)):
            self.runtime.agent_manager = manager
            self.ui.submit("/allow-all")
            await asyncio.sleep(0.2)
            self.pipe.send_bytes(b"\x1b[B\ry")
            await asyncio.sleep(0.25)
            self.assertEqual(len(manager.changes), 1)
            self.assertIn("Allow all change failed", self.screen())
            self.assertNotIn("Allow all changed:", self.screen())
            self.assertFalse(self.ui.config_busy)
            self.assertFalse(self.runtime._configuring_chats)

    async def test_rerender_uses_cached_old_messages_and_failure_stays_visible(self):
        for i in range(80):
            self.ui.transcript.add("one", str(i), "Agent", f"## Reply {i}\n\n**text**")
        self.ui.render_conversation(60)
        with patch("android_acp_bridge.terminal_ui.MarkdownStream", side_effect=AssertionError("old Markdown reparsed")):
            self.ui.render_conversation(60)
        self.client.observe_event(update("tool_call", toolCallId="bad", title="Failed task", status="failed"))
        for _ in range(30):
            await asyncio.sleep(0.1)
            if "Failed: Failed task" in self.screen():
                break
        self.assertIn("Failed: Failed task", self.screen())
        row = next(r for r in self.ui.transcript.entries if r.kind == "Tools")
        self.assertFalse(row.expanded)


if __name__ == "__main__":
    unittest.main()
