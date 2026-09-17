from __future__ import annotations

import asyncio
import importlib.util
import io
import tempfile
import threading
import time
import unittest
import urllib.request
from unittest.mock import patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.console_log import ConsoleLog
from android_acp_bridge.main import main
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, DeviceInfo
from android_acp_bridge.terminal import TerminalClient, display_text, terminal_loop
from android_acp_bridge.terminal import run_interactive
from android_acp_bridge.stdlib_server import BridgeHTTPServer
from test_runtime import BlockingAgentManager, FakeAgentManager, wait_for_event


class TerminalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.output: list[str] = []
        self.client = TerminalClient(self.output.append)
        self.runtime = BridgeRuntime(
            BridgeConfig(machine_name="test"), PairingStore(), agent_manager=FakeAgentManager(),
            account_pairing_enabled=True, local_client=self.client, console=ConsoleLog("error"),
        )
        self.client.runtime = self.runtime
        self.addCleanup(self.client.close)

    def attach_phone(self, phone: list[dict]) -> None:
        self.runtime.websocket_responses({
            "type": "chat.attach", "chatId": "chat-a", "agentId": "copilot-cli",
            "workspacePath": "D:\\repo",
        }, emit=phone.append)
        self.client.command("/use chat-a")

    def test_terminal_prompt_preserves_android_subscription_and_session(self) -> None:
        phone: list[dict] = []
        self.attach_phone(phone)
        subscriber = self.runtime._chat_emitters["chat-a"]
        self.client.command("hello from terminal")
        done = wait_for_event(phone, "operation.done")
        self.assertEqual(done["status"], "completed")
        self.assertIs(self.runtime._chat_emitters["chat-a"], subscriber)
        accepted = next(e for e in phone if e["type"] == "operation.accepted")
        self.assertEqual(accepted["content"], "hello from terminal")
        self.assertTrue(accepted["operationId"].startswith("terminal_"))
        self.client.drain()
        text = "".join(self.output)
        self.assertEqual(text.count("You > hello from terminal"), 1)
        self.assertIn("Agent >", text)
        self.assertNotIn("agent_message_chunk", text)
        self.assertIsNotNone(self.client.chats["chat-a"].session_id)
        session = self.client.chats["chat-a"].session_id
        # Reconnection updates session metadata without replaying terminal output.
        before = len(self.output)
        self.runtime.websocket_responses({
            "type": "chat.attach", "chatId": "chat-a", "agentId": "copilot-cli",
            "workspacePath": "D:\\repo",
        }, emit=phone.append)
        self.client.drain()
        self.assertEqual(len(self.output), before)
        self.assertEqual(self.client.chats["chat-a"].session_id, session)

    def test_two_frontends_share_existing_fifo_queue(self) -> None:
        manager = BlockingAgentManager()
        self.runtime.agent_manager = manager
        phone: list[dict] = []
        self.attach_phone(phone)
        self.runtime.websocket_responses({
            "type": "chat.prompt", "operationId": "phone-first", "chatId": "chat-a",
            "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first",
        }, emit=self.runtime._chat_emitters["chat-a"])
        self.assertTrue(manager.started.wait(3))
        try:
            self.client.command("second")
            self.assertEqual(manager.prompts, ["first"])
            self.assertFalse(self.client.command("/quit!"))
            self.assertTrue(self.client.command("/quit"))
        finally:
            manager.release.set()
        deadline = time.monotonic() + 3
        while sum(e["type"] == "operation.done" for e in phone) < 2 and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(manager.prompts, ["first", "second"])
        self.assertEqual(sum(e["type"] == "operation.done" for e in phone), 2)
        self.client.drain()
        self.assertIn("Phone > first", "".join(self.output))
        self.assertIn("You > second", "".join(self.output))

    def test_approval_requires_review_and_is_resolved_for_android(self) -> None:
        phone: list[dict] = []
        self.attach_phone(phone)
        self.client.command("needs approval")
        approval = wait_for_event(phone, "approval.requested")
        identifier = approval["approvalId"]
        self.client.command("/approve " + identifier)
        self.assertEqual(len(self.runtime.local_approvals()), 1)
        self.client.command("/approvals")
        self.assertIn(identifier, self.client._reviewed)
        self.client.command("/approve " + identifier)
        wait_for_event(phone, "operation.done")
        resolved = next(e for e in phone if e["type"] == "approval.resolved")
        self.assertEqual(resolved["status"], "approved")
        self.assertFalse(self.runtime.local_approvals())
        self.client.command("/approve " + identifier)
        self.assertIn("already resolved", "".join(self.output))

    def test_pairing_uses_command_broker_not_second_stdin_reader(self) -> None:
        for answer, expected in (("y", True), ("n", False), ("", False)):
            result: list[bool] = []
            thread = threading.Thread(target=lambda: result.append(self.runtime._confirm_account_pairing("Phone", "123456")))
            with patch("builtins.input", side_effect=AssertionError("Must not compete for stdin")):
                thread.start()
                deadline = time.monotonic() + 2
                while self.client._pairing is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.client.drain()
                self.client.command("/pair " + answer)
                thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(result, [expected])
            self.client.drain()
        self.assertIn("123456", "".join(self.output))

    def test_close_and_expiry_deny_pairing(self) -> None:
        results: list[bool] = []
        thread = threading.Thread(target=lambda: results.append(self.client.confirm_pairing("Phone", None)))
        thread.start()
        deadline = time.monotonic() + 2
        while self.client._pairing is None and time.monotonic() < deadline:
            time.sleep(0.01)
        self.client.drain()
        self.client._pairing.expires = 0
        self.client.command("/pair y")
        self.client.close()
        thread.join(3)
        self.assertEqual(results, [False])

    def test_unknown_chat_commands_and_new_workspace_validation(self) -> None:
        self.client.command("do not run")
        self.client.command("/use unknown")
        self.client.command("/shell erase files")
        self.assertFalse(self.runtime._prompt_operations)
        self.assertIn("never executed as a shell", "".join(self.output))
        with tempfile.TemporaryDirectory() as directory, patch.object(self.runtime, "agents_response", return_value={
            "agents": [{"id": "copilot-cli", "status": "available"}],
        }):
            self.client.command("/new copilot-cli relative")
            self.assertIsNone(self.client.selected)
            self.client.command(f'/new copilot-cli "{directory}"')
            self.assertIsNotNone(self.client.selected)
            self.assertEqual(self.client.chats[self.client.selected].workspace, directory)
        self.assertFalse(self.runtime._prompt_operations)

    def test_output_overflow_is_bounded_and_does_not_drop_android_events(self) -> None:
        self.attach_phone([])
        for index in range(1000):
            self.runtime._append_event("chat-a", {
                "type": "session/update", "operationId": "op",
                "update": {"sessionUpdate": "agent_message_chunk", "text": "x"},
            })
        self.assertEqual(self.runtime._next_event_ids["chat-a"], 1002)
        self.assertLessEqual(self.client._events.qsize(), 256)
        self.client.drain()
        self.assertIn("Terminal display limit reached", "".join(self.output))
        self.assertIn("Android delivery is unchanged", "".join(self.output))

    def test_terminal_escapes_and_other_chat_responses_are_not_rendered(self) -> None:
        self.client.selected = "chat-a"
        self.client.observe_event({"type": "session/update", "chatId": "other", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "PRIVATE OTHER CHAT",
        }})
        self.client.observe_event({"type": "session/update", "chatId": "chat-a", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "\x1b[2Jhello\x1b]52;c;clipboard\x07\u202e!",
        }})
        self.client.drain()
        output = "".join(self.output)
        self.assertNotIn("PRIVATE", output)
        self.assertNotIn("\x1b", output)
        self.assertNotIn("clipboard", output)
        self.assertNotIn("\u202e", output)
        self.assertIn("hello!", output)
        self.assertEqual(display_text("a\n\tb"), "a\n\tb")

    def test_interactive_cli_preflight_has_no_tunnel_side_effects(self) -> None:
        with patch("android_acp_bridge.main.setup_devtunnel") as setup, patch("sys.stderr", new_callable=io.StringIO):
            self.assertEqual(main(["start", "--interactive", "--server", "fastapi"]), 1)
            with patch("sys.stdin.isatty", return_value=False):
                self.assertEqual(main(["start", "--interactive"]), 1)
            setup.assert_not_called()

    @unittest.skipUnless(importlib.util.find_spec("prompt_toolkit"), "Install requirements-interactive.txt")
    def test_real_interactive_server_is_responsive_and_stops_on_quit(self) -> None:
        from prompt_toolkit import PromptSession
        from prompt_toolkit.application import create_app_session
        from prompt_toolkit.input import create_pipe_input
        from prompt_toolkit.output import DummyOutput

        self.runtime.config = BridgeConfig(machine_name="test", host="127.0.0.1", port=0)
        ready = threading.Event()
        servers: list[BridgeHTTPServer] = []
        outcomes: list[object] = []

        def server_factory(address, runtime):
            server = BridgeHTTPServer(address, runtime)
            servers.append(server)
            ready.set()
            return server

        with create_pipe_input() as pipe:
            def drive() -> None:
                try:
                    if not ready.wait(3):
                        outcomes.append("Server was not created")
                        return
                    with urllib.request.urlopen(f"http://127.0.0.1:{servers[0].server_port}/health", timeout=3) as response:
                        outcomes.append(response.status)
                except OSError as exc:
                    outcomes.append(str(exc))
                finally:
                    pipe.send_text("/quit\n")

            driver = threading.Thread(target=drive)
            driver.start()
            with patch("android_acp_bridge.terminal.BridgeHTTPServer", side_effect=server_factory), \
                    patch("prompt_toolkit.PromptSession", side_effect=lambda **kw: PromptSession(
                        input=pipe, output=DummyOutput(), **kw,
                    )), create_app_session(input=pipe, output=DummyOutput()):
                run_interactive(self.runtime, self.client)
            driver.join(3)
            self.assertFalse(driver.is_alive())
        self.assertEqual(outcomes, [200])
        self.assertEqual(servers[0].socket.fileno(), -1)
        self.assertTrue(self.client._closed)


@unittest.skipUnless(importlib.util.find_spec("prompt_toolkit"), "Install requirements-interactive.txt for terminal UI tests")
class TerminalInputTests(unittest.IsolatedAsyncioTestCase):
    async def test_streaming_preserves_draft_and_ctrl_c_does_not_stop_bridge(self) -> None:
        from prompt_toolkit import PromptSession
        from prompt_toolkit.history import DummyHistory
        from prompt_toolkit.input import create_pipe_input
        from prompt_toolkit.output import DummyOutput

        output: list[str] = []
        client = TerminalClient(output.append)
        client.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), local_client=client)
        client.selected = "chat-a"
        with create_pipe_input() as pipe:
            session = PromptSession(input=pipe, output=DummyOutput(), history=DummyHistory())
            task = asyncio.create_task(terminal_loop(client, lambda: True, session))
            try:
                await asyncio.sleep(0.1)
                pipe.send_text("unfinished message")
                await asyncio.sleep(0.1)
                client.observe_event({"type": "session/update", "chatId": "chat-a", "update": {
                    "sessionUpdate": "agent_message_chunk", "text": "background reply",
                }})
                await asyncio.sleep(0.2)
                self.assertEqual(session.default_buffer.text, "unfinished message")
                self.assertIn("background reply", "".join(output))
                pipe.send_bytes(b"\x03")
                await asyncio.sleep(0.2)
                self.assertFalse(task.done())
                self.assertIn("Input cancelled", "".join(output))
                pipe.send_text("/quit\n")
                await asyncio.wait_for(task, 3)
                self.assertTrue(client._closed)
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)


if __name__ == "__main__":
    unittest.main()
