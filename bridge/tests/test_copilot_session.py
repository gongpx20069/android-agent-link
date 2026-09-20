from __future__ import annotations

import asyncio
import queue
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentManager
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.copilot_session import CopilotAgentSession, CopilotEventProjection
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime


def event(kind, **data):
    return {"type": kind, "id": kind + "-id", "data": data}


def wait_until(predicate):
    deadline = time.monotonic() + 3
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("Expected event was not delivered")
        time.sleep(0.005)


class FakeNative:
    def __init__(self):
        self.session_id = "native-session"
        self.receive = None
        self.sent = threading.Event()
        self.send = AsyncMock(side_effect=self._send)
        self.abort = AsyncMock(side_effect=lambda: self.emit("session.idle", aborted=True))
        self.set_model = AsyncMock()
        self.get_events = AsyncMock(return_value=[])
        self.rpc = SimpleNamespace(
            model=SimpleNamespace(get_current=AsyncMock(return_value=SimpleNamespace(model_id="preserved-model"))),
            permissions=SimpleNamespace(
                get_mode=AsyncMock(return_value=SimpleNamespace(mode=SimpleNamespace(value="manual"))),
                set_approve_all=AsyncMock(return_value=SimpleNamespace(success=True)),
            ),
        )

    async def _send(self, prompt):
        self.emit("user.message", content=prompt)
        self.emit("assistant.turn_start", turnId="1")
        self.sent.set()
        return "accepted-message"

    def emit(self, kind, **data):
        value = event(kind, **data)
        self.receive(SimpleNamespace(to_dict=lambda: value))


class NativeSessionTests(unittest.TestCase):
    def setUp(self):
        self.native = FakeNative()
        self.client = SimpleNamespace(
            start=AsyncMock(), stop=AsyncMock(), ping=AsyncMock(),
            list_models=AsyncMock(return_value=[SimpleNamespace(id="preserved-model", name="Saved model")]),
            list_sessions=AsyncMock(return_value=[SimpleNamespace(session_id="native-session")]),
        )
        async def open_session(*args, **kwargs):
            self.native.receive = kwargs["on_event"]
            self.permission = kwargs["on_permission_request"]
            return self.native
        self.client.create_session = AsyncMock(side_effect=open_session)
        self.client.resume_session = AsyncMock(side_effect=open_session)
        self.patch_client = patch("copilot.CopilotClient", return_value=self.client)
        self.patch_cli = patch("android_acp_bridge.copilot_session.shutil.which", return_value="copilot")
        self.patch_client.start()
        self.patch_cli.start()
        self.session = CopilotAgentSession.start("copilot-cli", str(Path.cwd()))
        self.session.take_pending_updates()
        self.threads = []

    def tearDown(self):
        self.session.stop()
        for thread in self.threads:
            thread.join(3)
            self.assertFalse(thread.is_alive())
        self.patch_cli.stop()
        self.patch_client.stop()

    def start_prompt(self, callback=None):
        result = {}
        def run():
            try:
                result["updates"] = self.session.prompt("work", callback)
            except AcpAgentError as exc:
                result["error"] = str(exc)
        thread = threading.Thread(target=run)
        self.threads.append(thread)
        thread.start()
        self.assertTrue(self.native.sent.wait(3))
        return thread, result

    def test_reply_and_tool_launch_completion_do_not_end_background_work(self):
        delivered = []
        thread, result = self.start_prompt(delivered.append)
        self.native.emit("subagent.started", toolCallId="launch", agentDisplayName="Worker")
        self.native.emit("tool.execution_complete", toolCallId="launch", success=True, result={"content": "Started"})
        self.native.emit("assistant.message", messageId="main", content="Waiting for workers.", phase="final_answer")
        self.native.emit("assistant.turn_end", turnId="1")
        self.native.emit("tool.execution_start", toolCallId="late", toolName="read", arguments={"path": "file"})
        self.native.emit("tool.execution_partial_result", toolCallId="late", partialOutput="late output")
        wait_until(lambda: any(u["update"].get("rawOutput") == "late output" for u in delivered))
        self.assertTrue(thread.is_alive())
        self.assertNotIn("updates", result)
        self.native.emit("tool.execution_complete", toolCallId="late", success=True, result={"content": "done"})
        self.native.emit("subagent.completed", toolCallId="launch", agentDisplayName="Worker")
        self.native.emit("assistant.message", messageId="final", content="Finished.")
        self.native.emit("session.idle")
        thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(result["updates"], [])
        self.assertEqual(delivered[-1]["update"]["content"]["text"], "Finished.")

    def test_multiple_workers_and_autopilot_idle_do_not_end_prompt(self):
        delivered = []
        thread, result = self.start_prompt(delivered.append)
        for identity in ("a", "b"):
            self.native.emit("subagent.started", toolCallId=identity, agentDisplayName=identity)
        self.native.emit("subagent.completed", toolCallId="a", agentDisplayName="a")
        self.native.emit("session.idle", mode="autopilot")
        self.native.emit("assistant.message", messageId="still", content="Worker b is running")
        wait_until(lambda: any(u["update"].get("messageId") == "still" for u in delivered))
        self.assertTrue(thread.is_alive())
        self.native.emit("subagent.completed", toolCallId="b", agentDisplayName="b")
        self.native.emit("session.idle", mode="interactive")
        thread.join(3)
        self.assertIn("updates", result)

    def test_child_idle_and_error_cannot_finish_parent(self):
        delivered = []
        thread, result = self.start_prompt(delivered.append)
        for kind in ("session.idle", "session.error", "session.shutdown"):
            self.session._receive(SimpleNamespace(
                to_dict=lambda kind=kind: {**event(kind, message="child failed"), "agentId": "child"}))
        self.native.emit("assistant.message", messageId="parent", content="Parent still running")
        wait_until(lambda: bool(delivered))
        self.assertTrue(thread.is_alive())
        self.assertNotIn("error", result)
        self.native.emit("session.idle")

    def test_subscription_survives_prompt_completion(self):
        delivered = []
        thread, _ = self.start_prompt(delivered.append)
        self.native.emit("session.idle")
        thread.join(3)
        self.native.emit("tool.execution_complete", toolCallId="post-idle", success=True, result={"content": "late"})
        wait_until(lambda: any(u["update"].get("toolCallId") == "post-idle" for u in delivered))

    def test_previous_idle_is_not_completion_of_next_prompt(self):
        self.native.emit("session.idle")
        delivered = []
        thread, result = self.start_prompt(delivered.append)
        self.native.emit("assistant.message", messageId="new", content="new turn")
        wait_until(lambda: bool(delivered))
        self.assertTrue(thread.is_alive())
        self.assertNotIn("updates", result)
        self.native.emit("session.idle")

    def test_output_delivery_finishes_before_prompt_waiter(self):
        entered, release = threading.Event(), threading.Event()
        def deliver(update):
            entered.set()
            release.wait(3)
        thread, result = self.start_prompt(deliver)
        self.native.emit("assistant.message", messageId="slow", content="persist me")
        self.assertTrue(entered.wait(3))
        self.native.emit("session.idle")
        self.assertTrue(thread.is_alive())
        self.assertNotIn("updates", result)
        release.set()
        thread.join(3)
        self.assertIn("updates", result)

    def test_background_permission_does_not_block_reader(self):
        requested, approve = threading.Event(), threading.Event()
        delivered = []
        def permission(message):
            requested.set()
            approve.wait(3)
            return "allow-once"
        self.session.permission_callback = permission
        thread, result = self.start_prompt(delivered.append)
        request = SimpleNamespace(to_dict=lambda: {"kind": "shell", "toolCallId": "permission", "fullCommandText": "tests"})
        decision = asyncio.run_coroutine_threadsafe(self.permission(request, {}), self.session._loop)
        self.assertTrue(requested.wait(3))
        self.native.emit("assistant.message", messageId="while-pending", content="Other worker progress")
        wait_until(lambda: any(u["update"].get("messageId") == "while-pending" for u in delivered))
        self.assertTrue(thread.is_alive())
        approve.set()
        self.assertEqual(decision.result(3).kind, "approve-once")
        self.native.emit("session.idle")
        thread.join(3)
        self.assertIn("updates", result)

    def test_pending_human_approval_does_not_own_a_non_daemon_executor(self):
        requested, release = threading.Event(), threading.Event()
        workers = []
        def permission(message):
            workers.append(threading.current_thread())
            requested.set()
            release.wait(3)
            return "reject-once"
        self.session.permission_callback = permission
        decision = asyncio.run_coroutine_threadsafe(
            self.permission(SimpleNamespace(to_dict=lambda: {"kind": "shell"}), {}),
            self.session._loop)
        try:
            self.assertTrue(requested.wait(3))
            self.assertTrue(workers[0].daemon)
            decision.cancel()
            self.session._run(asyncio.sleep(0))
            self.session.stop()
            self.assertFalse(self.session._loop_thread.is_alive())
        finally:
            release.set()
            for worker in workers:
                worker.join(3)

    def test_cancel_is_not_success(self):
        thread, result = self.start_prompt()
        self.session.cancel_prompt()
        thread.join(3)
        self.assertEqual(result["updates"][-1]["update"]["sessionUpdate"], "agentlink_prompt_cancelled")
        self.native.abort.assert_awaited_once()

    def test_rejected_cancellation_is_not_reported_as_cancelled(self):
        thread, result = self.start_prompt()
        self.native.abort.side_effect = RuntimeError("abort rejected")
        with self.assertRaisesRegex(AcpAgentError, "abort rejected"):
            self.session.cancel_prompt()
        self.native.emit("session.idle")
        thread.join(3)
        self.assertEqual(result["updates"], [])

    def test_unsolicited_abort_is_not_success(self):
        thread, result = self.start_prompt()
        self.native.emit("session.idle", aborted=True)
        thread.join(3)
        self.assertEqual(result["updates"][-1]["update"]["sessionUpdate"], "agentlink_prompt_cancelled")

    def test_unexpected_shutdown_fails_waiter(self):
        thread, result = self.start_prompt()
        self.native.emit("session.shutdown")
        thread.join(3)
        self.assertIn("shut down", result["error"])

    def test_session_error_is_not_success_and_prevents_resend(self):
        thread, result = self.start_prompt()
        self.native.emit("session.error", errorType="fatal", message="failed")
        thread.join(3)
        self.assertIn("failed", result["error"])
        with self.assertRaises(AcpAgentError):
            self.session.prompt("must not run")
        self.assertEqual(self.native.send.await_count, 1)

    def test_transport_loss_fails_without_idle(self):
        self.session.HEARTBEAT_SECONDS = 0.01
        self.client.ping.side_effect = OSError("process exited")
        thread, result = self.start_prompt()
        thread.join(3)
        self.assertIn("process exited", result["error"])

    def test_delivery_error_is_visible(self):
        def failed(update):
            raise OSError("persistence unavailable")
        thread, result = self.start_prompt(failed)
        self.native.emit("assistant.message", messageId="failed", content="text")
        thread.join(3)
        self.assertIn("delivery failed", result["error"])

    def test_queue_overflow_fails_instead_of_silently_losing_events(self):
        thread, result = self.start_prompt()
        with patch.object(self.session._events, "put_nowait", side_effect=queue.Full):
            self.native.emit("assistant.message", messageId="lost", content="text")
        thread.join(3)
        self.assertIn("queue overflow", result["error"])
        with self.assertRaises(AcpAgentError):
            self.session.prompt("must not run")

    def test_queued_failure_is_checked_before_sending(self):
        self.native.emit("session.error", message="failed before send")
        with self.assertRaisesRegex(AcpAgentError, "failed before send"):
            self.session.prompt("must not run")
        self.native.send.assert_not_awaited()

    def test_resume_keeps_model_and_does_not_restart_pending_work(self):
        self.session.stop()
        self.session = CopilotAgentSession.load_for_continue("copilot-cli", str(Path.cwd()), "native-session", True)
        kwargs = self.client.resume_session.call_args.kwargs
        self.assertNotIn("model", kwargs)
        self.assertFalse(kwargs["continue_pending_work"])
        self.assertEqual(self.session.config_option_updates()[0]["update"]["configOptions"][0]["currentValue"], "preserved-model")

    def test_same_live_history_does_not_replace_process(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        manager._set_session("chat", self.session)
        self.native.get_events.return_value = [
            SimpleNamespace(to_dict=lambda: event("assistant.message", messageId="history", content="saved"))]
        page = manager.load_recent_session("chat", "copilot-cli", str(Path.cwd()), "native-session", 5)
        self.assertEqual(page["updates"][0]["update"]["content"]["text"], "saved")
        self.assertEqual(self.client.resume_session.await_count, 0)
        self.client.stop.assert_not_awaited()

    def test_second_chat_cannot_resume_an_owned_session(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        manager._set_session("owner", self.session)
        with self.assertRaisesRegex(AcpAgentError, "another chat"):
            manager.load_recent_session("other", "copilot-cli", str(Path.cwd()), "native-session", 5)
        with self.assertRaisesRegex(AcpAgentError, "another chat"):
            manager.restore_session("other", "copilot-cli", str(Path.cwd()), "native-session", True, lambda binding: None)
        self.client.resume_session.assert_not_awaited()
        self.client.stop.assert_not_awaited()

    def test_inflight_resume_reserves_session_across_chats(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        with manager._claim_session("owner", "copilot-cli", "native-session"):
            with self.assertRaisesRegex(AcpAgentError, "another chat"):
                manager.restore_session("other", "copilot-cli", str(Path.cwd()), "native-session", True, lambda binding: None)
        self.assertEqual(manager._session_claims, {})
        self.client.resume_session.assert_not_awaited()

    def test_native_listing_preserves_workspace_filter(self):
        self.client.list_sessions.return_value = []
        self.assertEqual(self.session.list_sessions(str(Path.cwd())), [])
        self.assertEqual(self.client.list_sessions.call_args.args[0].working_directory, str(Path.cwd()))
        self.session.list_sessions("")
        self.client.list_sessions.assert_awaited_with(None)

    def test_native_listing_projects_actual_sdk_metadata_through_runtime(self):
        from copilot.client import SessionMetadata

        timestamp = "2026-09-20T06:00:00+00:00"
        self.client.list_sessions.return_value = [
            SessionMetadata.from_dict({
                "sessionId": "local", "summary": "Existing work",
                "startTime": timestamp, "modifiedTime": timestamp, "isRemote": False,
                "context": {"cwd": str(Path.cwd()), "branch": "main"},
            }),
            SessionMetadata.from_dict({
                "sessionId": "without-context",
                "startTime": timestamp, "modifiedTime": timestamp, "isRemote": False,
            }),
            SessionMetadata.from_dict({
                "sessionId": "remote",
                "startTime": timestamp, "modifiedTime": timestamp, "isRemote": True,
                "context": {"cwd": "/remote/workspace"},
            }),
        ]
        runtime = BridgeRuntime(BridgeConfig(), PairingStore(), agent_manager=AcpAgentManager(copilot_transport="sdk"))
        self.addCleanup(runtime.shared.close)
        with patch.object(CopilotAgentSession, "start_without_session", return_value=self.session):
            responses = runtime.websocket_responses({
                "type": "session.list", "agentId": "copilot-cli", "workspacePath": str(Path.cwd()),
            })
        self.assertEqual(responses, [
            {"type": "session.list.result", "sessions": [
                {"sessionId": "local", "title": "Existing work", "cwd": str(Path.cwd()), "updatedAt": timestamp},
                {"sessionId": "without-context", "title": None, "cwd": None, "updatedAt": timestamp},
            ]},
            {"type": "bridge.done"},
        ])
        self.client.stop.assert_awaited_once()

    def test_permission_change_rejection_is_not_success(self):
        self.native.rpc.permissions.set_approve_all.return_value = SimpleNamespace(success=False)
        with self.assertRaisesRegex(AcpAgentError, "did not accept"):
            self.session.set_config_option("allow_all", "true")

    def test_config_refresh_reads_current_native_selection(self):
        self.native.rpc.model.get_current.return_value = SimpleNamespace(model_id="changed")
        updates = self.session.refresh_config_options()
        self.assertEqual(updates[0]["update"]["configOptions"][0]["currentValue"], "changed")

    def test_runtime_keeps_queue_and_history_guard_until_native_idle(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        manager._set_session("chat", self.session)
        runtime = BridgeRuntime(BridgeConfig(), PairingStore(), agent_manager=manager)
        emitted = []
        def send(identity):
            runtime.websocket_responses(
                {"type": "chat.prompt", "chatId": "chat", "operationId": identity, "agentId": "copilot-cli",
                 "workspacePath": str(Path.cwd()), "content": identity}, emit=emitted.append)
        send("first")
        self.assertTrue(self.native.sent.wait(3))
        self.native.emit("assistant.message", messageId="waiting", content="Waiting.", phase="final_answer")
        self.native.emit("assistant.turn_end", turnId="1")
        send("second")
        self.native.emit("tool.execution_complete", toolCallId="late", success=True, result={"content": "background"})
        wait_until(lambda: any(e.get("update", {}).get("toolCallId") == "late" for e in emitted))
        self.assertEqual(runtime._chat_status["chat"], "busy")
        self.assertEqual(self.native.send.await_count, 1)
        self.assertFalse(any(e["type"] == "operation.done" for e in emitted))
        history = runtime.websocket_responses(
            {"type": "session.loadRecent", "chatId": "chat", "agentId": "copilot-cli",
             "workspacePath": str(Path.cwd()), "sessionId": "native-session"})
        self.assertEqual(history[0]["errorCode"], "session_busy")
        self.native.emit("session.idle")
        wait_until(lambda: self.native.send.await_count == 2)
        self.assertEqual(runtime._chat_status["chat"], "busy")
        self.native.emit("session.idle")
        wait_until(lambda: runtime._chat_status["chat"] == "idle")
        completed = [e for e in emitted if e["type"] == "operation.done"]
        self.assertEqual([e["operationId"] for e in completed], ["first", "second"])
        late = next(e for e in emitted if e.get("update", {}).get("toolCallId") == "late")
        self.assertEqual(late["operationId"], "first")
        runtime.shared.close()

    def test_runtime_failure_stays_failed_not_successful_idle(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        manager._set_session("chat", self.session)
        runtime = BridgeRuntime(BridgeConfig(), PairingStore(), agent_manager=manager)
        emitted = []
        runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat", "operationId": "failure",
             "agentId": "copilot-cli", "workspacePath": str(Path.cwd()), "content": "work"},
            emit=emitted.append)
        self.assertTrue(self.native.sent.wait(3))
        self.native.emit("session.error", errorType="fatal", message="connection lost")
        wait_until(lambda: runtime._chat_status["chat"] == "failed")
        self.assertEqual(next(e["status"] for e in emitted if e["type"] == "operation.done"), "failed")
        runtime.shared.close()

    def test_runtime_persists_post_completion_updates_for_reconnect(self):
        manager = AcpAgentManager(copilot_transport="sdk")
        manager._set_session("chat", self.session)
        runtime = BridgeRuntime(BridgeConfig(), PairingStore(), agent_manager=manager)
        self.addCleanup(runtime.shared.close)
        emitted = []
        runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat", "operationId": "finished",
             "agentId": "copilot-cli", "workspacePath": str(Path.cwd()), "content": "work"},
            emit=emitted.append)
        self.assertTrue(self.native.sent.wait(3))
        self.native.emit("session.idle")
        operation = runtime._prompt_operations[("chat", "finished")]
        self.assertTrue(operation.completed.wait(3))
        response_count = len(operation.responses)
        self.native.emit("tool.execution_complete", toolCallId="after-done", success=True)
        wait_until(lambda: any(
            e.get("update", {}).get("toolCallId") == "after-done" for e in runtime._event_logs["chat"]))
        replay = runtime.websocket_responses({"type": "chat.attach", "chatId": "chat", "lastEventId": 0})
        late = next(e for e in replay if e.get("update", {}).get("toolCallId") == "after-done")
        self.assertEqual(late["operationId"], "finished")
        self.assertEqual(len(operation.responses), response_count)

    def test_late_approval_resolution_does_not_revive_finished_operation(self):
        runtime = BridgeRuntime(BridgeConfig(), PairingStore(), agent_manager=SimpleNamespace())
        runtime._chat_status["chat"] = "idle"
        emitted = []
        def emit(value):
            emitted.append(value)
            if value["type"] == "approval.requested":
                runtime._resolve_approval(value["approvalId"], "denied", "chat")
        runtime._request_permission("chat", {
            "params": {"toolCall": {"title": "Late permission", "kind": "execute"},
                       "options": [{"optionId": "deny", "kind": "reject_once"}]}}, emit)
        self.assertEqual(emitted[-1]["status"], "idle")
        runtime.shared.close()


class ProjectionTests(unittest.TestCase):
    def test_final_message_does_not_duplicate_deltas(self):
        projection = CopilotEventProjection()
        results = []
        for e in [event("assistant.message_delta", messageId="m", deltaContent="hel"),
                  event("assistant.message_delta", messageId="m", deltaContent="lo"),
                  event("assistant.message", messageId="m", content="hello!")]:
            results.extend(projection.updates(e))
        self.assertEqual("".join(r["update"]["content"]["text"] for r in results), "hello!")

    def test_child_reply_cannot_be_parent_completion_preview(self):
        result = CopilotEventProjection().updates(event(
            "assistant.message", messageId="child", content="Child done", parentToolCallId="launch"))
        self.assertEqual(result[0]["update"]["sessionUpdate"], "agent_thought_chunk")

    def test_top_level_agent_id_also_marks_child_reply(self):
        result = CopilotEventProjection().updates({
            **event("assistant.message", messageId="child", content="Child done"), "agentId": "worker"})
        self.assertEqual(result[0]["update"]["sessionUpdate"], "agent_thought_chunk")

    def test_history_ignores_deltas_and_replays_final_once(self):
        projection = CopilotEventProjection()
        self.assertEqual(projection.updates(
            event("assistant.message_delta", messageId="m", deltaContent="text"), history=True), [])
        result = projection.updates(event("assistant.message", messageId="m", content="text"), history=True)
        self.assertEqual(result[0]["update"]["content"]["text"], "text")

    def test_partial_output_preserves_earlier_chunks(self):
        projection = CopilotEventProjection()
        projection.updates(event("tool.execution_partial_result", toolCallId="t", partialOutput="one"))
        last = projection.updates(event("tool.execution_partial_result", toolCallId="t", partialOutput="two"))
        self.assertEqual(last[0]["update"]["rawOutput"], "onetwo")

    def test_background_lifecycle_is_separate_from_launch_tool(self):
        projection = CopilotEventProjection()
        launch = projection.updates(event("tool.execution_complete", toolCallId="t", success=True))
        worker = projection.updates(event("subagent.started", toolCallId="t", agentDisplayName="worker"))
        self.assertNotEqual(launch[0]["update"]["toolCallId"], worker[0]["update"]["toolCallId"])
        self.assertEqual(worker[0]["update"]["status"], "in_progress")

    def test_cancelled_worker_is_not_rendered_successful(self):
        result = CopilotEventProjection().updates(event(
            "subagent.completed", toolCallId="t", agentDisplayName="worker", cancelled=True))
        self.assertEqual(result[0]["update"]["status"], "failed")


class TransportSelectionTests(unittest.TestCase):
    def test_production_defaults_to_sdk_and_claude_stays_acp(self):
        from android_acp_bridge.acp_agent import AcpAgentSession
        manager = AcpAgentManager(copilot_transport=BridgeConfig().copilot_transport)
        self.assertIs(manager._session_type("copilot-cli"), CopilotAgentSession)
        self.assertIs(manager._session_type("claude-code"), AcpAgentSession)

    def test_explicit_compatibility_path(self):
        from android_acp_bridge.acp_agent import AcpAgentSession
        self.assertIs(AcpAgentManager(copilot_transport="acp")._session_type("copilot-cli"), AcpAgentSession)
