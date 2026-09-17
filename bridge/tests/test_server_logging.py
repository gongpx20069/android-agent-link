from __future__ import annotations

import asyncio
import importlib.util
import unittest
from unittest.mock import patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.console_log import ConsoleLog
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime


@unittest.skipUnless(importlib.util.find_spec("fastapi"), "Install requirements-fastapi.txt for optional backend tests")
class FastApiLoggingTests(unittest.IsolatedAsyncioTestCase):
    async def test_http_metadata_logging_preserves_responses_and_error_propagation(self) -> None:
        from android_acp_bridge.server import create_app

        lines: list[str] = []
        runtime = BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), console=ConsoleLog(write=lines.append))
        app = create_app(runtime)

        async def request(path: str) -> list[dict]:
            sent: list[dict] = []
            received = False

            async def receive() -> dict:
                nonlocal received
                if received:
                    await asyncio.Event().wait()
                received = True
                return {"type": "http.request", "body": b"", "more_body": False}

            async def send(message: dict) -> None:
                sent.append(message)

            await app({
                "type": "http", "asgi": {"version": "3.0", "spec_version": "2.4"},
                "http_version": "1.1", "method": "GET", "scheme": "http",
                "path": path, "raw_path": path.encode(), "query_string": b"token=PRIVATE",
                "root_path": "", "headers": [], "client": ("127.0.0.1", 1),
                "server": ("127.0.0.1", 4317),
            }, receive, send)
            return sent

        responses = await request("/health")
        self.assertEqual(responses[0]["status"], 200)
        self.assertTrue(any(b'"status":"ok"' in item.get("body", b"") for item in responses))
        self.assertFalse(lines)
        responses = await request("/unknown-private-path")
        self.assertEqual(responses[0]["status"], 404)
        self.assertIn("WARNING", lines[-1])
        with patch.object(runtime, "health_response", side_effect=RuntimeError("PRIVATE failure")):
            with self.assertRaises(RuntimeError):
                await request("/health")
        self.assertIn("ERROR", lines[-1])
        self.assertIn("status=500", lines[-1])
        self.assertNotIn("PRIVATE", "\n".join(lines))
        self.assertNotIn("unknown-private-path", "\n".join(lines))


if __name__ == "__main__":
    unittest.main()
