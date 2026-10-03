from __future__ import annotations

import json
import queue
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentSession, _agent_command
from android_acp_bridge.agents import AGENT_SPECS, discover_agents
from android_acp_bridge.elicitation import qwen_question_form


class NativeAcpTests(unittest.TestCase):
    def test_discovery_and_native_commands(self):
        with patch("shutil.which", side_effect=lambda name: str(Path.cwd() / (name + ".exe"))):
            self.assertEqual({agent.id for agent in discover_agents()}, set(AGENT_SPECS))
            self.assertTrue(all(agent.status == "available" for agent in discover_agents()))
            for identity, command, arguments in (("kimi-cli", "kimi", ["acp"]), ("qwen-code", "qwen", ["--acp"])):
                self.assertEqual(_agent_command(identity, Path.cwd()), [str(Path.cwd() / (command + ".exe")), *arguments])
        with patch("shutil.which", return_value=None):
            for identity in ("kimi-cli", "qwen-code"):
                with self.assertRaisesRegex(AcpAgentError, "Install"):
                    _agent_command(identity, Path.cwd())

    def test_windows_npm_global_and_local_bin_resolution_uses_manifest_not_shell(self):
        for identity in ("claude-code", "kimi-cli", "qwen-code", "deepseek-harness"):
            spec = AGENT_SPECS[identity]
            for local in (False, True):
                with self.subTest(identity=identity, local=local), tempfile.TemporaryDirectory(prefix="acp & spaces ") as directory:
                    base = Path(directory)
                    root = base / "node_modules" / spec.package
                    root.mkdir(parents=True)
                    entry = root / "main.mjs"
                    entry.write_text("", encoding="utf-8")
                    (root / "package.json").write_text(json.dumps({"bin": {spec.command: "main.mjs"}}), encoding="utf-8")
                    shim = (base / "node_modules" / ".bin" if local else base) / (spec.command + ".cmd")
                    with patch("shutil.which", side_effect=lambda name: "node.exe" if name == "node" else str(shim)), patch(
                        "subprocess.run", return_value=MagicMock(returncode=0, stdout="0.1.7-rc.2\n")
                    ):
                        self.assertEqual(_agent_command(identity, base), ["node.exe", str(entry.resolve()), *spec.arguments])
                        (root / "package.json").write_text(json.dumps({"bin": {spec.command: "../../outside.js"}}), encoding="utf-8")
                        with self.assertRaisesRegex(AcpAgentError, "Cannot resolve"):
                            _agent_command(identity, base)

    def test_protocol_lifecycle_interactions_configuration_and_recovery(self):
        fixture = Path(__file__).parent / "fixtures" / "native_acp.py"
        for identity in ("kimi-cli", "qwen-code", "deepseek-harness"):
            with self.subTest(identity=identity), tempfile.TemporaryDirectory() as workspace, patch(
                "android_acp_bridge.acp_agent._agent_command",
                return_value=[sys.executable, "-u", str(fixture), identity],
            ):
                session = AcpAgentSession.start(identity, workspace)
                try:
                    self.assertEqual(len(session.take_pending_updates()[-1]["update"]["configOptions"]), 2)
                    self.assertEqual(len(session.list_sessions(workspace)), 2)
                    def permission(message):
                        if message["method"] == "elicitation/create":
                            return {"action": "accept", "content": {"q0": ["A", "B"]} if identity == "kimi-cli" else {"0": "A, B"}}
                        return "allow-once" if identity == "deepseek-harness" else "once"
                    session.permission_callback = permission
                    events = []
                    session.prompt("work", events.append)
                    self.assertEqual(events[-1]["update"]["content"]["text"], "done")
                    self.assertTrue(session.binding().resumable)
                    config, value = ("reasoning_effort", "max") if identity == "deepseek-harness" else ("mode", "plan")
                    configs = session.set_config_option(config, value)[-1]["update"]["configOptions"]
                    self.assertEqual(configs[1]["currentValue"], value)
                    if identity == "deepseek-harness":
                        self.assertFalse(session.binding().history_replay_supported)
                        with self.assertRaises(AcpAgentError):
                            session.history()
                    else:
                        history, count = session.history()
                        self.assertGreaterEqual(count, 2)
                        self.assertEqual(history[0]["update"]["content"]["text"], "saved user")
                finally:
                    session.stop()
                self.assertEqual(session._process.returncode, 0)
                restored = AcpAgentSession.load_for_continue(identity, workspace, "native-session", True)
                try:
                    self.assertEqual(restored.session_id, "native-session")
                    self.assertTrue(all(event["update"]["sessionUpdate"] == "config_option_update"
                                        for event in restored.take_pending_updates()))
                    ready = threading.Event()
                    result, errors = [], []
                    def prompt():
                        try:
                            result.extend(restored.prompt("wait for cancel", lambda _: ready.set()))
                        except Exception as error:
                            errors.append(error)
                    worker = threading.Thread(target=prompt)
                    worker.start()
                    try:
                        self.assertTrue(ready.wait(3))
                        restored.cancel_prompt()
                        worker.join(3)
                        self.assertFalse(worker.is_alive())
                        self.assertEqual(errors, [])
                        self.assertEqual(result[-1]["update"]["sessionUpdate"], "agentlink_prompt_cancelled")
                    finally:
                        restored.stop()
                        worker.join(3)
                finally:
                    restored.stop()

    def test_qwen_question_cannot_be_approved_as_permission_or_answered_without_content(self):
        params = {"sessionId": "s", "toolCall": {"_meta": {
            "qwenInteractionKind": "user_question", "qwenQuestions": [
                {"question": "Choose?", "options": [{"label": "A"}, {"label": "B"}]}]}},
            "options": [{"optionId": "proceed_once", "kind": "allow_once"}]}
        form = qwen_question_form(params)
        self.assertEqual(form["requestedSchema"]["required"], ["0"])
        process = MagicMock()
        session = AcpAgentSession(process, queue.Queue(), "s")
        session._agent_id = "qwen-code"
        self.addCleanup(session.stop)
        for answer in ("proceed_once", {"action": "cancel"}, {"action": "accept", "content": {}}):
            session.permission_callback = lambda _: answer
            session._handle_permission_request({"id": 1, "method": "session/request_permission", "params": params})
            wire = json.loads(process.stdin.write.call_args.args[0])
            self.assertNotEqual(wire.get("result", {}).get("outcome", {}).get("outcome"), "selected")
        session.permission_callback = lambda _: {"action": "accept", "content": {"0": "Custom answer"}}
        session._connection.cancelled.set()
        session._handle_permission_request({"id": 1, "method": "session/request_permission", "params": params})
        self.assertEqual(json.loads(process.stdin.write.call_args.args[0])["result"]["outcome"], {"outcome": "cancelled"})
        params["options"] = [{"optionId": "proceed_once", "kind": "allow_always"}]
        with self.assertRaisesRegex(ValueError, "submit option"):
            qwen_question_form(params)

    def test_qwen_child_text_is_activity_not_main_answer(self):
        session = AcpAgentSession(MagicMock(), queue.Queue(), "s")
        self.addCleanup(session.stop)
        session._agent_id = "qwen-code"
        event = session._receive_update({"sessionId": "s", "update": {
            "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "child"},
            "_meta": {"parentToolCallId": "parent"},
        }})[0]
        self.assertEqual(event["update"]["sessionUpdate"], "agent_thought_chunk")
