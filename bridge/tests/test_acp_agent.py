from __future__ import annotations

import queue
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from android_acp_bridge.acp_agent import (
    AcpAgentError,
    AcpAgentManager,
    AcpAgentSession,
    AcpPromptRequest,
    AcpSessionNotFoundError,
)


MODEL_OPTIONS = [
    {
        "type": "select",
        "id": "model",
        "name": "Model",
        "currentValue": "gpt-5.6-sol",
        "options": [
            {"value": "auto", "name": "Auto"},
            {"value": "gpt-5.6-sol", "name": "GPT-5.6 Sol"},
        ],
        "category": "model",
    }
]


class AcpAgentSessionConfigTests(unittest.TestCase):
    def test_start_caches_config_options_from_session_new_result(self) -> None:
        session = self._session()
        session._request = MagicMock(return_value=({"sessionId": "session-1", "configOptions": MODEL_OPTIONS}, []))

        with patch.object(AcpAgentSession, "start_without_session", return_value=session):
            started = AcpAgentSession.start("copilot-cli", str(Path.cwd()))

        self.assertEqual(self._model_option(started)["currentValue"], "gpt-5.6-sol")

    def test_start_stops_initialized_process_when_session_new_fails(self) -> None:
        session = self._session()
        session._request = MagicMock(side_effect=AcpAgentError("session/new failed"))
        session.stop = MagicMock()

        with (
            patch.object(AcpAgentSession, "start_without_session", return_value=session),
            self.assertRaises(AcpAgentError),
        ):
            AcpAgentSession.start("copilot-cli", str(Path.cwd()))

        session.stop.assert_called_once()

    def test_start_stops_process_when_session_new_has_no_id(self) -> None:
        session = self._session()
        session._request = MagicMock(return_value=({}, []))
        session.stop = MagicMock()

        with (
            patch.object(AcpAgentSession, "start_without_session", return_value=session),
            self.assertRaises(AcpAgentError),
        ):
            AcpAgentSession.start("copilot-cli", str(Path.cwd()))

        session.stop.assert_called_once()

    def test_start_without_session_stops_process_when_initialize_fails(self) -> None:
        process = MagicMock()
        process.stdin = MagicMock()
        process.stdout = MagicMock()
        process.stderr = MagicMock()
        process.poll.return_value = None

        with (
            patch("android_acp_bridge.acp_agent._agent_command", return_value=["agent"]),
            patch("android_acp_bridge.acp_agent.subprocess.Popen", return_value=process),
            patch("android_acp_bridge.acp_agent._start_json_reader"),
            patch("android_acp_bridge.acp_agent._start_stderr_reader"),
            patch.object(AcpAgentSession, "_request", side_effect=AcpAgentError("initialize failed")),
            self.assertRaises(AcpAgentError),
        ):
            AcpAgentSession.start_without_session("copilot-cli", str(Path.cwd()))

        process.terminate.assert_called_once()

    def test_start_without_session_stops_process_when_reader_start_fails(self) -> None:
        process = MagicMock()
        process.stdin = MagicMock()
        process.stdout = MagicMock()
        process.stderr = MagicMock()
        process.poll.return_value = None

        with (
            patch("android_acp_bridge.acp_agent._agent_command", return_value=["agent"]),
            patch("android_acp_bridge.acp_agent.subprocess.Popen", return_value=process),
            patch("android_acp_bridge.acp_agent._start_json_reader", side_effect=RuntimeError("thread failed")),
            self.assertRaises(RuntimeError),
        ):
            AcpAgentSession.start_without_session("copilot-cli", str(Path.cwd()))

        process.terminate.assert_called_once()

    def test_load_caches_config_options_from_session_load_result(self) -> None:
        session = self._session()
        session._request_and_drain = MagicMock(return_value=({"configOptions": MODEL_OPTIONS}, [], 0, False))

        with patch.object(AcpAgentSession, "start_without_session", return_value=session):
            loaded, _updates = AcpAgentSession.load("copilot-cli", str(Path.cwd()), "session-1")

        self.assertEqual(self._model_option(loaded)["options"][1]["value"], "gpt-5.6-sol")

    def test_load_recent_caches_config_options_from_session_load_result(self) -> None:
        session = self._session()
        session._request_and_drain = MagicMock(return_value=({"configOptions": MODEL_OPTIONS}, [], 0, False))

        with patch.object(AcpAgentSession, "start_without_session", return_value=session):
            loaded, _updates, _scanned, _truncated = AcpAgentSession.load_recent(
                "copilot-cli",
                str(Path.cwd()),
                "session-1",
                5,
            )

        self.assertEqual(self._model_option(loaded)["id"], "model")

    def test_load_for_continue_drains_replay_and_preserves_resumable_state(self) -> None:
        session = self._session()
        session._request_and_drain = MagicMock(return_value=({"configOptions": MODEL_OPTIONS}, [], 12, False))

        with patch.object(AcpAgentSession, "start_without_session", return_value=session):
            loaded = AcpAgentSession.load_for_continue(
                "copilot-cli",
                str(Path.cwd()),
                "session-1",
                resumable=False,
            )

        self.assertFalse(loaded.binding().resumable)
        self.assertEqual(
            session._request_and_drain.call_args.kwargs["collect_updates"],
            False,
        )

    def test_failed_load_stops_the_started_process(self) -> None:
        session = self._session()
        session._request_and_drain = MagicMock(side_effect=AcpSessionNotFoundError("not found"))
        session.stop = MagicMock()

        with (
            patch.object(AcpAgentSession, "start_without_session", return_value=session),
            self.assertRaises(AcpSessionNotFoundError),
        ):
            AcpAgentSession.load("copilot-cli", str(Path.cwd()), "missing")

        session.stop.assert_called_once()

    def test_truncated_recent_load_is_stopped_instead_of_reused(self) -> None:
        session = self._session()
        session._request_and_drain = MagicMock(return_value=({}, [], 100_000, True))
        session.stop = MagicMock()

        with (
            patch.object(AcpAgentSession, "start_without_session", return_value=session),
            self.assertRaises(AcpAgentError),
        ):
            AcpAgentSession.load_recent("copilot-cli", str(Path.cwd()), "session-1", 5)

        session.stop.assert_called_once()

    def test_set_config_option_refreshes_cached_options_from_result(self) -> None:
        session = self._session()
        updated_options = [{**MODEL_OPTIONS[0], "currentValue": "auto"}]
        session._request = MagicMock(return_value=({"configOptions": updated_options}, []))

        updates = session.set_config_option("model", "auto")

        self.assertEqual(updates[0]["update"]["configOptions"][0]["currentValue"], "auto")
        self.assertEqual(self._model_option(session)["currentValue"], "auto")

    def test_request_collects_updates_without_replay_limit_state(self) -> None:
        session = self._session()
        session._output_queue.put({"method": "session/update", "params": {"update": {"sessionUpdate": "agent_message_chunk"}}})
        session._output_queue.put({"id": 3, "result": {}})

        _result, updates = session._request("session/prompt", {}, timeout_seconds=1)

        self.assertEqual(len(updates), 1)

    def test_request_and_drain_retains_only_latest_updates(self) -> None:
        session = self._session()
        for index in range(3):
            session._output_queue.put(
                {
                    "method": "session/update",
                    "params": {"update": {"sessionUpdate": "agent_message_chunk", "index": index}},
                }
            )
        session._output_queue.put({"id": 3, "result": {}})

        _result, updates, scanned, truncated = session._request_and_drain(
            "session/load",
            {},
            timeout_seconds=1,
            drain_idle_seconds=0.01,
            drain_timeout_seconds=1,
            max_updates=2,
        )

        self.assertEqual(scanned, 3)
        self.assertFalse(truncated)
        self.assertEqual([update["update"]["index"] for update in updates], [1, 2])

    @staticmethod
    def _session() -> AcpAgentSession:
        return AcpAgentSession(MagicMock(), queue.Queue(), "")

    @staticmethod
    def _model_option(session: AcpAgentSession) -> dict:
        updates = session.config_option_updates()
        return updates[0]["update"]["configOptions"][0]


class AcpAgentManagerBindingTests(unittest.TestCase):
    def test_model_change_rejects_a_stale_session_under_chat_lock(self) -> None:
        manager = AcpAgentManager()
        live = self._session("new-session", resumable=True)
        manager._set_session("chat-1", live)
        with patch.object(live, "set_config_option") as change:
            with self.assertRaisesRegex(AcpAgentError, "Session changed"):
                manager.set_config_option(
                    "chat-1", "copilot-cli", str(Path.cwd()), "model", "auto",
                    session_id="old-session", session_resumable=True,
                )
            change.assert_not_called()

    def test_prompt_restores_the_client_session_after_manager_restart(self) -> None:
        manager = AcpAgentManager()
        loaded = self._session("session-1", resumable=True)
        bindings = []

        with patch.object(AcpAgentSession, "load_for_continue", return_value=loaded) as load:
            manager.prompt(
                AcpPromptRequest(
                    chat_id="chat-1",
                    agent_id="copilot-cli",
                    workspace_path=str(Path.cwd()),
                    prompt="continue",
                    session_id="session-1",
                    session_resumable=True,
                ),
                session_callback=bindings.append,
            )

        load.assert_called_once()
        self.assertEqual(bindings[0].session_id, "session-1")
        self.assertTrue(bindings[0].resumable)

    def test_missing_empty_session_is_replaced_explicitly(self) -> None:
        manager = AcpAgentManager()
        created = self._session("session-2")
        bindings = []

        with (
            patch.object(AcpAgentSession, "load_for_continue", side_effect=AcpSessionNotFoundError("not found")),
            patch.object(AcpAgentSession, "start", return_value=created) as start,
        ):
            manager.prompt(
                AcpPromptRequest(
                    chat_id="chat-1",
                    agent_id="copilot-cli",
                    workspace_path=str(Path.cwd()),
                    prompt="first prompt",
                    session_id="empty-session",
                    session_resumable=False,
                ),
                session_callback=bindings.append,
            )

        start.assert_called_once()
        self.assertEqual(bindings[0].session_id, "session-2")
        self.assertEqual(bindings[0].replaced_session_id, "empty-session")
        self.assertTrue(bindings[-1].resumable)

    def test_missing_resumable_session_is_never_silently_replaced(self) -> None:
        manager = AcpAgentManager()

        with (
            patch.object(AcpAgentSession, "load_for_continue", side_effect=AcpSessionNotFoundError("not found")),
            patch.object(AcpAgentSession, "start") as start,
            self.assertRaises(AcpAgentError),
        ):
            manager.prompt(
                AcpPromptRequest(
                    chat_id="chat-1",
                    agent_id="copilot-cli",
                    workspace_path=str(Path.cwd()),
                    prompt="continue",
                    session_id="important-session",
                    session_resumable=True,
                ),
            )

        start.assert_not_called()

    def test_non_not_found_load_error_is_not_replaced(self) -> None:
        manager = AcpAgentManager()

        with (
            patch.object(AcpAgentSession, "load_for_continue", side_effect=AcpAgentError("authentication failed")),
            patch.object(AcpAgentSession, "start") as start,
            self.assertRaises(AcpAgentError),
        ):
            manager.prompt(
                AcpPromptRequest(
                    chat_id="chat-1",
                    agent_id="copilot-cli",
                    workspace_path=str(Path.cwd()),
                    prompt="first prompt",
                    session_id="empty-session",
                    session_resumable=False,
                ),
            )

        start.assert_not_called()

    def test_live_chat_session_wins_over_stale_queued_prompt_binding(self) -> None:
        manager = AcpAgentManager()
        live = self._session("session-live")
        manager._sessions["chat-1"] = live
        bindings = []

        with patch.object(AcpAgentSession, "load_for_continue") as load:
            manager.prompt(
                AcpPromptRequest(
                    chat_id="chat-1",
                    agent_id="copilot-cli",
                    workspace_path=str(Path.cwd()),
                    prompt="queued prompt",
                    session_id="session-stale",
                    session_resumable=False,
                ),
                session_callback=bindings.append,
            )

        load.assert_not_called()
        self.assertEqual(bindings[0].session_id, "session-live")
        self.assertTrue(bindings[-1].resumable)

    def test_restore_reads_live_binding_without_waiting_for_active_prompt(self) -> None:
        manager = AcpAgentManager()
        live = self._session("session-live")
        prompt_started = threading.Event()
        release_prompt = threading.Event()

        def request(method, _params, timeout_seconds, update_callback=None):
            prompt_started.set()
            release_prompt.wait(timeout=5)
            return {}, []

        live._request = request
        with patch.object(AcpAgentSession, "start", return_value=live):
            prompt_thread = threading.Thread(
                target=lambda: manager.prompt(
                    AcpPromptRequest(
                        chat_id="chat-1",
                        agent_id="copilot-cli",
                        workspace_path=str(Path.cwd()),
                        prompt="long prompt",
                    )
                )
            )
            prompt_thread.start()
            self.assertTrue(prompt_started.wait(timeout=2))
            bindings = []

            manager.restore_session(
                "chat-1",
                "copilot-cli",
                str(Path.cwd()),
                "session-live",
                False,
                bindings.append,
            )

            self.assertEqual(bindings[0].session_id, "session-live")
            release_prompt.set()
            prompt_thread.join(timeout=2)
            self.assertFalse(prompt_thread.is_alive())

    @staticmethod
    def _session(session_id: str, resumable: bool = False) -> AcpAgentSession:
        session = AcpAgentSession(MagicMock(), queue.Queue(), session_id, resumable=resumable)
        session._request = MagicMock(return_value=({}, []))
        return session


if __name__ == "__main__":
    unittest.main()
