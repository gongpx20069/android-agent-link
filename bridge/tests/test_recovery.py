from __future__ import annotations

import contextlib
import io
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentManager, AcpAgentSession
from android_acp_bridge.config import BridgeConfig, default_device_token_store
from android_acp_bridge.device_tokens import DeviceTokenStore, DeviceTokenStoreError
from android_acp_bridge.history import HistoryError, HistoryStore
from android_acp_bridge.main import main
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.stdlib_server import BridgeRequestHandler


def runtime(path: Path | None = None, manager=None) -> BridgeRuntime:
    return BridgeRuntime(BridgeConfig(machine_name="test", device_token_store=path), PairingStore(), False, manager)


class DeviceTokenRecoveryTests(unittest.TestCase):
    def test_tokens_survive_restart_without_storing_credentials(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            path = Path(directory) / "private" / "tokens.json"
            first = runtime(path)
            token = first.issue_device_token()
            second_token = first.issue_device_token()
            restarted = runtime(path)
            self.assertTrue(restarted.is_device_token_valid(token))
            self.assertTrue(restarted.is_device_token_valid(second_token))
            self.assertFalse(restarted.is_device_token_valid(token + "wrong"))
            data = path.read_text()
            self.assertNotIn(token, data)
            self.assertNotIn(second_token, data)
            self.assertNotIn("dev_", data)
            self.assertEqual(set(json.loads(data)), {"version", "hashes"})
            self.assertEqual(len(json.loads(data)["hashes"]), 2)
            if os.name != "nt":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)

    def test_default_runtime_never_touches_user_home(self) -> None:
        with patch.object(DeviceTokenStore, "_make_private", side_effect=AssertionError("unexpected disk access")):
            instance = runtime()
            self.assertTrue(instance.is_device_token_valid(instance.issue_device_token()))

    def test_http_logs_never_contain_device_token_query(self) -> None:
        handler = MagicMock()
        handler.address_string.return_value = "localhost"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            BridgeRequestHandler.log_message(handler, '"%s" %s', "GET /ws?token=dev_secret HTTP/1.1", "101")
        self.assertNotIn("dev_secret", output.getvalue())
        self.assertIn("/ws?[redacted]", output.getvalue())

    def test_corrupt_store_and_failed_atomic_write_fail_explicitly(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            path = Path(directory) / "private" / "tokens.json"
            instance = runtime(path)
            token = instance.issue_device_token()
            previous = path.read_bytes()
            with patch("android_acp_bridge.device_tokens.os.replace", side_effect=OSError("sensitive value")):
                with self.assertRaises(DeviceTokenStoreError) as failed:
                    instance.issue_device_token()
            self.assertNotIn("sensitive", str(failed.exception))
            self.assertEqual(path.read_bytes(), previous)
            self.assertTrue(runtime(path).is_device_token_valid(token))
            self.assertEqual(list(path.parent.glob("*.pending")), [])
            path.write_text('{"version":1,"hashes":["dev_secret"]}', encoding="utf-8")
            with self.assertRaises(DeviceTokenStoreError) as corrupt:
                runtime(path)
            self.assertNotIn("dev_secret", str(corrupt.exception))

    def test_production_start_wires_durable_store(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            path = Path(directory) / "private" / "tokens.json"
            issued = []
            with (
                patch("android_acp_bridge.main.run_server", side_effect=lambda instance: issued.append(instance.issue_device_token())),
                patch("android_acp_bridge.main.render_terminal_qr", return_value=""),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(main(["start", "--transport", "local", "--device-token-store", str(path)]), 0)
            self.assertTrue(runtime(path).is_device_token_valid(issued[0]))
            self.assertEqual(default_device_token_store().name, "device-tokens.json")


PERMISSION = {
    "params": {
        "toolCall": {"kind": "edit", "title": "Modify test file", "path": "test.txt"},
        "options": [
            {"kind": "allow_once", "optionId": "yes"},
            {"kind": "reject_once", "optionId": "no"},
        ],
    },
}


class ApprovalRecoveryTests(unittest.TestCase):
    def test_permission_without_approval_callback_never_defaults_to_allow(self) -> None:
        session = MagicMock()
        session.permission_callback = None
        AcpAgentSession._handle_permission_request(session, PERMISSION)
        self.assertEqual(session._write_json.call_args.args[0]["result"]["outcome"], {"outcome": "selected", "optionId": "no"})
        session.permission_callback = lambda _: "reject-once"
        AcpAgentSession._handle_permission_request(session, {
            "params": {"options": [{"kind": "allow_once", "optionId": "yes"}]},
        })
        self.assertEqual(session._write_json.call_args.args[0]["result"]["outcome"], {"outcome": "cancelled"})

    def test_attach_snapshot_is_chat_scoped_and_independent_of_checkpoint(self) -> None:
        instance = runtime()
        requested = threading.Event()
        events = []
        result = []

        def emit(event):
            events.append(instance._append_event("chat-a", event))
            if event["type"] == "approval.requested":
                requested.set()

        with patch("android_acp_bridge.runtime.APPROVAL_TIMEOUT_SECONDS", 2):
            worker = threading.Thread(target=lambda: result.append(instance._request_permission("chat-a", PERMISSION, emit)))
            worker.start()
            try:
                self.assertTrue(requested.wait(1))
                approval = next(event for event in events if event["type"] == "approval.requested")
                attached = instance.websocket_responses({
                    "type": "chat.attach", "chatId": "chat-a", "lastEventId": approval["eventId"],
                })
                self.assertNotIn("approval.requested", [event["type"] for event in attached])
                snapshot = next(event for event in attached if event["type"] == "approval.snapshot")
                self.assertEqual(len(snapshot["approvals"]), 1)
                recovered = snapshot["approvals"][0]
                self.assertEqual(recovered["details"], PERMISSION["params"]["toolCall"])
                self.assertEqual(recovered["expiresAt"] - recovered["createdAt"], 2000)
                self.assertGreater(recovered["createdAt"], 1_700_000_000_000)
                recovered["details"]["title"] = "mutated"
                other_chat = instance.websocket_responses({"type": "chat.attach", "chatId": "chat-b"})
                self.assertEqual(next(event for event in other_chat if event["type"] == "approval.snapshot")["approvals"], [])
                rejected = instance.websocket_responses({
                    "type": "approval.decide", "chatId": "chat-b", "approvalId": approval["approvalId"], "decision": "approved",
                })
                self.assertFalse(rejected[-2]["resolved"])
                acknowledgement = instance.websocket_responses({
                    "type": "approval.decide", "chatId": "chat-a", "approvalId": approval["approvalId"], "decision": "denied",
                })[-2]
                self.assertEqual(acknowledgement["status"], "denied")
                self.assertTrue(acknowledgement["resolved"])
            finally:
                worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result, ["no"])
        attached = instance.websocket_responses({"type": "chat.attach", "chatId": "chat-a"})
        self.assertEqual(next(event for event in attached if event["type"] == "approval.snapshot")["approvals"], [])
        resolution = next(event for event in attached if event["type"] == "approval.resolved")
        self.assertEqual(resolution["status"], "denied")
        self.assertIsInstance(resolution["decidedAt"], int)

    def test_immediate_decision_does_not_miss_notification_and_is_idempotent(self) -> None:
        instance = runtime()
        events = []
        acknowledgement = []

        def emit(event):
            events.append(instance._append_event("chat-a", event))
            if event["type"] == "approval.requested":
                acknowledgement.extend(instance.websocket_responses({
                    "type": "approval.decide", "approvalId": event["approvalId"], "decision": "approved",
                }))

        with (
            patch("android_acp_bridge.runtime.APPROVAL_TIMEOUT_SECONDS", 0.05),
            patch.object(threading.Condition, "wait", side_effect=AssertionError("Missed an already-resolved decision")),
        ):
            self.assertEqual(instance._request_permission("chat-a", PERMISSION, emit), "yes")
        approval_id = acknowledgement[-2]["approvalId"]
        retry = instance.websocket_responses({"type": "approval.decide", "approvalId": approval_id, "decision": "denied"})
        self.assertEqual(retry[-2]["status"], "approved")
        self.assertTrue(retry[-2]["resolved"])
        self.assertEqual(len([event for event in events if event["type"] == "approval.resolved"]), 1)
        self.assertEqual(instance._pending_approvals, {})

    def test_timeout_emits_explicit_terminal_event_and_late_decision_cannot_approve(self) -> None:
        instance = runtime()
        with patch("android_acp_bridge.runtime.APPROVAL_TIMEOUT_SECONDS", 0.01):
            self.assertEqual(instance._request_permission("chat-a", PERMISSION, None), "no")
        events = instance.websocket_responses({"type": "chat.attach", "chatId": "chat-a"})
        resolution = next(event for event in events if event["type"] == "approval.resolved")
        self.assertEqual(resolution["status"], "expired")
        retry = instance.websocket_responses({
            "type": "approval.decide", "approvalId": resolution["approvalId"], "decision": "approved",
        })[-2]
        self.assertEqual(retry["status"], "expired")
        self.assertTrue(retry["resolved"])


def text(role: str, value: str):
    return {"update": {"sessionUpdate": f"{role}_message_chunk", "content": {"text": value}}}


HISTORY = [
    text("user", "first question"),
    {"timestamp": 42, "update": {"sessionUpdate": "tool_call", "toolCallId": "edit-1", "title": "Edit file", "kind": "edit",
                              "content": [{"type": "diff", "path": "test.txt", "oldText": "old", "newText": "new"}]}},
    {"update": {"sessionUpdate": "plan", "entries": [{"content": "Implement fix", "status": "completed"}]}},
    text("agent", "first answer"),
    text("user", "second question"),
    {"update": {"sessionUpdate": "config_option_update", "configOptions": [{"id": "model"}]}},
    text("agent", "second answer"),
]


class HistoryRecoveryTests(unittest.TestCase):
    def test_explicit_history_load_rotates_only_its_chat_replay_generation(self) -> None:
        manager = MagicMock()
        manager.load_recent_session.return_value = {"updates": HISTORY, "scannedEvents": 7, "truncated": False}
        instance = runtime(manager=manager)
        instance._append_event("chat-a", {"type": "session/update", "update": {"text": "OLD_SESSION"}})
        instance._append_event("chat-b", {"type": "session/update", "update": {"text": "OTHER_CHAT"}})
        original_generation = instance._event_generation
        result = instance.websocket_responses({
            "type": "session.loadRecent", "chatId": "chat-a", "sessionId": "new-session",
            "workspacePath": str(Path.cwd()), "limit": 2,
        })[1]
        self.assertEqual(result["latestEventId"], 0)
        self.assertNotEqual(result["eventGeneration"], original_generation)
        attached = instance.websocket_responses({
            "type": "chat.attach", "chatId": "chat-a", "lastEventId": 1,
            "lastEventGeneration": original_generation,
        })
        self.assertTrue(attached[0]["checkpointReset"])
        self.assertNotIn("OLD_SESSION", json.dumps(attached))
        self.assertEqual(attached[0]["eventGeneration"], result["eventGeneration"])
        other = instance.websocket_responses({"type": "chat.attach", "chatId": "chat-b"})
        self.assertEqual(other[0]["eventGeneration"], original_generation)
        self.assertIn("OTHER_CHAT", json.dumps(other))
        instance.websocket_responses({
            "type": "session.history", "chatId": "chat-a", "sessionId": "new-session",
            "historyId": result["historyId"], "before": result["nextBefore"], "limit": 2,
        })
        self.assertEqual(instance._chat_event_generations["chat-a"], result["eventGeneration"])
        manager.load_recent_session.assert_called_once()

    def test_failed_or_busy_history_load_preserves_existing_replay(self) -> None:
        manager = MagicMock()
        manager.load_recent_session.side_effect = AcpAgentError("load failed")
        instance = runtime(manager=manager)
        previous = instance._append_event("chat-a", {"type": "session/update", "update": {"text": "KEEP_ME"}})
        request = {"type": "session.loadRecent", "chatId": "chat-a", "sessionId": "new", "workspacePath": str(Path.cwd())}
        failed = instance.websocket_responses(request)[0]
        self.assertIn("error", failed)
        self.assertEqual(instance._event_logs["chat-a"], [previous])
        self.assertNotIn("chat-a", instance._chat_event_generations)
        self.assertEqual(instance._history_loading_chats, set())
        instance._active_prompts["chat-a"] = MagicMock()
        busy = instance.websocket_responses(request)[0]
        self.assertEqual(busy["errorCode"], "session_busy")
        manager.load_recent_session.assert_called_once()
        self.assertEqual(instance._event_logs["chat-a"], [previous])

    def test_prompt_cannot_start_during_history_replacement(self) -> None:
        manager = MagicMock()
        instance = runtime(manager=manager)
        prompt_responses = []

        def loading(*_):
            prompt_responses.extend(instance.websocket_responses({
                "type": "chat.prompt", "chatId": "chat-a", "operationId": "old-session-operation", "content": "not now",
            }))
            return {"updates": HISTORY, "scannedEvents": 7, "truncated": False}

        manager.load_recent_session.side_effect = loading
        result = instance.websocket_responses({
            "type": "session.loadRecent", "chatId": "chat-a", "sessionId": "new", "workspacePath": str(Path.cwd()),
        })[1]
        self.assertIn("eventGeneration", result)
        self.assertEqual(prompt_responses[0]["status"], "failed")
        self.assertIn("loading", prompt_responses[0]["error"])
        manager.prompt.assert_not_called()
        self.assertEqual(instance._history_loading_chats, set())

    def test_history_retains_diff_and_paginates_immutable_snapshot_without_loading(self) -> None:
        manager = MagicMock()
        manager.load_recent_session.return_value = {"updates": HISTORY, "scannedEvents": len(HISTORY), "truncated": False}
        instance = runtime(manager=manager)
        loaded = instance.websocket_responses({
            "type": "session.loadRecent", "chatId": "chat-a", "sessionId": "session-a",
            "workspacePath": str(Path.cwd()), "limit": 2,
        })[1]
        self.assertEqual(loaded["totalMessages"], 4)
        self.assertTrue(loaded["hasMore"])
        self.assertEqual([row["text"] for row in loaded["messages"] if row["kind"] == "text"], ["second question", "second answer"])
        self.assertTrue(any(row["kind"] == "control" for row in loaded["messages"]))
        request = {
            "type": "session.history", "chatId": "chat-a", "sessionId": "session-a",
            "historyId": loaded["historyId"], "before": loaded["nextBefore"], "limit": 2,
        }
        older = instance.websocket_responses(request)[0]
        self.assertFalse(older["hasMore"])
        self.assertIsNone(older["nextBefore"])
        self.assertEqual(older["totalMessages"], 4)
        self.assertEqual([row["text"] for row in older["messages"] if row["kind"] == "text"], ["first question", "first answer"])
        activity = next(row for row in older["messages"] if row["kind"] == "activity")
        self.assertEqual(activity["timestampMillis"], 42)
        self.assertEqual(activity["activityId"], "edit-1")
        self.assertIn('"newText":"new"', activity["details"])
        self.assertTrue(any(row["kind"] == "plan" for row in older["messages"]))
        original_id = activity["historyItemId"]
        activity["details"] = "mutated"
        again = instance.websocket_responses(request)[0]
        again_activity = next(row for row in again["messages"] if row["kind"] == "activity")
        self.assertEqual(again_activity["historyItemId"], original_id)
        self.assertNotEqual(again_activity["details"], "mutated")
        manager.load_recent_session.assert_called_once()
        manager.load_session.assert_not_called()
        manager.restore_session.assert_not_called()

    def test_snapshots_are_bound_bounded_and_expire_explicitly(self) -> None:
        store = HistoryStore(max_snapshots=2, ttl_seconds=10)
        first = store.create("a", "s", HISTORY, 7, 1)
        with self.assertRaises(HistoryError) as mismatch:
            store.page("b", "s", first["historyId"], first["nextBefore"], 1)
        self.assertEqual(mismatch.exception.code, "history_mismatch")
        with self.assertRaises(HistoryError) as cursor:
            store.page("a", "s", first["historyId"], "bad", 1)
        self.assertEqual(cursor.exception.code, "invalid_cursor")
        store.create("b", "s", HISTORY, 7, 1)
        store.create("c", "s", HISTORY, 7, 1)
        with self.assertRaises(HistoryError) as evicted:
            store.page("a", "s", first["historyId"], 0, 1)
        self.assertEqual(evicted.exception.code, "history_expired")
        latest = store.create("c", "s", HISTORY, 7, 1)
        self.assertEqual(len(store._snapshots), 2)
        with patch("android_acp_bridge.history.time.monotonic", return_value=float("inf")):
            with self.assertRaises(HistoryError) as expired:
                store.page("c", "s", latest["historyId"], 0, 1)
        self.assertEqual(expired.exception.code, "history_expired")

    def test_history_page_error_is_explicit_on_wire(self) -> None:
        response = runtime().websocket_responses({
            "type": "session.history", "chatId": "a", "sessionId": "s", "historyId": "missing", "before": 0,
        })[0]
        self.assertEqual(response["type"], "session.history.result")
        self.assertEqual(response["errorCode"], "history_expired")
        self.assertNotIn("hasMore", response)

    def test_recent_load_binds_correct_workspace_and_preserves_live_session_on_failure(self) -> None:
        manager = AcpAgentManager()
        old_session = MagicMock()
        manager._set_session("a", old_session)
        session = MagicMock()
        workspace = str(Path.cwd())
        with patch.object(AcpAgentSession, "load_recent", return_value=(session, HISTORY, 7, False)) as load:
            manager.load_recent_session("a", "copilot-cli", workspace, "session-a", 2)
            load.assert_called_once_with("copilot-cli", workspace, "session-a", 2)
            self.assertIs(manager._get_session("a"), session)
        old_session.stop.assert_called_once()
        with patch.object(AcpAgentSession, "load_recent", side_effect=AcpAgentError("load failed")):
            with self.assertRaises(AcpAgentError):
                manager.load_recent_session("a", "copilot-cli", workspace, "missing", 2)
        self.assertIs(manager._get_session("a"), session)
        session.stop.assert_not_called()
        with self.assertRaisesRegex(AcpAgentError, "workspacePath"):
            manager.load_recent_session("a", "copilot-cli", "", "session-a", 2)


if __name__ == "__main__":
    unittest.main()
