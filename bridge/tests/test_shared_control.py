from __future__ import annotations

import io
import json
import tempfile
import time
import socket
import struct
import threading
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from android_acp_bridge.acp_agent import AcpAgentSession
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.shared_state import SharedState
from android_acp_bridge.stdlib_server import BridgeHTTPServer, _read_websocket_frame
from test_runtime import BlockingAgentManager, FakeAgentManager


class SharedControlTests(unittest.TestCase):
    def runtime(self, **config) -> BridgeRuntime:
        manager = config.pop("manager", FakeAgentManager())
        instance = BridgeRuntime(BridgeConfig(machine_name="test", **config), PairingStore(), False, manager)
        self.addCleanup(instance.shared.close)
        return instance

    def request(self, runtime, action, **args):
        response = runtime.websocket_responses({"type": "control.request", "requestId": "request", "action": action, **args})
        self.assertEqual(response[0]["requestId"], "request")
        self.assertEqual(response[-1]["type"], "bridge.done")
        return response[0]

    def register(self, runtime, identity="chat"):
        result = self.request(runtime, "chat.register", chatId=identity, workspacePath=str(Path.cwd()), agentId="copilot-cli")
        self.assertEqual(result["status"], "ok")
        return result["data"]["chat"]

    def wait_done(self, runtime, identity, task):
        operation = runtime._prompt_operations[(identity, task)]
        self.assertTrue(operation.completed.wait(5))

    def test_catalog_register_keeps_identity_and_binding(self):
        runtime = self.runtime()
        chat = self.register(runtime)
        response = self.request(runtime, "chat.register", chatId="chat", workspacePath="X:\\wrong", agentId="claude-code")
        self.assertEqual(response["data"]["chat"], chat)
        self.assertEqual(self.request(runtime, "chat.list")["data"]["chats"][0]["chatId"], "chat")
        self.assertEqual(len(runtime.workspaces_response()["workspaces"]), 1)
        self.assertEqual(runtime.public_workspaces_response(), {"workspaces": []})

    def test_send_is_async_idempotent_and_detects_human_takeover(self):
        manager = BlockingAgentManager()
        runtime = self.runtime(manager=manager)
        self.register(runtime)
        sent = self.request(runtime, "chat.send", chatId="chat", operationId="mochi_1", content="work",
                            source="mochi", expectedHumanRevision=0)
        self.assertEqual(sent["status"], "ok")
        self.assertFalse(manager.release.is_set())
        self.assertTrue(manager.started.wait(2))
        duplicate = self.request(runtime, "chat.send", chatId="chat", operationId="mochi_1", content="work",
                                 source="mochi", expectedHumanRevision=0)
        self.assertTrue(duplicate["data"]["duplicate"])
        conflict = self.request(runtime, "chat.send", chatId="chat", operationId="mochi_1", content="different",
                                source="mochi", expectedHumanRevision=0)
        self.assertEqual(conflict["code"], "CONFLICT")
        queued = self.request(runtime, "chat.send", chatId="chat", operationId="mochi_2", content="followup",
                             source="mochi", expectedHumanRevision=0)
        self.assertEqual(queued["data"]["state"], "queued")
        human = self.request(runtime, "chat.send", chatId="chat", operationId="human_1", content="takeover")
        self.assertEqual(human["status"], "ok")
        self.assertEqual(runtime.shared.task("chat", "mochi_2")["state"], "cancelled")
        stale = self.request(runtime, "chat.send", chatId="chat", operationId="mochi_3", content="stale",
                            source="mochi", expectedHumanRevision=0)
        self.assertEqual(stale["code"], "CONFLICT")
        manager.release.set()
        self.wait_done(runtime, "chat", "human_1")
        self.assertEqual(manager.prompts, ["work", "takeover"])

    def test_shared_snapshot_and_multicast_disconnect(self):
        runtime = self.runtime()
        self.register(runtime)
        first, second = [], []
        a, b = first.append, second.append
        attach = {"type": "chat.attach", "chatId": "chat", "workspacePath": str(Path.cwd()), "agentId": "copilot-cli"}
        runtime.websocket_responses(attach, emit=a)
        runtime.websocket_responses(attach, emit=b)
        self.request(runtime, "chat.send", chatId="chat", operationId="one", content="hello")
        self.wait_done(runtime, "chat", "one")
        self.assertTrue(any(e["type"] == "operation.done" for e in first))
        self.assertEqual([e["eventId"] for e in first if "eventId" in e],
                         [e["eventId"] for e in second if "eventId" in e])
        runtime.detach(a)
        before = len(first)
        self.request(runtime, "chat.send", chatId="chat", operationId="two", content="hello again")
        self.wait_done(runtime, "chat", "two")
        self.assertEqual(len(first), before)
        page = self.request(runtime, "chat.read", chatId="chat", limit=2)["data"]
        self.assertEqual(len(page["events"]), 2)
        self.assertTrue(page["hasMore"])
        self.assertEqual(page["chat"]["status"], "idle")
        self.assertEqual(page["chat"]["humanRevision"], 2)
        self.assertEqual(len(page["tasks"]), 2)
        self.assertTrue(page["online"])
        following = self.request(runtime, "chat.read", chatId="chat", afterEventId=page["nextEventId"])["data"]
        self.assertTrue(all(e["eventId"] > page["nextEventId"] for e in following["events"]))

    def test_restart_preserves_catalog_idempotency_and_interrupted_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private" / "shared.sqlite3"
            runtime = self.runtime(shared_state_store=path)
            self.register(runtime)
            runtime._append_event("chat", {"type": "operation.accepted", "operationId": "unknown",
                                         "state": "running", "content": "do not rerun", "source": "mochi"})
            runtime._append_event("chat", {"type": "chat.status", "status": "busy"})
            generation = runtime.shared.generation
            runtime.shared.close()
            restored = self.runtime(shared_state_store=path)
            try:
                self.assertEqual(restored.shared.generation, generation)
                self.assertEqual(restored.shared.chat("chat")["status"], "interrupted")
                duplicate = self.request(restored, "chat.send", chatId="chat", operationId="unknown",
                                         content="do not rerun", source="mochi", expectedHumanRevision=0)
                self.assertEqual(duplicate["data"]["state"], "interrupted")
                self.assertEqual(restored._active_prompts, {})
                self.assertEqual(restored._next_event_ids["chat"], 3)
            finally:
                restored.shared.close()

    def test_workspace_roots_and_non_destructive_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = self.runtime(workspace_roots=(directory,))
            path = str(Path(directory) / "project")
            result = self.request(runtime, "workspace.create", mode="directory", path=path)
            self.assertEqual(result["status"], "ok")
            self.assertTrue(Path(path).is_dir())
            conflict = self.request(runtime, "workspace.create", mode="directory", path=path)
            self.assertEqual(conflict["code"], "CONFLICT")
            registered = self.request(runtime, "workspace.create", mode="register_existing", path=path)
            self.assertEqual(registered["data"], result["data"])
            forbidden = self.request(runtime, "workspace.create", mode="directory", path=str(Path(directory).parent / "outside"))
            self.assertEqual(forbidden["code"], "PERMISSION_DENIED")
            created = self.request(runtime, "chat.create", workspaceId=result["data"]["workspace"]["id"],
                                   agentId="copilot-cli", title="Shared title", chatId="new")
            self.assertEqual(created["data"]["chat"]["chatTitle"], "Shared title")

    def test_git_arguments_and_disabled_workspace_creation(self):
        runtime = self.runtime()
        self.assertEqual(self.request(runtime, "workspace.create", path=str(Path.cwd() / "anything"))["code"], "PERMISSION_DENIED")
        with tempfile.TemporaryDirectory() as directory:
            enabled = self.runtime(workspace_roots=(directory,))
            args = {"mode": "clone", "path": str(Path(directory) / "clone")}
            for url in ("file:///secret", "https://user:secret@example.com/repo", "-option"):
                self.assertEqual(self.request(enabled, "workspace.create", repositoryUrl=url, **args)["code"], "INVALID_ARGS")
            with patch("android_acp_bridge.control.subprocess.run", return_value=Mock(returncode=1)) as run:
                result = self.request(enabled, "workspace.create", repositoryUrl="https://example.com/repo", **args)
                self.assertEqual(result["code"], "PROVIDER_ERROR")
                self.assertEqual(run.call_args.args[0][-3:], ["--", "https://example.com/repo", str(Path(args["path"]).resolve())])
                self.assertNotIn("shell", run.call_args.kwargs)

    def test_invalid_reads_and_missing_resources_fail_explicitly(self):
        runtime = self.runtime()
        self.register(runtime)
        self.assertEqual(self.request(runtime, "chat.read", chatId="chat", limit=True)["code"], "INVALID_ARGS")
        self.assertEqual(self.request(runtime, "chat.read", chatId="missing")["code"], "NOT_FOUND")
        self.assertEqual(self.request(runtime, "chat.send", chatId="chat", operationId="x", content="go", source="mochi")["code"], "CONFLICT")

    def test_journal_retention_truncation_and_history_generation(self):
        state = SharedState()
        self.addCleanup(state.close)
        state.register({"chatId": "chat", "workspacePath": str(Path.cwd())})
        state.EVENT_LIMIT = 2
        for index in range(1, 4):
            state.append("chat", {"type": "session/update", "eventId": index, "timestamp": index,
                                 "update": {"text": "x" * (state.EVENT_BYTES + 1)}})
        page = state.events("chat")
        self.assertEqual([e["eventId"] for e in page["events"]], [2, 3])
        self.assertTrue(page["truncated"])
        self.assertTrue(page["events"][0]["truncated"])
        self.assertNotEqual(state.reset_events("chat"), page["eventGeneration"])
        self.assertEqual(state.chat("chat")["humanRevision"], 1)

    def test_cancel_notification_never_consumes_prompt_response(self):
        process = Mock(stdin=io.StringIO())
        session = AcpAgentSession(process, Mock(), "session")
        session.cancel_prompt()
        message = json.loads(process.stdin.getvalue())
        self.assertEqual(message, {"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": "session"}})
        session._output_queue.get.assert_not_called()

    def test_completed_cancel_does_not_relabel_task_as_running(self):
        runtime = self.runtime()
        self.register(runtime)
        self.request(runtime, "chat.send", chatId="chat", operationId="one", content="hello")
        self.wait_done(runtime, "chat", "one")
        self.request(runtime, "task.cancel", chatId="chat", operationId="one")
        self.assertEqual(runtime.shared.task("chat", "one")["state"], "completed")

    def test_configuration_is_confirmed_shared_state_and_busy_rejected(self):
        runtime = self.runtime()
        self.register(runtime)
        refreshed = self.request(runtime, "chat.configure", chatId="chat")
        self.assertEqual(refreshed["status"], "ok")
        self.assertEqual(refreshed["data"]["chat"]["configOptions"][0]["currentValue"], "gpt-5.4")
        configured = self.request(runtime, "chat.configure", chatId="chat", configId="model", value="model-b")
        self.assertEqual(configured["data"]["chat"]["configOptions"][0]["currentValue"], "model-b")
        manager = BlockingAgentManager()
        runtime.agent_manager = manager
        self.request(runtime, "chat.send", chatId="chat", operationId="one", content="wait")
        try:
            rejected = self.request(runtime, "chat.configure", chatId="chat")
            self.assertEqual(rejected["code"], "CONFLICT")
        finally:
            manager.release.set()
            self.wait_done(runtime, "chat", "one")

    def test_cross_chat_workspace_write_conflicts(self):
        manager = BlockingAgentManager()
        runtime = self.runtime(manager=manager)
        self.register(runtime, "one")
        self.register(runtime, "two")
        self.request(runtime, "chat.send", chatId="one", operationId="first", content="wait")
        try:
            rejected = self.request(runtime, "chat.send", chatId="two", operationId="second", content="edit",
                                    source="mochi", expectedHumanRevision=0)
            self.assertEqual(rejected["code"], "CONFLICT")
            self.assertIsNone(runtime.shared.task("two", "second"))
        finally:
            manager.release.set()
            self.wait_done(runtime, "one", "first")

    def test_active_cancellation_is_only_a_request_until_agent_confirms(self):
        manager = BlockingAgentManager()
        manager.cancel_prompt = Mock()
        runtime = self.runtime(manager=manager)
        self.register(runtime)
        self.request(runtime, "chat.send", chatId="chat", operationId="one", content="wait")
        self.assertTrue(manager.started.wait(2))
        try:
            result = self.request(runtime, "task.cancel", chatId="chat", operationId="one")
            self.assertEqual(result["data"]["state"], "cancellation_requested")
            manager.cancel_prompt.assert_called_once_with("chat")
            self.assertEqual(runtime.shared.task("chat", "one")["state"], "running")
        finally:
            manager.release.set()
            self.wait_done(runtime, "chat", "one")

    def test_authenticated_websocket_control_and_two_live_subscribers(self):
        manager = BlockingAgentManager()
        runtime = self.runtime(manager=manager)
        server = BridgeHTTPServer(("127.0.0.1", 0), runtime)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        token = runtime.issue_device_token()
        connections = []

        def connect():
            connection = socket.create_connection(server.server_address, timeout=5)
            stream = connection.makefile("rb")
            connections.append((connection, stream))
            connection.sendall((
                f"GET /ws?token={token} HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode())
            self.assertIn(b"101", stream.readline())
            while stream.readline() != b"\r\n":
                pass
            return connection, stream

        def send(connection, payload):
            raw = json.dumps(payload).encode()
            mask = b"\x01\x02\x03\x04"
            size = len(raw)
            header = bytes((0x81, 0x80 | size)) if size < 126 else bytes((0x81, 0xfe)) + struct.pack("!H", size)
            connection.sendall(header + mask + bytes(byte ^ mask[index % 4] for index, byte in enumerate(raw)))

        def until(stream, kind):
            for _ in range(100):
                frame = _read_websocket_frame(stream)
                self.assertIsNotNone(frame)
                event = json.loads(frame[1])
                if event.get("type") == kind:
                    return event
            self.fail(f"Missing {kind}")

        try:
            controller, control_stream = connect()
            send(controller, {"type": "control.request", "requestId": "register", "action": "chat.register",
                              "chatId": "chat", "agentId": "copilot-cli", "workspacePath": str(Path.cwd())})
            self.assertEqual(until(control_stream, "control.result")["status"], "ok")
            first, first_stream = connect()
            second, second_stream = connect()
            for connection, stream in ((first, first_stream), (second, second_stream)):
                send(connection, {"type": "chat.attach", "chatId": "chat", "workspacePath": str(Path.cwd())})
                until(stream, "chat.attached")
            send(controller, {"type": "control.request", "requestId": "send", "action": "chat.send", "chatId": "chat",
                              "operationId": "task", "content": "work", "source": "mochi", "expectedHumanRevision": 0})
            accepted = until(control_stream, "control.result")
            self.assertEqual(accepted["requestId"], "send")
            self.assertEqual(accepted["data"]["taskId"], "task")
            self.assertFalse(manager.release.is_set())
            self.assertTrue(manager.started.wait(2))
            manager.release.set()
            first_done = until(first_stream, "operation.done")
            second_done = until(second_stream, "operation.done")
            self.assertEqual(first_done, second_done)
            self.wait_done(runtime, "chat", "task")
        finally:
            manager.release.set()
            for connection, stream in connections:
                connection.shutdown(socket.SHUT_RDWR)
                stream.close()
                connection.close()
            server.shutdown()
            server.server_close()
            worker.join(5)

    def test_failed_durable_acceptance_never_starts_or_wedges_task(self):
        manager = BlockingAgentManager()
        runtime = self.runtime(manager=manager)
        self.register(runtime)
        with patch.object(runtime.shared, "append", side_effect=sqlite3.OperationalError("disk full")):
            result = self.request(runtime, "chat.send", chatId="chat", operationId="one", content="edit")
        self.assertEqual(result["code"], "PROVIDER_ERROR")
        self.assertEqual(manager.prompts, [])
        self.assertFalse(runtime._active_prompts)
        self.assertFalse(runtime._prompt_operations)

    def test_history_replacement_resets_shared_cursor_for_all_subscribers(self):
        runtime = self.runtime()
        chat = self.register(runtime)
        events = []
        runtime.websocket_responses({"type": "chat.attach", **chat}, emit=events.append)
        old_generation = runtime.shared.events("chat")["eventGeneration"]
        responses = runtime.websocket_responses({"type": "session.loadRecent", **chat, "sessionId": "replacement"})
        result = next(event for event in responses if event["type"] == "session.loadRecent.result")
        self.assertNotEqual(result["eventGeneration"], old_generation)
        self.assertEqual(runtime.shared.chat("chat")["sessionId"], "replacement")
        self.assertEqual(runtime.shared.events("chat")["eventGeneration"], result["eventGeneration"])
        reset = next(event for event in events if event["type"] == "chat.attached" and event.get("checkpointReset"))
        self.assertEqual(reset["eventGeneration"], result["eventGeneration"])
        self.assertTrue(any(event["type"] == "chat.resyncRequired" for event in events))


if __name__ == "__main__":
    unittest.main()
