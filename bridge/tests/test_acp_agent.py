from __future__ import annotations

import queue
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from android_acp_bridge.acp_agent import AcpAgentSession


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

    def test_load_caches_config_options_from_session_load_result(self) -> None:
        session = self._session()
        session._request = MagicMock(return_value=({"configOptions": MODEL_OPTIONS}, []))

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

    def test_set_config_option_refreshes_cached_options_from_result(self) -> None:
        session = self._session()
        updated_options = [{**MODEL_OPTIONS[0], "currentValue": "auto"}]
        session._request = MagicMock(return_value=({"configOptions": updated_options}, []))

        updates = session.set_config_option("model", "auto")

        self.assertEqual(updates[0]["update"]["configOptions"][0]["currentValue"], "auto")
        self.assertEqual(self._model_option(session)["currentValue"], "auto")

    @staticmethod
    def _session() -> AcpAgentSession:
        return AcpAgentSession(MagicMock(), queue.Queue(), "")

    @staticmethod
    def _model_option(session: AcpAgentSession) -> dict:
        updates = session.config_option_updates()
        return updates[0]["update"]["configOptions"][0]


if __name__ == "__main__":
    unittest.main()
