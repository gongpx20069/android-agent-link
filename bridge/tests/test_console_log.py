from __future__ import annotations

import copy
import io
import threading
import unittest
from unittest.mock import patch

from android_acp_bridge.console_log import ConsoleLog
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, PromptOperation
from android_acp_bridge.main import main


class ConsoleLogTests(unittest.TestCase):
    def setUp(self) -> None:
        self.now = 100.0
        self.lines: list[str] = []
        self.log = ConsoleLog(clock=lambda: self.now, write=self.lines.append)

    def event(self, kind: str, **values: object) -> dict:
        return {"type": kind, "chatId": "chat-a", "operationId": "op-a", **values}

    def update(self, kind: str, **values: object) -> None:
        self.log.observe(self.event("session/update", update={"sessionUpdate": kind, **values}))

    def test_thousand_chunks_and_interleaved_config_have_three_info_lines(self) -> None:
        self.log.observe(self.event("operation.started", content="PRIVATE PROMPT"))
        for _ in range(1000):
            self.update("agent_message_chunk", content={"text": "secret"})
            self.update("config_option_update", configOptions=["SECRET_CONFIG"])
            self.update("agent_thought_chunk", content={"text": "PRIVATE THOUGHT"})
        self.log.observe(self.event("operation.done", status="completed"))
        self.assertEqual(len(self.lines), 3)
        self.assertIn("chunks=1000 chars=6000 thought_chunks=1000", self.lines[-1])
        self.assertFalse(self.log._operations)
        self.assertNotIn("secret", "\n".join(self.lines))
        self.assertNotIn("PRIVATE", "\n".join(self.lines))

    def test_tool_sparse_updates_and_repeated_completion_are_collapsed(self) -> None:
        self.log.observe(self.event("operation.started"))
        self.update("tool_call", toolCallId="tool-a", status="pending", title="secret command")
        for _ in range(100):
            self.update("tool_call_update", toolCallId="tool-a", content="secret output")
        self.update("tool_call_update", toolCallId="tool-a", status="in_progress")
        self.now += 2
        self.update("tool_call_update", toolCallId="tool-a", status="completed")
        self.update("tool_call_update", toolCallId="tool-a", status="completed")
        self.assertEqual(sum("tool." in line for line in self.lines), 2)
        self.assertIn("duration=2.0s", self.lines[-1])
        self.update("tool_call_update", toolCallId="tool-b", status="failed", content={"error": "secret"})
        self.assertIn("ERROR", self.lines[-1])
        self.assertIn("tool.finished", self.lines[-1])
        self.assertNotIn("secret", "\n".join(self.lines))

    def test_heartbeat_is_per_operation_throttled_and_stops_on_completion(self) -> None:
        self.log.observe(self.event("operation.started"))
        self.log.observe(self.event("operation.started", operationId="op-b"))
        self.log.observe(self.event("approval.requested", approvalId="p1", details="secret"))
        self.now += 14
        self.log.progress()
        self.assertFalse(any("operation.progress" in line for line in self.lines))
        self.now += 1
        self.log.progress()
        self.log.progress()
        progress = [line for line in self.lines if "operation.progress" in line]
        self.assertEqual(len(progress), 2)
        self.assertIn("op=op-a state=awaiting_approval", progress[0])
        self.assertIn("op=op-b state=running", progress[1])
        self.log.observe(self.event("approval.resolved", approvalId="p1", status="approved"))
        self.log.observe(self.event("operation.done", status="cancelled"))
        self.now += 15
        self.log.progress()
        self.assertIn("op=op-b", self.lines[-1])
        self.assertEqual(len(self.log._operations), 1)

    def test_debug_is_metadata_only_and_quiet_levels_preserve_errors(self) -> None:
        self.log = ConsoleLog("debug", write=self.lines.append)
        self.update("agent_message_chunk", text="secret body")
        self.update("config_option_update", configOptions=["secret"])
        self.log.observe(self.event("session.list.result", error="token=secret"))
        self.assertTrue(any("chars=11" in line for line in self.lines))
        self.assertNotIn("secret", "\n".join(self.lines))
        self.lines.clear()
        self.log = ConsoleLog("warning", write=self.lines.append)
        self.log.observe(self.event("operation.started"))
        self.log.observe(self.event("approval.requested", approvalId="p1"))
        self.log.observe(self.event("operation.done", status="failed"))
        self.assertEqual(len(self.lines), 2)
        self.assertIn("WARNING", self.lines[0])
        self.assertIn("ERROR", self.lines[1])

    def test_pairing_prompt_coalesces_without_retaining_content_and_restores_on_error(self) -> None:
        with self.assertRaises(EOFError):
            with self.log.pairing_prompt():
                for _ in range(1000):
                    self.log.message("info", "operation.progress", chat="a")
                self.assertFalse(self.lines)
                self.log.message("error", "operation.error")
                self.assertEqual(len(self.lines), 1)
                raise EOFError
        self.assertIn("deferred=1000", self.lines[-1])
        self.log.message("info", "connection.closed")
        self.assertIn("connection.closed", self.lines[-1])

    def test_state_caps_and_thread_shutdown(self) -> None:
        for index in range(257):
            self.log.observe(self.event("operation.started", operationId=str(index)))
        self.assertEqual(len(self.log._operations), 256)
        self.assertTrue(any("logging.capacity" in line for line in self.lines))
        self.log.start()
        worker = self.log._worker
        self.assertTrue(worker.is_alive())
        self.log.start()
        self.assertIs(self.log._worker, worker)
        self.log.close()
        self.assertFalse(worker.is_alive())
        self.assertFalse(self.log._operations)

    def test_http_success_is_debug_and_errors_are_visible_without_urls(self) -> None:
        self.log.http("GET", 200)
        self.assertFalse(self.lines)
        self.log.http("POST", 403)
        self.log.http("POST", 503)
        self.assertEqual(len(self.lines), 2)
        self.assertIn("WARNING", self.lines[0])
        self.assertIn("ERROR", self.lines[1])

    def test_multi_connection_delivery_and_replay_do_not_repeat_business_logs(self) -> None:
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), console=self.log)
        first: list[dict] = []
        second: list[dict] = []
        runtime._chat_emitters["chat-a"] = first.append
        operation = PromptOperation("chat-a", "op-a", "fake", "", "", None, False,
                                    second.append, [], threading.Event())
        events = [self.event("operation.started")]
        events += [self.event("session/update", update={
            "sessionUpdate": "agent_message_chunk", "content": {"text": f"private-{index}"},
        }) for index in range(30)]
        events += [self.event("operation.done", status="completed")]
        original = copy.deepcopy(events)
        for event in events:
            runtime._publish_prompt_event(operation, event)
        self.assertEqual(events, original)
        self.assertEqual(first, second)
        self.assertEqual(len(first), len(events))
        for sent, expected in zip(first, original):
            self.assertEqual({k: v for k, v in sent.items() if k not in {"eventId", "timestamp"}}, expected)
        self.assertEqual([e["eventId"] for e in first], list(range(1, len(events) + 1)))
        self.assertEqual(len(self.lines), 3)
        runtime._log_responses(first)
        self.assertEqual(len(self.lines), 3)
        replay = runtime.websocket_responses({"type": "chat.attach", "chatId": "chat-a", "lastEventId": 0})
        self.assertEqual([e for e in replay if e.get("operationId") == "op-a"],
                         [e for e in first if e.get("operationId") == "op-a"])
        self.assertEqual(first[-1]["type"], "chat.status")
        self.assertEqual(sum("operation.finished" in line for line in self.lines), 1)
        self.assertTrue(any("chat.attached" in line and "replayed=32" in line for line in self.lines))

    def test_business_logs_do_not_depend_on_emitter_or_append_replay_generation(self) -> None:
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), console=self.log)
        event = self.event("operation.started")
        runtime._append_event("chat-a", event)
        runtime._append_event("chat-a", self.event("operation.done", status="completed"))
        runtime._next_event_ids["chat-a"] = 1
        runtime._append_event("chat-a", event)
        self.assertEqual(sum("operation.started" in line for line in self.lines), 2)

    def test_concurrent_chats_have_independent_counts(self) -> None:
        def run(chat: str, count: int) -> None:
            self.log.observe(self.event("operation.started", chatId=chat))
            for _ in range(count):
                self.log.observe(self.event("session/update", chatId=chat, update={
                    "sessionUpdate": "agent_message_chunk", "text": "x",
                }))
            self.log.observe(self.event("operation.done", chatId=chat, status="completed"))

        threads = [threading.Thread(target=run, args=(f"chat-{i}", 100 + i)) for i in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(3)
            self.assertFalse(thread.is_alive())
        for i in range(4):
            line = next(line for line in self.lines if "operation.finished" in line and f"chat=chat-{i} " in line)
            self.assertIn(f"chunks={100 + i} chars={100 + i}", line)
        self.assertEqual(len(self.lines), 12)
        self.assertFalse(self.log._operations)

    def test_cli_level_wired_and_progress_worker_closed_on_server_failure(self) -> None:
        consoles = []

        def server(runtime: BridgeRuntime) -> None:
            consoles.append(runtime.console)
            self.assertEqual(runtime.console.threshold, 10)
            self.assertTrue(runtime.console._worker.is_alive())
            raise OSError("test listener failure")

        with patch("android_acp_bridge.main.default_config", return_value=BridgeConfig(machine_name="test")), \
                patch("android_acp_bridge.main.run_server", side_effect=server), \
                patch("android_acp_bridge.main.render_terminal_qr", return_value="QR"), \
                patch("sys.stdout", new_callable=io.StringIO):
            with self.assertRaises(OSError):
                main(["start", "--transport", "local", "--log-level", "debug"])
        self.assertEqual(len(consoles), 1)
        self.assertIsNone(consoles[0]._worker)

    def test_one_shot_done_without_chat_cleans_tool_statistics(self) -> None:
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), console=self.log)
        runtime._log_responses([
            {"type": "session/update", "chatId": "chat-a", "update": {
                "sessionUpdate": "tool_call_update", "toolCallId": "approval-a", "status": "completed",
            }},
            {"type": "bridge.done"},
        ])
        self.assertFalse(self.log._operations)
        self.now += 60
        self.log.progress()
        self.assertFalse(any("operation.progress" in line for line in self.lines))


if __name__ == "__main__":
    unittest.main()
