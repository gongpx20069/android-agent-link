from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentManager, AcpSessionBinding, _agent_command
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime


class ContextResumeTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.workspace = str(Path(directory.name).resolve())
        self.manager = AcpAgentManager()
        self.runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), False, self.manager)
        self.addCleanup(self.runtime.shared.close)
        self.addCleanup(self.stop_sessions)
        fixture = Path(__file__).parent / "fixtures" / "native_acp.py"
        command = patch("android_acp_bridge.acp_agent._agent_command",
                        return_value=[sys.executable, "-u", str(fixture), "deepseek-harness"])
        command.start()
        self.addCleanup(command.stop)
        self.payload = {"type": "session.resume", "chatId": "chat", "agentId": "deepseek-harness",
                        "workspacePath": self.workspace, "sessionId": "native-session"}
        self.runtime.shared.register({**self.payload, "sessionId": "old", "sessionResumable": True})

    def stop_sessions(self):
        for session in self.manager._sessions.values():
            session.stop()

    def resume(self, **overrides):
        return self.runtime.websocket_responses({**self.payload, **overrides})[0]

    def test_resume_preserves_journal_generation_and_announces_configuration_and_boundary(self):
        old = self.runtime._append_event("chat", {"type": "session/update", "update": {
            "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "retained"}}})
        generation = self.runtime._event_generation
        result = self.resume()
        self.assertNotIn("error", result)
        self.assertTrue(result["contextOnly"])
        self.assertFalse(result["historyReplaySupported"])
        self.assertNotIn("messages", result)
        self.assertEqual(self.runtime._event_generation, generation)
        events = list(self.runtime._event_logs["chat"])
        self.assertEqual(events[0], old)
        self.assertTrue(any(event.get("update", {}).get("sessionUpdate") == "config_option_update" for event in events))
        self.assertTrue(any("previous context" in event.get("update", {}).get("rawOutput", "") for event in events))
        self.assertFalse(self.runtime.shared.chat("chat")["historyReplaySupported"])
        live = self.manager._get_session("chat")
        self.assertNotIn("error", self.resume())
        self.assertIs(self.manager._get_session("chat"), live)

    def test_failed_resume_keeps_live_session_binding_and_history(self):
        self.assertNotIn("error", self.resume())
        live = self.manager._get_session("chat")
        events = list(self.runtime._event_logs["chat"])
        failed = self.resume(sessionId="missing")
        self.assertIn("error", failed)
        self.assertIs(self.manager._get_session("chat"), live)
        self.assertIsNone(live._process.poll())
        self.assertEqual(self.runtime.shared.chat("chat")["sessionId"], "native-session")
        self.assertEqual(list(self.runtime._event_logs["chat"]), events)
        self.assertNotIn("chat", self.runtime._history_loading_chats)

    def test_busy_approval_configuration_and_scope_guards(self):
        with patch.object(self.manager, "resume_session") as resume:
            for collection in (self.runtime._configuring_chats, self.runtime._history_loading_chats):
                collection.add("chat")
                self.assertIn("error", self.resume())
                collection.discard("chat")
            self.runtime._active_prompts["chat"] = "task"
            self.assertIn("error", self.resume())
            self.runtime._active_prompts.clear()
            self.runtime._pending_approvals["approval"] = SimpleNamespace(requested={"chatId": "chat"})
            self.assertIn("error", self.resume())
            self.runtime._pending_approvals.clear()
            self.assertIn("error", self.resume(agentId="qwen-code"))
            self.assertIn("error", self.resume(workspacePath=str(Path(self.workspace).parent)))
            self.assertIn("error", self.resume(sessionId=""))
            resume.assert_not_called()

    def test_list_advertises_no_replay_and_duplicate_ownership_is_rejected(self):
        sessions = self.manager.list_sessions("deepseek-harness", self.workspace)
        self.assertEqual(len(sessions), 2)
        self.assertTrue(all(session["historyReplaySupported"] is False for session in sessions))
        self.assertNotIn("error", self.resume())
        self.assertIn("already belongs", self.resume(chatId="other")["error"])
        with self.assertRaises(AcpAgentError):
            self.manager.load_recent_session("chat", "deepseek-harness", self.workspace, "native-session", 50)

    def test_wrong_binding_never_overwrites_shared_state(self):
        with patch.object(self.manager, "resume_session", return_value=(AcpSessionBinding("wrong", True), [])):
            self.assertIn("error", self.resume())
        self.assertEqual(self.runtime.shared.chat("chat")["sessionId"], "old")


class DshVersionTests(unittest.TestCase):
    def test_cli_release_is_checked_instead_of_acp_agent_info_version(self):
        with patch("shutil.which", return_value="dsh.exe"):
            for output in ("0.1.7-rc.1", "0.2.0", ""):
                with patch("subprocess.run", return_value=Mock(returncode=0, stdout=output)):
                    with self.assertRaisesRegex(AcpAgentError, "baseline"):
                        _agent_command("deepseek-harness", Path.cwd())
            with patch("subprocess.run", return_value=Mock(returncode=0, stdout="0.1.7-rc.2\n")):
                self.assertEqual(_agent_command("deepseek-harness", Path.cwd()), ["dsh.exe", "--profile", "acp"])
            with patch("subprocess.run", side_effect=subprocess.TimeoutExpired("dsh", 15)):
                with self.assertRaisesRegex(AcpAgentError, "Cannot check"):
                    _agent_command("deepseek-harness", Path.cwd())
