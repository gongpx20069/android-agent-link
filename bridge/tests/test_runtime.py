from __future__ import annotations

import unittest
import threading
import time
import io
from contextlib import redirect_stdout
from pathlib import Path

from android_acp_bridge.acp_agent import AcpPromptRequest, AcpSessionBinding, _resolve_workspace
from android_acp_bridge.config import BridgeConfig, WorkspaceConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, DeviceInfo, InvalidPairingTokenError, PromptOperation, parse_device_info


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
        self.assertEqual(emitted[3]["type"], "chat.session")
        self.assertFalse(emitted[3]["resumable"])
        first_update_index = next(index for index, event in enumerate(emitted) if event["type"] == "session/update")
        self.assertLess(3, first_update_index)
        self.assertTrue(any(event["type"] == "chat.session" and event["resumable"] for event in emitted))
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
        self.assertTrue(attach_responses[-1]["snapshot"])

        next_attach = runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": first_event_id}
        )
        self.assertTrue(all(not response.get("snapshot", False) for response in next_attach[1:-1]))
        self.assertTrue(next_attach[-1]["snapshot"])

    def test_chat_attach_resets_checkpoint_after_event_counter_restart(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        attached = runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": 100}
        )
        self.assertTrue(attached[0]["checkpointReset"])
        self.assertEqual(attached[-1]["eventId"], 1)

        runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "hello"}
        )
        reattached = runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": 100}
        )
        self.assertTrue(reattached[0]["checkpointReset"])
        self.assertTrue(any(response["type"] == "operation.done" for response in reattached[1:-1]))

    def test_chat_attach_resets_equal_checkpoint_from_previous_generation(self) -> None:
        previous_runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        previous_attach = previous_runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": 0}
        )

        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        runtime.websocket_responses(
            {"type": "chat.prompt", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "hello"}
        )
        attached = runtime.websocket_responses(
            {
                "type": "chat.attach",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "lastEventId": previous_attach[-1]["eventId"],
                "lastEventGeneration": previous_attach[0]["eventGeneration"],
            }
        )

        self.assertTrue(attached[0]["checkpointReset"])
        self.assertNotEqual(attached[0]["eventGeneration"], previous_attach[0]["eventGeneration"])
        self.assertTrue(any(response["type"] == "operation.accepted" for response in attached[1:-1]))

    def test_chat_attach_requires_resync_when_event_log_was_truncated(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )
        for index in range(510):
            runtime._append_event("chat_1", {"type": "agent_message_chunk", "content": str(index)})

        attached = runtime.websocket_responses(
            {"type": "chat.attach", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "lastEventId": 1}
        )

        self.assertEqual(attached[1]["type"], "chat.resyncRequired")
        replayed_event_ids = [response["eventId"] for response in attached[2:-1]]
        self.assertEqual(replayed_event_ids[0], 11)
        self.assertEqual(replayed_event_ids[-1], 510)

    def test_chat_attach_restores_persisted_session_binding(self) -> None:
        manager = FakeAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )

        responses = runtime.websocket_responses(
            {
                "type": "chat.attach",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "sessionId": "sess_saved",
                "sessionResumable": True,
                "lastEventId": 0,
            }
        )

        binding = next(response for response in responses if response["type"] == "chat.session")
        self.assertEqual(binding["sessionId"], "sess_saved")
        self.assertTrue(binding["resumable"])
        self.assertEqual(manager.restored_sessions, [("chat_1", "sess_saved", True)])

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

        self.assertEqual(responses[0], {"type": "chat.session", "chatId": "chat_1", "sessionId": "sess_1", "resumable": True})
        self.assertEqual(responses[1]["update"]["sessionUpdate"], "agent_message_chunk")
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

        self.assertEqual(responses[0]["type"], "chat.session")
        result = responses[1]
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

        self.assertEqual(responses[0]["type"], "chat.session")
        self.assertEqual(responses[1]["update"]["sessionUpdate"], "config_option_update")
        self.assertEqual(responses[1]["update"]["configOptions"][0]["currentValue"], "gpt-5.4")
        self.assertEqual(responses[-1]["type"], "bridge.done")

    def test_session_refresh_config_options_returns_latest_config(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        responses = runtime.websocket_responses({"type": "session.refreshConfigOptions", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo"})

        self.assertEqual(responses[0]["type"], "chat.session")
        self.assertEqual(responses[1]["update"]["sessionUpdate"], "config_option_update")
        self.assertEqual(responses[1]["update"]["configOptions"][0]["id"], "model")
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

    def test_waiting_prompts_are_batched_in_fifo_order_after_active_turn(self) -> None:
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
        runtime.websocket_responses(
            {"type": "chat.prompt", "operationId": "op_third", "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": "third"},
            emit=emitted.append,
        )
        queued = next(event for event in emitted if event.get("operationId") == "op_second" and event["type"] == "operation.accepted")

        self.assertEqual(queued["state"], "queued")
        self.assertEqual(manager.prompts, ["first"])
        manager.release.set()
        deadline = time.time() + 5
        while manager.prompts != ["first", "second\n\nthird"] and time.time() < deadline:
            time.sleep(0.01)
        done = [
            event for event in emitted
            if event["type"] == "operation.done" and event.get("operationId") in {"op_first", "op_second", "op_third"}
        ]
        while len(done) < 3 and time.time() < deadline:
            time.sleep(0.01)
            done = [
                event for event in emitted
                if event["type"] == "operation.done" and event.get("operationId") in {"op_first", "op_second", "op_third"}
            ]

        self.assertEqual(manager.prompts, ["first", "second\n\nthird"])
        self.assertEqual(len(done), 3)
        self.assertEqual([event["queueRemaining"] for event in done], [2, 1, 0])
        started = [
            event for event in emitted
            if event["type"] == "operation.started" and event.get("operationId") in {"op_second", "op_third"}
        ]
        self.assertEqual([event["content"] for event in started], ["second", "third"])
        self.assertEqual([event["batchSize"] for event in started], [2, 2])
        idle_events = [event for event in emitted if event["type"] == "chat.status" and event["status"] == "idle"]
        self.assertEqual(len(idle_events), 1)

    def test_prompts_added_while_batch_runs_wait_for_the_next_batch(self) -> None:
        manager = SequencedBlockingAgentManager()
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
        self.assertTrue(manager.started[0].wait(timeout=5))
        for operation_id, content in [("op_second", "second"), ("op_third", "third")]:
            runtime.websocket_responses(
                {"type": "chat.prompt", "operationId": operation_id, "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": content},
                emit=emitted.append,
            )

        manager.release[0].set()
        self.assertTrue(manager.started[1].wait(timeout=5))
        for operation_id, content in [("op_fourth", "fourth"), ("op_fifth", "fifth")]:
            runtime.websocket_responses(
                {"type": "chat.prompt", "operationId": operation_id, "chatId": "chat_1", "agentId": "copilot-cli", "workspacePath": "D:\\repo", "content": content},
                emit=emitted.append,
            )

        manager.release[1].set()
        self.assertTrue(manager.started[2].wait(timeout=5))
        manager.release[2].set()
        wait_for_event(emitted, "bridge.done")
        deadline = time.time() + 5
        while len(manager.prompts) < 3 and time.time() < deadline:
            time.sleep(0.01)

        self.assertEqual(manager.prompts, ["first", "second\n\nthird", "fourth\n\nfifth"])

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
        removed_again = runtime.websocket_responses(
            {"type": "chat.prompt.remove", "chatId": "chat_1", "operationId": "op_second"},
        )
        manager.release.set()
        wait_for_event(emitted, "bridge.done")

        self.assertEqual(removed[0]["status"], "cancelled")
        self.assertEqual(removed_again[0]["status"], "cancelled")
        self.assertEqual(manager.prompts, ["first"])

    def test_removal_tombstone_cancels_prompt_that_arrives_later(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )

        removed = runtime.websocket_responses(
            {"type": "chat.prompt.remove", "chatId": "chat_1", "operationId": "op_late"},
        )
        prompt = runtime.websocket_responses(
            {
                "type": "chat.prompt",
                "operationId": "op_late",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "content": "must not run",
            },
        )

        self.assertEqual(removed[0]["status"], "cancelled")
        self.assertEqual(prompt[0]["state"], "cancelled")
        self.assertTrue(prompt[0]["duplicate"])
        self.assertEqual(manager.prompts, [])

    def test_unmatched_removal_tombstone_survives_operation_history_eviction(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )

        for index in range(1001):
            runtime.websocket_responses(
                {
                    "type": "chat.prompt.remove",
                    "chatId": "chat_1",
                    "operationId": f"op_cancel_{index}",
                },
            )
        prompt = runtime.websocket_responses(
            {
                "type": "chat.prompt",
                "operationId": "op_cancel_0",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "content": "must still not run",
            },
        )

        self.assertEqual(prompt[0]["state"], "cancelled")
        self.assertEqual(manager.prompts, [])

    def test_matched_removal_tombstones_respect_operation_history_limit(self) -> None:
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=FakeAgentManager(),
        )

        for index in range(1100):
            operation_id = f"op_cancel_{index}"
            runtime.websocket_responses(
                {
                    "type": "chat.prompt.remove",
                    "chatId": "chat_1",
                    "operationId": operation_id,
                },
            )
            runtime.websocket_responses(
                {
                    "type": "chat.prompt",
                    "operationId": operation_id,
                    "chatId": "chat_1",
                    "agentId": "copilot-cli",
                    "workspacePath": "D:\\repo",
                    "content": "must not run",
                },
            )

        self.assertLessEqual(len(runtime._prompt_operations), 1000)
        self.assertLessEqual(len(runtime._cancelled_prompt_ids), 1000)

    def test_matched_removal_is_protected_when_history_has_only_active_operations(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )
        for index in range(1000):
            operation = PromptOperation(
                chat_id="chat_1",
                operation_id=f"op_active_{index}",
                agent_id="copilot-cli",
                workspace_path="D:\\repo",
                content="active",
                session_id=None,
                session_resumable=False,
                emit=None,
                responses=[],
                completed=threading.Event(),
                state="queued",
                waiters=[],
            )
            runtime._prompt_operations[("chat_1", operation.operation_id)] = operation

        runtime.websocket_responses(
            {
                "type": "chat.prompt.remove",
                "chatId": "chat_1",
                "operationId": "op_cancelled",
            },
        )
        prompt = runtime.websocket_responses(
            {
                "type": "chat.prompt",
                "operationId": "op_cancelled",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "content": "must not run",
            },
        )

        self.assertEqual(prompt[0]["state"], "cancelled")
        self.assertEqual(manager.prompts, [])
        self.assertIn(("chat_1", "op_cancelled"), runtime._cancelled_prompt_ids)
        self.assertNotIn(("chat_1", "op_cancelled"), runtime._prompt_operations)

    def test_cancellation_retry_does_not_allow_duplicate_prompt_execution(self) -> None:
        manager = BlockingAgentManager()
        runtime = BridgeRuntime(
            config=BridgeConfig(machine_name="devbox"),
            pairing_store=PairingStore(),
            require_local_pairing_confirmation=False,
            agent_manager=manager,
        )

        runtime.websocket_responses(
            {"type": "chat.prompt.remove", "chatId": "chat_1", "operationId": "op_cancelled"},
        )
        first_prompt = runtime.websocket_responses(
            {
                "type": "chat.prompt",
                "operationId": "op_cancelled",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "content": "must not run",
            },
        )
        retried_removal = runtime.websocket_responses(
            {"type": "chat.prompt.remove", "chatId": "chat_1", "operationId": "op_cancelled"},
        )
        duplicate_prompt = runtime.websocket_responses(
            {
                "type": "chat.prompt",
                "operationId": "op_cancelled",
                "chatId": "chat_1",
                "agentId": "copilot-cli",
                "workspacePath": "D:\\repo",
                "content": "must still not run",
            },
        )

        self.assertEqual(first_prompt[0]["state"], "cancelled")
        self.assertEqual(retried_removal[0]["status"], "cancelled")
        self.assertEqual(duplicate_prompt[0]["state"], "cancelled")
        self.assertEqual(manager.prompts, [])

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
        self.assertTrue(attached[-1]["snapshot"])


class FakeAgentManager:
    def __init__(self) -> None:
        self.restored_sessions: list[tuple[str, str, bool]] = []

    def prompt(self, request: AcpPromptRequest, permission_callback=None, update_callback=None, session_callback=None):
        if session_callback is not None:
            session_callback(AcpSessionBinding(request.session_id or "sess_created", request.session_resumable))
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
            updates = [
                {
                    "type": "session/update",
                    "update": {
                        "sessionUpdate": "tool_call_update",
                        "toolCallId": "tool_permission",
                        "status": option_id,
                    },
                }
            ]
            if session_callback is not None:
                session_callback(AcpSessionBinding(request.session_id or "sess_created", True))
            return updates
        updates = [
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
        if session_callback is not None:
            session_callback(AcpSessionBinding(request.session_id or "sess_created", True))
        return updates

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

    def restore_session(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id: str,
        session_resumable: bool,
        session_callback,
    ):
        self.restored_sessions.append((chat_id, session_id, session_resumable))
        session_callback(AcpSessionBinding(session_id, session_resumable))

    def refresh_config_options(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        session_id=None,
        session_resumable=False,
        session_callback=None,
    ):
        if session_callback is not None:
            session_callback(AcpSessionBinding(session_id or "sess_created", session_resumable))
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

    def set_config_option(
        self,
        chat_id: str,
        agent_id: str,
        workspace_path: str,
        config_id: str,
        value: str,
        session_id=None,
        session_resumable=False,
        session_callback=None,
    ):
        if session_callback is not None:
            session_callback(AcpSessionBinding(session_id or "sess_created", session_resumable))
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
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self.prompts: list[str] = []

    def prompt(self, request: AcpPromptRequest, permission_callback=None, update_callback=None, session_callback=None):
        if session_callback is not None:
            session_callback(AcpSessionBinding(request.session_id or "sess_created", request.session_resumable))
        self.prompts.append(request.prompt)
        self.started.set()
        self.release.wait(timeout=5)
        updates = [
            {
                "type": "session/update",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "done"},
                },
            }
        ]
        if session_callback is not None:
            session_callback(AcpSessionBinding(request.session_id or "sess_created", True))
        return updates


class SequencedBlockingAgentManager(FakeAgentManager):
    def __init__(self) -> None:
        super().__init__()
        self.started = [threading.Event() for _ in range(3)]
        self.release = [threading.Event() for _ in range(3)]
        self.prompts: list[str] = []

    def prompt(self, request: AcpPromptRequest, permission_callback=None, update_callback=None, session_callback=None):
        index = len(self.prompts)
        self.prompts.append(request.prompt)
        self.started[index].set()
        self.release[index].wait(timeout=5)
        if session_callback is not None:
            session_callback(AcpSessionBinding(request.session_id or "sess_created", True))
        return []


if __name__ == "__main__":
    unittest.main()
