from __future__ import annotations

import unittest

from android_acp_bridge.acp_agent import AcpPromptRequest
from android_acp_bridge.config import BridgeConfig, WorkspaceConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, DeviceInfo, InvalidPairingTokenError, parse_device_info


class RuntimeTests(unittest.TestCase):
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

        self.assertEqual(responses[0]["type"], "session/update")
        self.assertEqual(responses[0]["update"]["sessionUpdate"], "tool_call")
        self.assertEqual(responses[1]["update"]["sessionUpdate"], "tool_call_update")
        self.assertEqual(responses[2]["update"]["sessionUpdate"], "agent_message_chunk")
        self.assertEqual(responses[-1]["type"], "bridge.done")

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


class FakeAgentManager:
    def prompt(self, request: AcpPromptRequest):
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


if __name__ == "__main__":
    unittest.main()
