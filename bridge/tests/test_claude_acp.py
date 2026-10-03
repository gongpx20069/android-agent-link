from __future__ import annotations

import json
import queue
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentManager, AcpAgentSession, _agent_command
from android_acp_bridge.agents import discover_agents
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.elicitation import validate_form, validate_answers
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime


def wait_until(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("Timed out waiting for test event")
        time.sleep(0.005)


class ClaudeTransportTests(unittest.TestCase):
    def setUp(self):
        self.incoming = queue.Queue()
        self.process = MagicMock()
        self.sent = queue.Queue()
        self.process.stdin.write.side_effect = lambda line: self.sent.put(json.loads(line))
        self.session = AcpAgentSession(self.process, self.incoming, "s")
        self.session._capabilities = {"loadSession": True, "sessionCapabilities": {"list": {}, "resume": {}}}
        self.session._workspace = str(Path.cwd())
        self.addCleanup(self.session.stop)

    def update(self, kind, **fields):
        self.incoming.put({"method": "session/update", "params": {
            "sessionId": "s", "update": {"sessionUpdate": kind, **fields},
        }})

    def run_prompt(self):
        self.events, self.result, self.errors = [], [], []
        def run():
            try:
                self.result.extend(self.session.prompt("hello", self.events.append))
            except AcpAgentError as exc:
                self.errors.append(exc)
        worker = threading.Thread(target=run)
        worker.start()
        request = self.sent.get(timeout=2)
        self.assertEqual(request["method"], "session/prompt")
        return worker, request["id"]

    def test_prompt_waits_for_multiple_background_tasks_and_delivers_late_events(self):
        worker, identity = self.run_prompt()
        for task in ("one", "two"):
            self.update("async_task_spawned", asyncTaskId=task, name=task)
        self.incoming.put({"id": identity, "result": {"stopReason": "end_turn"}})
        wait_until(lambda: len(self.session._background_tasks) == 2)
        self.assertTrue(worker.is_alive())
        self.update("async_task_state_update", asyncTaskId="one", state="completed")
        self.update("agent_message_chunk", content={"type": "text", "text": "late"})
        wait_until(lambda: len(self.session._background_tasks) == 1)
        self.assertTrue(worker.is_alive())
        self.update("async_task_state_update", asyncTaskId="two", state="completed")
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertFalse(self.errors)
        self.assertTrue(any(event["update"].get("content", {}).get("text") == "late" for event in self.events))
        self.assertEqual(self.events[-1]["update"]["tasks"], [])
        self.update("agent_message_chunk", content={"type": "text", "text": "after"})
        wait_until(lambda: any(event["update"].get("content", {}).get("text") == "after" for event in self.events))

    def test_permission_wait_does_not_block_reader_or_cancel(self):
        allowed = threading.Event()
        self.addCleanup(allowed.set)
        self.session.permission_callback = lambda _: (allowed.wait(2), "yes")[1]
        worker, identity = self.run_prompt()
        self.incoming.put({"id": "permission", "method": "session/request_permission", "params": {
            "options": [{"optionId": "yes", "kind": "allow_once"}],
        }})
        self.update("agent_message_chunk", content={"type": "text", "text": "still streaming"})
        wait_until(lambda: bool(self.events))
        self.session.cancel_prompt()
        self.assertEqual(self.sent.get(timeout=2)["method"], "session/cancel")
        allowed.set()
        answer = self.sent.get(timeout=2)
        self.assertEqual(answer["result"]["outcome"], {"outcome": "cancelled"})
        self.incoming.put({"id": identity, "result": {"stopReason": "cancelled"}})
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(self.result[-1]["update"]["sessionUpdate"], "agentlink_prompt_cancelled")

    def test_eof_during_background_work_is_failure_not_completion(self):
        worker, identity = self.run_prompt()
        self.update("async_task_spawned", asyncTaskId="task", name="Task")
        self.incoming.put({"id": identity, "result": {"stopReason": "end_turn"}})
        self.incoming.put({"_transportError": "test transport lost"})
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertTrue(self.errors)

    def test_cancel_stops_reported_background_tasks_after_prompt_response(self):
        worker, identity = self.run_prompt()
        self.update("async_task_spawned", asyncTaskId="task", name="Task")
        wait_until(lambda: bool(self.session._background_tasks))
        self.session.cancel_prompt()
        self.assertEqual(self.sent.get(timeout=2)["method"], "session/cancel")
        self.incoming.put({"id": identity, "result": {"stopReason": "end_turn"}})
        stop = self.sent.get(timeout=2)
        self.assertEqual(stop["method"], "_session/async_task/stop")
        self.assertEqual(stop["params"]["asyncTaskId"], "task")
        self.update("async_task_state_update", asyncTaskId="task", state="stopped")
        self.incoming.put({"id": stop["id"], "result": {"stopped": True}})
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(self.result[-1]["update"]["sessionUpdate"], "agentlink_prompt_cancelled")
        self.assertTrue(any(event["update"].get("tasks") == [] for event in self.events))

    def test_structured_provider_failure_is_not_successful_end_turn(self):
        worker, identity = self.run_prompt()
        self.incoming.put({"id": identity, "result": {
            "stopReason": "end_turn",
            "_meta": {"jetbrains": {"air": {"sessionFailure": {"severity": "error", "title": "Sign in first"}}}},
        }})
        worker.join(2)
        self.assertEqual(str(self.errors[0]), "Sign in first")

    def test_background_completion_waits_until_update_delivery_finishes(self):
        received, release = threading.Event(), threading.Event()
        self.addCleanup(release.set)
        self.events, self.errors = [], []
        def sink(event):
            if event["update"].get("tasks") == []:
                received.set()
                release.wait(2)
            self.events.append(event)
        completed = threading.Event()
        worker = threading.Thread(target=lambda: (self.session.prompt("hello", sink), completed.set()))
        worker.start()
        request = self.sent.get(timeout=2)
        self.update("async_task_spawned", asyncTaskId="task", name="Task")
        self.incoming.put({"id": request["id"], "result": {"stopReason": "end_turn"}})
        self.update("async_task_state_update", asyncTaskId="task", state="completed")
        self.assertTrue(received.wait(2))
        self.assertFalse(completed.is_set())
        release.set()
        worker.join(2)
        self.assertTrue(completed.is_set())

    def test_paginated_sessions_and_repeated_cursor(self):
        self.session._request = MagicMock(side_effect=[
            ({"sessions": [{"sessionId": "one"}], "nextCursor": "next"}, []),
            ({"sessions": [{"sessionId": "two"}]}, []),
        ])
        self.assertEqual([item["sessionId"] for item in self.session.list_sessions("")], ["one", "two"])
        self.assertEqual(self.session._request.call_args.args[1]["cursor"], "next")
        self.session._request = MagicMock(return_value=({"sessions": [], "nextCursor": "loop"}, []))
        with self.assertRaisesRegex(AcpAgentError, "pagination"):
            self.session.list_sessions("")

    def test_missing_capability_fails_without_issuing_load_or_list(self):
        self.session._capabilities = {}
        self.session._request = MagicMock()
        with self.assertRaisesRegex(AcpAgentError, "does not support"):
            self.session.list_sessions("")
        with self.assertRaisesRegex(AcpAgentError, "not provided"):
            self.session.history()
        self.session._request.assert_not_called()

    def test_history_compatibility_drain_starts_when_response_arrives(self):
        result = []
        worker = threading.Thread(target=lambda: result.append(self.session._request_and_drain(
            "session/load", {"sessionId": "s"}, 2, 0.05, 1, 100,
        )))
        worker.start()
        request = self.sent.get(timeout=2)
        time.sleep(0.08)
        self.incoming.put({"id": request["id"], "result": {}})
        time.sleep(0.01)
        self.update("agent_message_chunk", content={"type": "text", "text": "replay tail"})
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result[0][1][0]["update"]["content"]["text"], "replay tail")

    def test_continue_uses_resume_without_replaying_history(self):
        self.session._request_and_drain = MagicMock(return_value=({}, [], 0, False))
        with patch.object(AcpAgentSession, "start_without_session", return_value=self.session):
            AcpAgentSession.load_for_continue("claude-code", str(Path.cwd()), "s", True)
        self.assertEqual(self.session._request_and_drain.call_args.args[0], "session/resume")
        self.assertFalse(self.session._request_and_drain.call_args.kwargs["collect_updates"])

    def test_live_history_keeps_process_and_other_chat_cannot_claim_it(self):
        manager = AcpAgentManager()
        manager._set_session("chat", self.session)
        self.session.history = MagicMock(return_value=([], 0))
        with patch.object(AcpAgentSession, "load_recent") as spawn:
            manager.load_recent_session("chat", "claude-code", str(Path.cwd()), "s", 50)
            spawn.assert_not_called()
        with self.assertRaisesRegex(AcpAgentError, "another chat"):
            manager.load_recent_session("other", "claude-code", str(Path.cwd()), "s", 50)

    def test_unknown_reverse_rpc_is_rejected_not_left_hanging(self):
        worker, identity = self.run_prompt()
        self.incoming.put({"id": "read", "method": "fs/read_text_file", "params": {}})
        self.assertEqual(self.sent.get(timeout=2)["error"]["code"], -32601)
        self.incoming.put({"id": identity, "result": {"stopReason": "end_turn"}})
        worker.join(2)
        self.assertFalse(worker.is_alive())

    def test_child_output_cannot_become_main_answer(self):
        result = self.session._receive_update({"sessionId": "s", "update": {
            "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "child"},
            "_meta": {"claudeCode": {"parentToolUseId": "tool"}},
        }})
        self.assertEqual(result[0]["update"]["sessionUpdate"], "agent_thought_chunk")
        with self.assertRaisesRegex(AcpAgentError, "unowned"):
            self.session._receive_update({"sessionId": "other", "update": {"sessionUpdate": "agent_message_chunk"}})

    def test_launcher_requires_adapter_not_claude_cli(self):
        with patch("shutil.which", side_effect=lambda command: "claude.exe" if command == "claude" else None):
            self.assertEqual(discover_agents()[0].status, "missing_adapter")
            with self.assertRaisesRegex(AcpAgentError, "Install"):
                _agent_command("claude-code", Path.cwd())


FORM = {
    "type": "object",
    "properties": {
        "database": {"type": "string", "oneOf": [{"const": "sqlite", "title": "SQLite"}, {"const": "postgres"}]},
        "features": {"type": "array", "items": {"anyOf": [{"const": "auth"}, {"const": "cache"}]}},
        "note": {"type": "string"},
    },
    "required": ["database"],
}


class ClaudeInteractionTests(unittest.TestCase):
    def test_terminal_requires_review_and_sends_exact_choice_or_typed_answers(self):
        from android_acp_bridge.terminal import TerminalClient

        output = []
        client = TerminalClient(output.append)
        self.addCleanup(client.close)
        client.runtime = MagicMock()
        permission = {"approvalId": "p", "chatId": "chat", "summary": "Permission", "details": {},
                      "options": [{"optionId": "exact", "kind": "allow_once", "name": "Once"}]}
        question = {"approvalId": "q", "chatId": "chat", "summary": "Question",
                    "interaction": "question", "details": {"requestedSchema": FORM}}
        client.runtime.local_approvals.return_value = [permission, question]
        client._dispatch = MagicMock()
        client.command("/choose p exact")
        client._dispatch.assert_not_called()
        client.command("/approvals")
        client.command("/choose p exact")
        self.assertEqual(client._dispatch.call_args.args[0]["optionId"], "exact")
        client.command('/answer q {"database":"sqlite"}')
        self.assertEqual(client._dispatch.call_args.args[0]["answers"], {"database": "sqlite"})

    def test_form_validates_choices_types_required_and_unsupported_constraints(self):
        validate_form({"requestedSchema": FORM})
        answers = {"database": "sqlite", "features": ["auth"], "note": "custom"}
        self.assertEqual(validate_answers(FORM, answers), answers)
        for invalid in ({}, {"database": "other"}, {"database": True}, {"database": "sqlite", "unknown": 1},
                        {"database": "sqlite", "features": ["auth", "auth"]}):
            with self.assertRaises(ValueError):
                validate_answers(FORM, invalid)
        with self.assertRaises(ValueError):
            validate_form({"mode": "url", "requestedSchema": FORM})
        with self.assertRaises(ValueError):
            validate_form({"requestedSchema": {"type": "object", "properties": {"x": {"type": "string", "pattern": "x"}}}})

    def runtime(self):
        return BridgeRuntime(config=BridgeConfig(machine_name="test"), pairing_store=PairingStore(),
                             require_local_pairing_confirmation=False)

    def test_exact_permission_option_not_first_allow_and_broadcast_once(self):
        runtime, events, result = self.runtime(), [], []
        message = {"params": {"toolCall": {"title": "Edit"}, "options": [
            {"optionId": "always", "name": "Always allow", "kind": "allow_always"},
            {"optionId": "once", "name": "Allow once", "kind": "allow_once"},
        ]}}
        worker = threading.Thread(target=lambda: result.append(runtime._request_permission("chat", message, events.append)))
        worker.start()
        wait_until(lambda: any(event["type"] == "approval.requested" for event in events))
        identity = next(event["approvalId"] for event in events if event["type"] == "approval.requested")
        reply = runtime._approval_decision_updates({"approvalId": identity, "decision": "approved", "optionId": "once"})
        worker.join(2)
        self.assertEqual(result, ["once"])
        self.assertEqual(reply[1]["optionId"], "once")
        self.assertEqual(sum(event["type"] == "approval.resolved" for event in events), 1)

    def test_question_is_shared_and_cannot_be_approved_without_answers(self):
        runtime, events, result = self.runtime(), [], []
        message = {"method": "elicitation/create", "params": {
            "sessionId": "s", "mode": "form", "message": "Which database?", "requestedSchema": FORM,
        }}
        worker = threading.Thread(target=lambda: result.append(runtime._request_permission("chat", message, events.append)))
        worker.start()
        wait_until(lambda: bool(runtime.local_approvals()))
        request = runtime.local_approvals()[0]
        identity = request["approvalId"]
        self.assertEqual(request["interaction"], "question")
        self.assertIsNone(runtime._resolve_approval(identity, "approved"))
        self.assertIsNone(runtime._resolve_approval(identity, "approved", answers={"database": "invented"}))
        self.assertIsNotNone(runtime._resolve_approval(identity, "approved", answers={"database": "sqlite"}))
        worker.join(2)
        self.assertEqual(result, [{"action": "accept", "content": {"database": "sqlite"}}])
        self.assertEqual(runtime.local_approvals(), [])
        self.assertFalse(any("answers" in event for event in events))
        self.assertIsNone(runtime._resolve_approval(identity, "approved", answers={"database": "postgres"}))

    def test_cancellation_expires_question_without_waiting_five_minutes(self):
        runtime, events, result = self.runtime(), [], []
        cancellation = threading.Event()
        message = {"method": "elicitation/create", "_cancelEvent": cancellation,
                   "params": {"requestedSchema": FORM, "message": "Question"}}
        worker = threading.Thread(target=lambda: result.append(runtime._request_permission("chat", message, events.append)))
        worker.start()
        wait_until(lambda: bool(runtime.local_approvals()))
        cancellation.set()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(result, [{"action": "cancel"}])
