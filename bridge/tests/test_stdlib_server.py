from __future__ import annotations

import io
import errno
import json
import socket
import struct
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.console_log import ConsoleLog
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.stdlib_server import (
    BridgeHTTPServer, _read_websocket_frame, _read_websocket_text, _write_websocket_frame,
)
from test_runtime import BlockingAgentManager, FakeAgentManager


def _masked_frame(opcode: int, payload: bytes) -> bytes:
    mask = b"\x01\x02\x03\x04"
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    length = len(payload)
    header = bytes([0x80 | opcode, 0x80 | length]) if length < 126 else (
        bytes([0x80 | opcode, 0xfe]) + struct.pack("!H", length))
    return header + mask + masked


class StdlibWebSocketTests(unittest.TestCase):
    def test_read_websocket_text_replies_to_ping_then_reads_text(self) -> None:
        input_stream = io.BytesIO(
            _masked_frame(0x9, b"ping") +
            _masked_frame(0x1, b"hello")
        )
        output_stream = io.BytesIO()

        self.assertEqual(_read_websocket_text(input_stream, output_stream), "hello")

        pong = output_stream.getvalue()
        self.assertEqual(pong[:2], b"\x8a\x04")
        self.assertEqual(pong[2:], b"ping")


class WebSocketLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.logs = []
        self.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(),
                                     agent_manager=FakeAgentManager(), console=ConsoleLog(write=self.logs.append))
        self.server = BridgeHTTPServer(("127.0.0.1", 0), self.runtime)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.connections = []
        self.token = self.runtime.issue_device_token()
        self.tick = patch("android_acp_bridge.stdlib_server.WEBSOCKET_WATCH_SECONDS", 0.01)
        self.tick.start()
        self.addCleanup(self.tick.stop)
        self.addCleanup(self.cleanup)

    def cleanup(self):
        for connection, stream in self.connections:
            if connection.fileno() >= 0:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            stream.close()
            connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(2)
        self.wait_for(lambda: not self.runtime._chat_subscribers)
        self.runtime.shared.close()

    def wait_for(self, predicate):
        deadline = time.monotonic() + 3
        while not predicate():
            if time.monotonic() >= deadline:
                self.fail("Timed out waiting for transport cleanup: " + repr(self.logs))
            time.sleep(0.01)

    def connect(self, chat, slow=False):
        self.runtime.shared.register({"chatId": chat, "agentId": "copilot-cli", "workspacePath": str(Path.cwd())})
        connection = socket.socket()
        connection.settimeout(3)
        if slow:
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        connection.connect(self.server.server_address)
        stream = connection.makefile("rb")
        self.connections.append((connection, stream))
        connection.sendall((
            f"GET /ws?token={self.token} HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
            "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode())
        self.assertIn(b"101", stream.readline())
        while stream.readline() != b"\r\n":
            pass
        self.send(connection, {"type": "chat.attach", "chatId": chat})
        self.until(stream, lambda event: event.get("type") == "chat.status" and event.get("snapshot"))
        return connection, stream

    def send(self, connection, payload):
        connection.sendall(_masked_frame(0x1, json.dumps(payload).encode()))

    def until(self, stream, predicate):
        for _ in range(100):
            frame = _read_websocket_frame(stream)
            self.assertIsNotNone(frame)
            event = json.loads(frame[1])
            if predicate(event):
                return event
        self.fail("Expected event was not received")

    def abort(self, connection, stream):
        stream.close()
        connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER,
                              struct.pack("hh" if sys.platform == "win32" else "ii", 1, 0))
        connection.close()

    def assert_closed(self, chat, reason):
        self.wait_for(lambda: any("connection.closed" in line and f"chat={chat} " in line
                                  and f"reason={reason}" in line for line in self.logs))
        line = next(line for line in self.logs if "connection.closed" in line and f"chat={chat} " in line)
        connection_id = line.split("connection=")[1].split()[0]
        self.wait_for(lambda: not any(t.name in {f"ws-writer-{connection_id}", f"ws-watch-{connection_id}",
                                                f"ws-dispatch-{connection_id}"} for t in threading.enumerate()))
        self.assertNotIn(chat, self.runtime._chat_subscribers)
        self.assertNotIn(self.token, "\n".join(self.logs))

    def assert_ping(self, connection, stream):
        connection.sendall(_masked_frame(0x9, b"alive"))
        self.assertEqual(_read_websocket_frame(stream), (0xA, b"alive"))

    def test_reset_is_connection_local_and_running_task_replays_without_resubmission(self):
        manager = BlockingAgentManager()
        self.runtime.agent_manager = manager
        broken, broken_stream = self.connect("broken")
        healthy, healthy_stream = self.connect("healthy")
        self.send(broken, {"type": "chat.prompt", "chatId": "broken", "operationId": "one", "prompt": "wait"})
        self.assertTrue(manager.started.wait(2))
        try:
            with patch.object(self.server, "handle_error") as unhandled:
                self.abort(broken, broken_stream)
                self.wait_for(lambda: "broken" not in self.runtime._chat_subscribers)
                self.assertIn("broken", self.runtime._active_prompts)
                self.assert_ping(healthy, healthy_stream)
                _, restored = self.connect("broken")
                manager.release.set()
                done = self.until(restored, lambda event: event.get("type") == "operation.done")
                self.assertEqual(done["operationId"], "one")
                self.assertEqual(done["status"], "completed")
                self.assertEqual(len(manager.prompts), 1)
                unhandled.assert_not_called()
                self.wait_for(lambda: any("connection.closed" in line and "chat=broken " in line for line in self.logs))
        finally:
            manager.release.set()
            self.wait_for(lambda: "broken" not in self.runtime._active_prompts)

    def test_write_failure_wakes_idle_reader_without_affecting_other_chat(self):
        self.connect("broken")
        healthy, stream = self.connect("healthy")

        def write(output, opcode, payload, **kwargs):
            if b"fail-write" in payload:
                raise BrokenPipeError(errno.EPIPE, "test")
            return _write_websocket_frame(output, opcode, payload, **kwargs)

        with patch("android_acp_bridge.stdlib_server._write_websocket_frame", side_effect=write):
            self.runtime._subscribers("broken")[0]({"type": "fail-write"})
            self.assert_closed("broken", "write_failed")
            self.assert_ping(healthy, stream)

    def test_byte_budget_accepts_exact_limit_and_closes_on_overflow(self):
        connection, stream = self.connect("broken")
        self.assert_ping(connection, stream)
        emit = self.runtime._subscribers("broken")[0]
        payload = {"type": "probe", "text": "x" * 100}
        size = len(json.dumps(payload, separators=(",", ":")).encode())
        with patch("android_acp_bridge.stdlib_server.WEBSOCKET_QUEUE_BYTES", size):
            emit(payload)
            self.assertEqual(self.until(stream, lambda e: e.get("type") == "probe"), payload)
            emit({**payload, "text": payload["text"] + "x"})
            self.assert_closed("broken", "send_queue_full")

    def test_stalled_send_has_deadline_and_queue_count_limit(self):
        for reason in ("write_timeout", "send_queue_full"):
            with self.subTest(reason=reason):
                chat = reason
                self.connect(chat, slow=True)
                healthy, stream = self.connect("healthy-" + reason)
                entered = threading.Event()

                def write(output, opcode, payload, **kwargs):
                    if b'"type":"stall"' in payload[:50]:
                        output._sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 0)
                        entered.set()
                    return _write_websocket_frame(output, opcode, payload, **kwargs)

                with patch("android_acp_bridge.stdlib_server._write_websocket_frame", side_effect=write), \
                        patch("android_acp_bridge.stdlib_server.WEBSOCKET_WRITE_SECONDS",
                              0.15 if reason == "write_timeout" else 1), \
                        patch("android_acp_bridge.stdlib_server.WEBSOCKET_QUEUE_MESSAGES", 16):
                    # Queue capacity is chosen when the socket is opened.
                    if reason == "send_queue_full":
                        self.connect(chat + "-limited", slow=True)
                        chat += "-limited"
                    emit = self.runtime._subscribers(chat)[0]
                    emit({"type": "stall", "text": "x" * (8 * 1024 * 1024)})
                    self.assertTrue(entered.wait(2))
                    if reason == "send_queue_full":
                        for index in range(17):
                            emit({"type": "queued", "index": index})
                    self.assert_closed(chat, reason)
                    self.assert_ping(healthy, stream)

    def test_detaches_subscription_created_by_request_finishing_after_disconnect(self):
        connection, stream = self.connect("late")
        entered, release, completed = threading.Event(), threading.Event(), threading.Event()
        original = self.runtime.websocket_responses

        def dispatch(payload, emit=None):
            entered.set()
            release.wait(2)
            result = original(payload, emit=emit)
            completed.set()
            return result

        with patch.object(self.runtime, "websocket_responses", side_effect=dispatch):
            self.send(connection, {"type": "chat.attach", "chatId": "late"})
            self.assertTrue(entered.wait(2))
            try:
                self.abort(connection, stream)
                self.wait_for(lambda: "late" not in self.runtime._chat_subscribers)
            finally:
                release.set()
            self.assertTrue(completed.wait(2))
            self.wait_for(lambda: "late" not in self.runtime._chat_subscribers)

    def test_temporary_backpressure_recovers_on_same_connection(self):
        connection, stream = self.connect("recovering", slow=True)
        entered = threading.Event()

        def write(output, opcode, payload, **kwargs):
            if b'"type":"stall"' in payload[:50]:
                output._sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 0)
                entered.set()
            return _write_websocket_frame(output, opcode, payload, **kwargs)

        with patch("android_acp_bridge.stdlib_server._write_websocket_frame", side_effect=write), \
                patch("android_acp_bridge.stdlib_server.WEBSOCKET_WRITE_SECONDS", 3):
            self.runtime._subscribers("recovering")[0]({"type": "stall", "text": "x" * (1024 * 1024)})
            self.assertTrue(entered.wait(2))
            time.sleep(0.1)
            event = self.until(stream, lambda event: event.get("type") == "stall")
            self.assertEqual(len(event["text"]), 1024 * 1024)
            self.assert_ping(connection, stream)
            self.assertFalse(any("connection.closed" in line for line in self.logs))

    def test_write_deadline_does_not_become_idle_read_timeout(self):
        with patch("android_acp_bridge.stdlib_server.WEBSOCKET_WRITE_SECONDS", 0.1):
            connection, stream = self.connect("idle")
            time.sleep(0.2)
            self.assert_ping(connection, stream)
            self.assertFalse(any("connection.closed" in line for line in self.logs))

    def test_request_queue_overflow_does_not_dispatch_unaccepted_work(self):
        connection, _ = self.connect("flood")
        entered, release, finished = threading.Event(), threading.Event(), threading.Event()
        requests = []

        def dispatch(payload, emit=None):
            requests.append(payload)
            entered.set()
            release.wait(3)
            finished.set()
            return []

        with patch.object(self.runtime, "websocket_responses", side_effect=dispatch):
            self.send(connection, {"type": "probe", "chatId": "flood"})
            self.assertTrue(entered.wait(2))
            try:
                for index in range(129):
                    self.send(connection, {"type": "probe", "chatId": "flood", "index": index})
                self.wait_for(lambda: any("reason=request_queue_full" in line for line in self.logs))
            finally:
                release.set()
            self.assertTrue(finished.wait(2))
            self.assert_closed("flood", "request_queue_full")
            self.assertEqual(len(requests), 1)


if __name__ == "__main__":
    unittest.main()
