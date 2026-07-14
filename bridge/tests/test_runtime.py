from __future__ import annotations

import unittest
import threading
import time
import io
from contextlib import redirect_stdout
from pathlib import Path

from android_acp_bridge.acp_agent import AcpPromptRequest, _resolve_workspace
from android_acp_bridge.config import BridgeConfig, WorkspaceConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, DeviceInfo, InvalidPairingTokenError, parse_device_info


def wait_for_event(events: list[dict], event_type: str, timeout: float = 5) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        event = next((candidate for candidate in events if candidate.get("type") == event_type), None)
        if event is not None:
            return event
        time.sleep(0.01)
    raise AssertionError(f"Timed out waiting for {event_type}")


class RuntimeTests(unittest.TestCase):
    def test_empty_workspace_resolves_to_home(self) -> None:
        self.assertEqual(_resolve_workspace(""), Path.home().resolve())

    def test_default_runtime_has_no_startup_workspaces(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
        )

        self.assertEqual(runtime.workspaces_response(), {"workspaces": []})

    def test_workspaces_response_uses_wire_names(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(
                machine_name="devbox",
                workspaces=(WorkspaceConfig(id="repo", display_name="Repo", absolute_path="D:\\repos\\repo"),),
            ),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
        )

        self.assertEqual(
            runtime.workspaces_response(),
            {
                "workspaces": [
                    {
                        "id": "repo",
                        "displayName": "Repo",
                        "absolutePath": "D:\\repos\\repo",
                    }
                ]
            },
        )

    def test_redeem_pairing_issues_device_token(self) -> None:
        store = PairingStore()
        token = store.create()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=store,
            require_local_pairing_confirmation=False,
        )

        response = runtime.redeem_pairing(
            token.pairing_id,
            token.pairing_token,
            DeviceInfo(name="Pixel", platform="android", app_version="0.1.0"),
        )

        self.assertEqual(response["machineId"], "devbox")
        self.assertTrue(response["deviceToken"].startswith("dev_"))
        self.assertTrue(runtime.is_device_token_valid(response["deviceToken"]))

    def test_redeem_pairing_rejects_reuse(self) -> None:
        store = PairingStore()
        token = store.create()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=store,
            require_local_pairing_confirmation=False,
        )

        runtime.redeem_pairing(
            token.pairing_id,
            token.pairing_token,
            DeviceInfo(name="Pixel", platform="android", app_version="0.1.0"),
        )

        with self.assertRaises(InvalidPairingTokenError):
            runtime.redeem_pairing(
                token.pairing_id,
                token.pairing_token,
                DeviceInfo(name="Pixel", platform="android", app_version="0.1.0"),
            )

    def test_parse_device_info_requires_wire_app_version(self) -> None:
        self.assertEqual(
            parse_device_info({"name": "Pixel", "platform": "android", "appVersion": "0.1.0"}),
            DeviceInfo(name="Pixel", platform="android", app_version="0.1.0"),
        )
        self.assertIsNone(parse_device_info({"name": "Pixel", "platform": "android"}))

    def test_chat_prompt_websocket_responses_include_tool_call_updates(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "hello"})

        self.assertEqual(responses[0]["type"], "operation.accepted")
        self.assertEqual(responses[1]["type"], "chat.status")
        session_updates = [response for response in responses if response["type"] == "session/update"]
        self.assertEqual(session_updates[0]["update"]["sessionUpdate"], "tool_call")
        self.assertEqual(session_updates[1]["update"]["sessionUpdate"], "tool_call_update")
        self.assertEqual(session_updates[2]["update"]["sessionUpdate"], "agent_message_chunk")
        self.assertEqual(responses[-2]["type"], "chat.status")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_streaming_prompt_emits_busy_before_updates_without_duplicates(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        emitted: list[dict] = []

        responses = runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "hello"},
            emit=emitted.append,
        )
        wait_for_event(emitted, "bridge.done")

        self.assertEqual(responses, [])
        self.assertEqual(emitted[0]["type"], "operation.accepted")
        self.assertEqual(emitted[1]["type"], "chat.status")
        self.assertEqual(emitted[1]["status"], "busy")
        self.assertEqual(emitted[2]["type"], "operation.started")
        self.assertEqual(emitted[3]["type"], "session/update")
        self.assertEqual(emitted[-2]["status"], "idle")
        self.assertEqual(emitted[-1]["type"], "bridge.done")
        event_ids = [event["eventId"] for event in emitted if "eventId" in event]
        self.assertEqual(len(event_ids), len(set(event_ids)))

    def test_chat_attach_replays_events_after_last_event_id(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        prompt_responses = runtime.websocket_responses({"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "hello"})
        first_event_id = prompt_responses[0]["eventId"]

        attach_responses = runtime.websocket_responses({"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": first_event_id})

        self.assertEqual(attach_responses[0]["type"], "chat.attached")
        replayed = attach_responses[1:-1]
        self.assertTrue(all(response.get("eventId", 0) > first_event_id for response in replayed))
        self.assertEqual(attach_responses[-1]["type"], "chat.status")

    def test_chat_prompt_logs_client_and_agent_updates(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        output = io.StringIO()

        with redirect_stdout(output):
            runtime.websocket_responses(
                {
                    "type": "chat.prompt",
                    "chatId": "chat_1",
                    "agentId": "copilot-cli",
                    "workspacePath": "D:\\repo",
                    "content": "hello " + "x" * 120,
                }
            )

        logs = output.getvalue()
        self.assertIn("[bridge] <- client chat=chat_1 agent=copilot-cli", logs)
        self.assertIn('prompt="hello ', logs)
        self.assertIn("…", logs)
        self.assertIn("[bridge] -> android chat=chat_1 tool_call", logs)
        self.assertIn("[bridge] -> android chat=chat_1 tool_call_update", logs)
        self.assertIn("[bridge] -> android chat=chat_1 agent_message_chunk", logs)

    def test_agent_message_chunks_are_logged_as_one_line(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
        )
        output = io.StringIO()
        responses = [
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "是"}},
            },
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "**"}},
            },
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "`gpt-5.5`"}},
            },
            {"type": "bridge.done", "chatId": "chat_1"},
        ]

        with redirect_stdout(output):
            runtime._log_responses(responses)

        lines = [line for line in output.getvalue().splitlines() if "agent_message_chunk" in line]
        self.assertEqual(len(lines), 1)
        self.assertIn("是**`gpt-5.5`", lines[0])

    def test_agent_message_chunk_log_is_suppressed_after_fifty_chars(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
        )
        output = io.StringIO()
        responses = [
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "x" * 20}},
            },
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "y" * 35}},
            },
            {
                "type": "session/update",
                "chatId": "chat_1",
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"text": "z" * 20}},
            },
            {"type": "bridge.done", "chatId": "chat_1"},
        ]

        with redirect_stdout(output):
            runtime._log_responses(responses)

        lines = [line for line in output.getvalue().splitlines() if "agent_message_chunk" in line]
        self.assertEqual(len(lines), 1)
        self.assertIn("…", lines[0])

    def test_approval_decision_websocket_response_is_tool_call_update(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
        )

        responses = runtime.websocket_responses({"type": "approval.decide", "approvalId": "approval_1", "decision": "approved"})

        self.assertEqual(responses[0]["update"]["sessionUpdate"], "tool_call_update")
        self.assertEqual(responses[0]["update"]["toolCallId"], "approval_1")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_list_websocket_response_returns_sessions(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.list", "agentId": "copilot-cli", "workspacePath": "D:\\repo"})

        self.assertEqual(responses[0]["type"], "session.list.result")
        self.assertEqual(responses[0]["sessions"][0]["sessionId"], "sess_1")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_list_allows_empty_workspace_filter(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.list", "agentId": "copilot-cli", "workspacePath": ""})

        self.assertEqual(responses[0]["sessions"][0]["cwd"], "")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_load_websocket_response_streams_updates(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.load", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "sessionId": "sess_1"})

        self.assertEqual(responses[0]["update"]["sessionUpdate"], "agent_message_chunk")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_load_recent_returns_latest_visible_messages(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses(
            {
                "type": "session.loadRecent",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "sessionId": "sess_1",
                "limit": 2,
            }
        )

        result = responses[0]
        self.assertEqual(result["type"], "session.loadRecent.result")
        self.assertEqual(result["sessionId"], "sess_1")
        self.assertEqual(result["scannedEvents"], 4)
        self.assertEqual(result["messages"], [{"role": "user", "text": "new question"}, {"role": "agent", "text": "new answer"}])
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_set_config_option_returns_config_update(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.setConfigOption", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "configId": "model", "value": "gpt-5.4"})

        self.assertEqual(responses[0]["update"]["sessionUpdate"], "config_option_update")
        self.assertEqual(responses[0]["update"]["configOptions"][0]["currentValue"], "gpt-5.4")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_refresh_config_options_returns_latest_config(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.refreshConfigOptions", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo"})

        self.assertEqual(responses[0]["update"]["sessionUpdate"], "config_option_update")
        self.assertEqual(responses[0]["update"]["configOptions"][0]["id"], "model")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_permission_request_emits_approval_and_waits_for_decision(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        emitted: list[dict] = []
        responses = runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "needs approval"},
            emit=emitted.append,
        )
        approval = wait_for_event(emitted, "approval.requested")
        runtime.websocket_responses({"type": "approval.decide", "approvalId": approval["approvalId"], "decision": "approved"})
        wait_for_event(emitted, "bridge.done")

        self.assertEqual(responses, [])
        session_updates = [response for response in emitted if response["type"] == "session/update"]
        self.assertEqual(session_updates[0]["update"]["status"], "allow-once")

    def test_concurrent_prompt_for_same_chat_is_queued_fifo(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )
        emitted: list[dict] = []
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=emitted.append,
        )
        self.assertTrue(manager.started.wait(timeout=5))

        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_second", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "second"},
            emit=emitted.append,
        )
        queued = next(event for event in emitted if event.get("operationId") == "op_second" and event["type"] == "operation.accepted")

        self.assertEqual(queued["state"], "queued")
        self.assertEqual(manager.prompts, ["first"])
        manager.release.set()
        deadline = time.time() + 5
        while manager.prompts != ["first", "second"] and time.time() < deadline:
            time.sleep(0.01)
        done = [
            event for event in emitted
            if event["type"] == "operation.done" and event.get("operationId") in {"op_first", "op_second"}
        ]
        while len(done) < 2 and time.time() < deadline:
            time.sleep(0.01)
            done = [
                event for event in emitted
                if event["type"] == "operation.done" and event.get("operationId") in {"op_first", "op_second"}
            ]

        self.assertEqual(manager.prompts, ["first", "second"])
        self.assertEqual(len(done), 2)
        self.assertEqual(done[0]["queueRemaining"], 1)
        self.assertEqual(done[1]["queueRemaining"], 0)
        idle_events = [event for event in emitted if event["type"] == "chat.status" and event["status"] == "idle"]
        self.assertEqual(len(idle_events), 1)

    def test_queued_prompt_can_be_removed_before_it_starts(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )
        emitted: list[dict] = []
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=emitted.append,
        )
        self.assertTrue(manager.started.wait(timeout=5))
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_second", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "second"},
            emit=emitted.append,
        )

        removed = runtime.websocket_responses(
            {"type": "chat.prompt.remove", "chatId": "chat_1", "operationId": "op_second"},
        )
        manager.release.set()
        wait_for_event(emitted, "bridge.done")

        self.assertEqual(removed[0]["status"], "cancelled")
        self.assertEqual(manager.prompts, ["first"])

    def test_queued_one_shot_prompts_keep_their_own_emitters(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )
        first_events: list[dict] = []
        second_events: list[dict] = []

        def emit_first(event: dict) -> None:
            first_events.append(event)

        def emit_second(event: dict) -> None:
            second_events.append(event)

        setattr(emit_first, "_connection_id", "first")
        setattr(emit_second, "_connection_id", "second")
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=emit_first,
        )
        self.assertTrue(manager.started.wait(timeout=5))
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_second", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "second"},
            emit=emit_second,
        )

        manager.release.set()
        wait_for_event(first_events, "bridge.done")
        wait_for_event(second_events, "bridge.done")

        self.assertTrue(any(event.get("operationId") == "op_first" and event["type"] == "operation.done" for event in first_events))
        self.assertFalse(any(event.get("operationId") == "op_first" and event["type"] == "operation.done" for event in second_events))
        self.assertTrue(any(event.get("operationId") == "op_second" and event["type"] == "operation.done" for event in second_events))

    def test_duplicate_in_flight_prompt_waits_for_terminal_event(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )
        original_events: list[dict] = []
        duplicate_events: list[dict] = []

        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=original_events.append,
        )
        self.assertTrue(manager.started.wait(timeout=5))
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=duplicate_events.append,
        )

        manager.release.set()
        wait_for_event(duplicate_events, "bridge.done")

        self.assertTrue(any(event["type"] == "operation.done" for event in duplicate_events))
        self.assertEqual(manager.prompts, ["first"])

    def test_attached_prompt_events_are_not_emitted_twice(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        emitted: list[dict] = []

        def emit(event: dict) -> None:
            emitted.append(event)

        setattr(emit, "_connection_id", "persistent")
        runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "lastEventId": 0},
            emit=emit,
        )
        emitted.clear()
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_first", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"},
            emit=emit,
        )
        wait_for_event(emitted, "bridge.done")

        event_ids = [event["eventId"] for event in emitted if "eventId" in event]
        self.assertEqual(len(event_ids), len(set(event_ids)))

    def test_chat_attach_reports_busy_while_prompt_is_running(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )

        thread = threading.Thread(
            target=lambda: runtime.websocket_responses(
                {"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "first"}
            )
        )
        thread.start()
        self.assertTrue(manager.started.wait(timeout=5))

        attached = runtime.websocket_responses({"type": "chat.attach", "chatId": "chat_1", "lastEventId": 0})

        manager.release.set()
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive())
        self.assertEqual(attached[-1]["type"], "chat.status")
        self.assertEqual(attached[-1]["status"], "busy")


class FakeAgentManager:
    def prompt(self, request: AcpPromptRequest, permission_callback=None, update_callback=None):
        if permission_callback is not None and request.prompt == "needs approval":
            option_id = permission_callback(
                {
                    "params": {
                        "toolCall": {
                            "toolCallId": "tool_permission",
                            "title": "Run command",
                            "kind": "execute",
                        },
                        "options": [
                            {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
                            {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"},
                        ],
                    }
                }
            )
            return [
                {
                    "type": "session/update",
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "tool_permission",
                        "status": option_id,
                    },
                }
            ]
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "tool_1",
                    "title": "Fake tool",
                    "kind": "other",
                    "status": "started",
                },
            },
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "tool_1",
                    "status": "completed",
                    "content": {"result": request.prompt},
                },
            },
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "hello back"},
                },
            },
        ]

    def list_sessions(self, agent_id: str, workspace_path: str):
        return [{"sessionId": "sess_1", "cwd": workspace_path, "title": "Previous work", "updatedAt": "2026-07-07T00:00:00Z"}]

    def load_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str):
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": f"Loaded {session_id}"},
                },
            }
        ]

    def load_recent_session(self, chat_id: str, agent_id: str, workspace_path: str, session_id: str, limit: int):
        return {
            "updates": [
                {"type": "session/update", "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": "old question"}}},
                {"type": "session/update", "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "old answer"}}},
                {"type": "session/update", "update": {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": "new question"}}},
                {"type": "session/update", "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "new answer"}}},
            ],
            "scannedEvents": 4,
            "truncated": False,
        }

    def refresh_config_options(self, chat_id: str, agent_id: str, workspace_path: str):
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "config_option_update",
                    "configOptions": [
                        {
                            "id": "model",
                            "name": "Model",
                            "category": "model",
                            "type": "select",
                            "currentValue": "gpt-5.4",
                            "options": [{"value": "gpt-5.4", "name": "gpt-5.4"}],
                        }
                    ],
                },
            }
        ]

    def set_config_option(self, chat_id: str, agent_id: str, workspace_path: str, config_id: str, value: str):
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "config_option_update",
                    "configOptions": [
                        {
                            "id": config_id,
                            "name": "Model",
                            "category": "model",
                            "type": "select",
                            "currentValue": value,
                            "options": [{"value": value, "name": value}],
                        }
                    ],
                },
            }
        ]


class BlockingAgentManager(FakeAgentManager):
    def __init__(self) -> None:
        self.started = threading.Event()
        self.release = threading.Event()
        self.prompts: list[str] = []

    def prompt(self, request: AcpPromptRequest, permission_callback=None, update_callback=None):
        self.prompts.append(request.prompt)
        self.started.set()
        self.release.wait(timeout=5)
        return [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "done"},
                },
            }
        ]


if __name__ == "__main__":
    unittest.main()
