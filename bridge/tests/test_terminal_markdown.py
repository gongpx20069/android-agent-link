from __future__ import annotations

import importlib.util
import asyncio
import re
import unittest
from unittest.mock import patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.terminal import TerminalClient, create_terminal_session, terminal_loop
from android_acp_bridge.terminal_render import MarkdownStream


HAS_MARKDOWN = all(importlib.util.find_spec(module) for module in ("rich", "markdown_it"))


@unittest.skipUnless(HAS_MARKDOWN, "Install requirements-interactive.txt")
class MarkdownStreamTests(unittest.TestCase):
    def setUp(self) -> None:
        self.output: list[str] = []
        self.notices: list[str] = []
        self.width = 80
        self.stream = MarkdownStream(self.output.append, self.notices.append, lambda: self.width)
        self.no_color = patch.dict("os.environ", {"NO_COLOR": "1"})
        self.no_color.start()
        self.addCleanup(self.no_color.stop)

    def text(self) -> str:
        return "".join(self.output)

    def test_split_inline_markup_and_heading_render_once(self) -> None:
        for part in ("# Sum", "mary\n", "\nA **bo", "ld** and *italic* `code` paragraph.", "\n\n"):
            self.stream.feed(part)
        self.stream.finish()
        rendered = self.text()
        for word in ("Summary", "bold", "italic", "code"):
            self.assertEqual(rendered.count(word), 1)
        self.assertNotIn("# Summary", rendered)
        self.assertNotIn("**", rendered)
        self.assertNotIn("`", rendered)
        self.assertNotIn("\x1b", rendered)

    def test_fenced_code_preserves_blank_lines_and_waits_for_close(self) -> None:
        for part in ("``", "`python\n", "def example():\n", "\n", "    return 42\n", "``"):
            self.stream.feed(part)
            self.assertFalse(self.output)
        self.stream.feed("`\n")
        rendered = self.text()
        self.assertIn("def example():", rendered)
        self.assertIn("    return 42", rendered)
        self.assertNotIn("```", rendered)
        self.assertEqual(rendered.count("return 42"), 1)
        self.assertFalse(self.stream.pending)
        self.stream.finish()
        self.assertEqual(rendered, self.text())

    def test_tilde_long_fence_and_unclosed_fence_on_completion(self) -> None:
        self.stream.feed("~~~~text\none\n~~~\ntwo\n")
        self.assertFalse(self.output)
        self.stream.feed("~~~~\n")
        self.assertIn("~~~", self.text())
        self.stream.feed("```text\nunfinished")
        self.stream.finish()
        self.assertIn("unfinished", self.text())
        self.assertNotIn("```text", self.text())

    def test_table_waits_for_rows_and_renders_columns(self) -> None:
        for part in ("| Name | Result |\n", "| --- | --- |\n", "| alpha | success |\n"):
            self.stream.feed(part)
            self.assertFalse(self.output)
        self.stream.feed("\n")
        rendered = self.text()
        self.assertIn("Name", rendered)
        self.assertIn("Result", rendered)
        self.assertIn("alpha", rendered)
        self.assertIn("success", rendered)
        self.assertNotIn("| ---", rendered)
        self.assertFalse(self.stream.pending)

    def test_nested_list_and_quote_do_not_split_on_internal_blank_lines(self) -> None:
        for part in ("3. First\n", "\n", "   - Nested\n", "4. Second\n", "\n"):
            self.stream.feed(part)
            self.assertFalse(self.output)
        self.stream.feed("> quoted **text**\n")
        self.assertIn("3 First", " ".join(self.text().split()))
        self.assertIn("4 Second", " ".join(self.text().split()))
        self.assertIn("Nested", self.text())
        self.stream.finish()
        self.assertIn("quoted text", self.text())
        self.assertNotIn("**", self.text())

    def test_setext_heading_and_partial_table_delimiter_remain_intact(self) -> None:
        self.stream.feed("Heading\n")
        self.assertFalse(self.output)
        self.stream.feed("====\n")
        self.assertIn("Heading", self.text())
        self.assertNotIn("====", self.text())
        self.output.clear()
        self.stream.feed("| A | B |\n|-")
        self.assertFalse(self.output)
        self.stream.feed("--|---|\n| c | d |\n\n")
        self.assertIn("c", self.text())
        self.assertNotIn("|---", self.text())

    def test_overflow_is_explicit_bounded_and_does_not_drop_text(self) -> None:
        self.stream.feed("x" * self.stream.MAX_PENDING)
        self.assertLessEqual(len(self.stream.pending), self.stream.MAX_PENDING)
        self.stream.feed("tail")
        self.stream.feed("more")
        self.stream.finish()
        self.assertEqual(self.text(), "x" * self.stream.MAX_PENDING + "tailmore")
        self.assertEqual(len(self.notices), 1)
        self.assertIn("plain text", self.notices[0])
        self.assertFalse(self.stream.pending)
        self.assertFalse(self.stream.plain)

    def test_remote_escapes_links_images_and_encoded_controls_are_inert(self) -> None:
        content = (
            "\x1b[2Jhello\x1b]52;c;clipboard\x07\u202e\n\n"
            "[docs](https://example.invalid) ![image](https://example.invalid/image.png)\n\n"
            "&#27;[2Jentity &#27;]52;c;secret&#7; safe\n"
        )
        with patch("socket.socket.connect", side_effect=AssertionError("Renderer must not access network")):
            self.stream.feed(content)
            self.stream.finish()
        rendered = self.text()
        self.assertIn("hello", rendered)
        self.assertIn("docs", rendered)
        self.assertIn("https://example.invalid", rendered)
        self.assertNotIn("clipboard", rendered)
        self.assertNotIn("\x1b", rendered)
        self.assertNotIn("\u202e", rendered)

    def test_colors_are_generated_styling_only_and_resize_preserves_content(self) -> None:
        with patch.dict("os.environ", {"NO_COLOR": ""}):
            self.stream.feed("**colored**\n\n")
        rendered = self.text()
        self.assertRegex(rendered, r"\x1b\[[0-9;]*m")
        self.assertNotIn("\x1b", re.sub(r"\x1b\[[0-9;]*m", "", rendered))
        self.output.clear()
        self.width = 20
        self.stream.feed("A sentence that must wrap across a narrow terminal.\n\n")
        self.assertEqual(" ".join(self.text().split()), "A sentence that must wrap across a narrow terminal.")
        self.assertTrue(all(len(line) <= 20 for line in self.text().splitlines()))

    def test_narrow_nested_layout_retains_text_with_explicit_notice(self) -> None:
        self.width = 8
        source = "> > > > quoted\n\n- outer\n  - inner\n    - deepest\n"
        self.stream.feed(source)
        self.stream.finish()
        self.assertIn("quoted", self.text())
        self.assertIn("deepest", self.text())
        self.assertEqual(len(self.notices), 1)
        self.assertIn("without truncation", self.notices[0])

    def test_long_table_cells_wrap_instead_of_ellipsis(self) -> None:
        self.width = 32
        first = "abcdefghijklmnopqrstuvwxyz"
        second = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        self.stream.feed(f"| First | Second |\n| --- | --- |\n| {first} | {second} |\n\n")
        rendered = self.text()
        # Interleaved rows must retain every character of each cell.
        self.assertNotIn("\u2026", rendered)
        self.assertEqual("".join(c for c in rendered.split("First", 1)[1].split("Second", 1)[1] if c.islower()), first)
        self.assertEqual("".join(c for c in rendered.split("Second", 1)[1] if c.isupper()), second)
        self.assertFalse(self.notices)

    def test_notices_do_not_split_code_and_other_chat_does_not_flush_it(self) -> None:
        client = TerminalClient(self.output.append)
        client.selected = "one"
        client.markdown = MarkdownStream(client._write_markdown, client.say, lambda: 80)
        client.observe_event({"type": "session/update", "chatId": "one", "operationId": "op", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "```python\ndef example():\n",
        }})
        client.drain()
        self.assertNotIn("def example", self.text())
        client._select_chat("one")
        self.assertNotIn("def example", self.text())
        client.say("Approval notice")
        client.observe_event({"type": "operation.done", "chatId": "other", "operationId": "op", "status": "completed"})
        client.drain()
        self.assertNotIn("def example", self.text())
        client.observe_event({"type": "session/update", "chatId": "one", "operationId": "op", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "    return 42\n```",
        }})
        client.observe_event({"type": "operation.done", "chatId": "one", "operationId": "op", "status": "completed"})
        client.drain()
        self.assertEqual(self.text().count("def example"), 1)
        self.assertIn("    return 42", self.text())
        self.assertNotIn("```", self.text())
        self.assertLess(self.text().index("return 42"), self.text().rindex("Task completed"))

    def test_switch_and_close_flush_pending_reply_once(self) -> None:
        client = TerminalClient(self.output.append)
        client.selected = "one"
        client.markdown = MarkdownStream(client._write_markdown, client.say, lambda: 80)
        for chat_id in ("one", "two"):
            client.observe_request({"type": "chat.attach", "chatId": chat_id,
                                    "agentId": "copilot-cli", "workspacePath": "D:\\repo"})
        client.observe_event({"type": "session/update", "chatId": "one", "operationId": "op", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "**first pending**",
        }})
        client.drain()
        client._select_chat("two")
        client.observe_event({"type": "session/update", "chatId": "two", "operationId": "op", "update": {
            "sessionUpdate": "agent_message_chunk", "text": "**second pending**",
        }})
        client.drain()
        client.close()
        client.close()
        self.assertEqual(self.text().count("first pending"), 1)
        self.assertEqual(self.text().count("second pending"), 1)
        self.assertNotIn("**", self.text())

    def test_approval_details_remain_literal_with_markdown_enabled(self) -> None:
        client = TerminalClient(self.output.append)
        client.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), local_client=client)
        client.markdown = MarkdownStream(client._write_markdown, client.say, lambda: 80)
        approvals = [{
            "chatId": "one", "approvalId": "approval-one", "summary": "**exact summary**",
            "details": {"command": "echo `literal` # comment", "path": "**file**"},
        }]
        with patch.object(client.runtime, "local_approvals", return_value=approvals):
            client.command("/approvals")
        self.assertIn("**exact summary**", self.text())
        self.assertIn("echo `literal` # comment", self.text())
        self.assertIn("**file**", self.text())


@unittest.skipUnless(HAS_MARKDOWN and importlib.util.find_spec("prompt_toolkit"), "Install requirements-interactive.txt")
class MarkdownInputTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_stdout_proxy_renders_markdown_without_changing_draft(self) -> None:
        from prompt_toolkit.application import create_app_session
        from prompt_toolkit.input import create_pipe_input
        from prompt_toolkit.output import DummyOutput
        from prompt_toolkit.patch_stdout import patch_stdout

        writes: list[str] = []

        class CapturedOutput(DummyOutput):
            def write(self, data: str) -> None:
                writes.append(data)

            def write_raw(self, data: str) -> None:
                writes.append(data)

        client = TerminalClient()
        client.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), local_client=client)
        client.observe_request({"type": "chat.attach", "chatId": "one",
                                "agentId": "copilot-cli", "workspacePath": "D:\\repo"})
        with create_pipe_input() as pipe, create_app_session(input=pipe, output=CapturedOutput()), \
                patch.dict("os.environ", {"NO_COLOR": ""}), patch_stdout(raw=True):
            session = create_terminal_session(client)
            task = asyncio.create_task(terminal_loop(client, lambda: True, session))
            try:
                await asyncio.sleep(0.15)
                pipe.send_text("keep my draft")
                await asyncio.sleep(0.1)
                client.observe_event({"type": "session/update", "chatId": "one", "operationId": "op", "update": {
                    "sessionUpdate": "agent_message_chunk", "text": "**Rendered response**\n\n",
                }})
                await asyncio.sleep(0.7)
                self.assertEqual(session.default_buffer.text, "keep my draft")
                rendered = "".join(writes)
                self.assertIn("Rendered response", rendered)
                self.assertNotIn("**Rendered response**", rendered)
                self.assertRegex(rendered, r"\x1b\[[0-9;]*m")
                self.assertFalse(task.done())
                pipe.send_bytes(b"\x03")
                await asyncio.sleep(0.1)
                pipe.send_text("/quit\n")
                await asyncio.wait_for(task, 3)
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)


if __name__ == "__main__":
    unittest.main()
