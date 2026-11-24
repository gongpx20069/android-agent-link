import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.control import execute
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.shared_state import SharedState, ControlError
from android_acp_bridge.stdlib_server import BridgeHTTPServer
from android_acp_bridge.tool_details import ToolDetails


def event(kind="tool_call", **fields):
    return {"type": "session/update", "update": {"sessionUpdate": kind, "toolCallId": "tool", **fields}}


class ToolDetailsTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "state.db"
        self.shared = SharedState(self.path)
        self.addCleanup(lambda: self.shared.close())
        self.shared.register({"chatId": "a", "agentId": "copilot-cli", "workspacePath": self.folder.name})
        self.shared.register({"chatId": "b", "agentId": "copilot-cli", "workspacePath": self.folder.name})
        self.store = ToolDetails(self.shared)

    def test_snapshot_is_immutable_paged_and_scope_bound(self):
        summary = self.store.capture("a", event(title="Read", rawInput={"path": "file"},
                                                rawOutput="secret" * 10000), "session-a")["update"]
        self.assertNotIn("secret", json.dumps(summary))
        self.assertLess(len(json.dumps(summary)), 1024)
        ref = summary["detailRef"]
        first = self.store.page("a", ref)
        self.store.capture("a", event("tool_call_update", status="completed", rawOutput="final"), "session-a")
        text, page = first["text"], first
        while page["next"] is not None:
            page = self.store.page("a", ref, first["snapshot"], page["next"])
            self.assertLess(len(json.dumps(page).encode()), 32768)
            text += page["text"]
        self.assertEqual(json.loads(text)["rawOutput"], "secret" * 10000)
        latest = self.store.page("a", ref)
        self.assertEqual(json.loads(latest["text"])["rawOutput"], "final")
        self.assertEqual(json.loads(latest["text"])["rawInput"], {"path": "file"})
        self.assertGreater(latest["version"], first["version"])
        with self.assertRaises(ControlError):
            self.store.page("b", ref)
        with self.assertRaises(ControlError):
            self.store.page("b", ref, first["snapshot"])
        self.shared.close()
        self.shared = SharedState(self.path)
        self.store = ToolDetails(self.shared)
        self.assertEqual(self.store.page("a", ref)["version"], latest["version"])

    def test_limits_expiration_deletion_and_reused_tool_ids(self):
        first = self.store.capture("a", event(rawOutput="old"), "session")["update"]
        second = self.store.capture("a", event(rawOutput="new"), "session")["update"]
        self.assertNotEqual(first["detailRef"], second["detailRef"])
        self.store.MAX_DETAIL_BYTES = 500
        limited = self.store.capture("a", event("tool_call_update", rawOutput="x" * 501), "session")["update"]
        self.assertTrue(limited["detailsTruncated"])
        self.assertTrue(json.loads(self.store.page("a", limited["detailRef"])["text"])["truncated"])
        with self.shared.db:
            self.shared.db.execute("UPDATE tool_details SET updated=0")
        with self.assertRaises(ControlError):
            self.store.page("a", first["detailRef"])
        fresh = self.store.capture("a", event(rawOutput="fresh"), "session")["update"]
        self.store.page("a", fresh["detailRef"])
        self.shared.delete_chat("a")
        self.assertEqual(self.shared.db.execute("SELECT count(*) FROM tool_details").fetchone()[0], 0)
        self.assertEqual(self.shared.db.execute("SELECT count(*) FROM tool_snapshots").fetchone()[0], 0)

    def test_existing_journal_is_migrated_without_body_leak(self):
        raw = {**event(rawInput="private"), "chatId": "a", "eventId": 1, "timestamp": 1}
        self.shared.append("a", raw)
        ToolDetails(self.shared)
        saved = self.shared.events("a")["events"][0]
        self.assertNotIn("private", json.dumps(saved))
        self.assertEqual(json.loads(self.store.page("a", saved["update"]["detailRef"])["text"])["rawInput"], "private")

    def test_unicode_wire_budget_quota_and_snapshot_expiration(self):
        summary = self.store.capture("a", event(rawOutput="\U0001f642" * 6000), "s")["update"]
        page = self.store.page("a", summary["detailRef"])
        while True:
            self.assertLess(len(json.dumps(page).encode("utf-8")), 32768)
            if page["next"] is None:
                break
            page = self.store.page("a", summary["detailRef"], page["snapshot"], page["next"])
        with self.shared.db:
            self.shared.db.execute("UPDATE tool_snapshots SET updated=0")
        with self.assertRaises(ControlError):
            self.store.page("a", summary["detailRef"], page["snapshot"], 0)
        self.store.QUOTA_BYTES = 2000
        for i in range(10):
            current = self.store.capture("a", event(rawOutput="x" * 400), f"quota-{i}")["update"]
            self.store.page("a", current["detailRef"])
        total = sum(self.shared.db.execute(f"SELECT COALESCE(SUM(size),0) FROM {table}").fetchone()[0]
                    for table in ("tool_details", "tool_snapshots"))
        self.assertLessEqual(total, self.store.QUOTA_BYTES)

    def test_history_projects_final_state_and_storage_failure_never_publishes_body(self):
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore())
        self.addCleanup(runtime.shared.close)
        updates = runtime._project_tool_history("a", [
            event(rawInput="first-input"), event("tool_call_update", rawOutput="last-output", status="completed")])
        self.assertEqual(updates[0]["update"]["detailVersion"], updates[1]["update"]["detailVersion"])
        self.assertEqual(updates[0]["update"]["status"], "completed")
        self.assertNotIn("first-input", json.dumps(updates))
        received = []
        runtime._chat_subscribers["a"] = [received.append]
        runtime.shared.db.execute("CREATE TRIGGER fail_details BEFORE INSERT ON tool_details BEGIN SELECT RAISE(ABORT,'test failure'); END")
        import sqlite3
        with self.assertRaises(sqlite3.Error):
            runtime._record_broadcast("a", event(rawInput="must-not-leak"))
        self.assertEqual(received, [])

    def test_runtime_live_replay_control_and_authenticated_http(self):
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore())
        self.addCleanup(runtime.shared.close)
        runtime.shared.register({"chatId": "a", "agentId": "copilot-cli", "workspacePath": self.folder.name})
        received = []
        runtime._chat_subscribers["a"] = [received.append]
        runtime._record_broadcast("a", event(title="Read", rawOutput="sensitive" * 50000))
        self.assertNotIn("sensitive", json.dumps(received))
        replay = runtime.websocket_responses({"type": "chat.attach", "chatId": "a"})
        self.assertNotIn("sensitive", json.dumps(replay))
        page = execute(runtime, {"action": "chat.read", "chatId": "a"})
        self.assertNotIn("sensitive", json.dumps(page))
        ref = received[0]["update"]["detailRef"]
        self.assertIn("sensitive", execute(runtime, {"action": "tool.read", "chatId": "a", "ref": ref})["text"])
        server = BridgeHTTPServer(("127.0.0.1", 0), runtime)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/tool-details?chatId=a&ref={ref}"
            with self.assertRaises(HTTPError) as denied:
                urlopen(url)
            self.assertEqual(denied.exception.code, 401)
            token = runtime.issue_device_token()
            with urlopen(Request(url, headers={"Authorization": f"Bearer {token}"})) as response:
                self.assertEqual(response.headers["Cache-Control"], "no-store")
                payload = response.read()
                self.assertLess(len(payload), 32768)
                self.assertIn(b"sensitive", payload)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
