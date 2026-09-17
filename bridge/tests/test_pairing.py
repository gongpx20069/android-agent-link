from __future__ import annotations

import base64
import json
import io
import unittest
from datetime import timedelta
from unittest.mock import patch

from android_acp_bridge.main import _parse_connection_headers, _validate_pairing_endpoint, main
from android_acp_bridge.pairing import PairingStore, build_pairing_payload, encode_pairing_deep_link, render_terminal_qr


class PairingTests(unittest.TestCase):
    def test_startup_qr_is_command_only_in_interactive_mode(self) -> None:
        for mode in ("interactive", "stdlib", "fastapi"):
            with (
                self.subTest(mode=mode),
                patch("android_acp_bridge.main.importlib.util.find_spec", return_value=object()),
                patch("sys.stdin.isatty", return_value=True),
                patch("sys.stdout", new_callable=io.StringIO) as output,
                patch("android_acp_bridge.main.BridgeRuntime"),
                patch("android_acp_bridge.main.render_terminal_qr", return_value="QR-MARKER"),
                patch("android_acp_bridge.main.run_server") as server,
                patch("android_acp_bridge.main._run_fastapi") as fastapi,
                patch("android_acp_bridge.terminal.run_interactive") as terminal,
            ):
                args = ["start", "--transport", "local"]
                args += ["--interactive"] if mode == "interactive" else ["--server", mode]
                with patch.object(output, "isatty", return_value=True):
                    self.assertEqual(main(args), 0)
                if mode == "interactive":
                    self.assertNotIn("QR-MARKER", output.getvalue())
                    self.assertNotIn("acpclient://", output.getvalue())
                    self.assertNotIn("Android pairing QR:", output.getvalue())
                    client = terminal.call_args.args[1]
                    self.assertIn("QR-MARKER", client.pairing_display)
                    self.assertIn("acpclient://", client.pairing_display)
                    server.assert_not_called()
                    fastapi.assert_not_called()
                    client.close()
                else:
                    self.assertIn("QR-MARKER", output.getvalue())
                    self.assertIn("Android pairing QR:", output.getvalue())
                    self.assertIn("acpclient://", output.getvalue())
                    terminal.assert_not_called()

    def test_start_defaults_to_devtunnel_transport(self) -> None:
        with patch("android_acp_bridge.main._start", return_value=0) as start:
            self.assertEqual(main(["start"]), 0)

        self.assertEqual(start.call_args.args[0].transport, "devtunnel")

    def test_pairing_token_is_one_time_use(self) -> None:
        store = PairingStore()
        token = store.create()

        self.assertTrue(store.redeem(token.pairing_id, token.pairing_token))
        self.assertFalse(store.redeem(token.pairing_id, token.pairing_token))

    def test_expired_token_is_rejected(self) -> None:
        store = PairingStore()
        token = store.create(ttl=timedelta(seconds=-1))

        self.assertFalse(store.redeem(token.pairing_id, token.pairing_token))

    def test_deep_link_contains_base64url_payload(self) -> None:
        store = PairingStore()
        token = store.create()
        payload = build_pairing_payload(
            machine_name="devbox",
            endpoint="ws://100.64.0.10:4317",
            token=token,
            bridge_fingerprint="sha256:test",
        )

        link = encode_pairing_deep_link(payload)
        encoded = link.split("data=", 1)[1]
        decoded = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        body = json.loads(decoded)

        self.assertEqual(body["type"], "acp-bridge-pairing")
        self.assertEqual(body["machineName"], "devbox")
        self.assertEqual(body["endpoint"], "ws://100.64.0.10:4317")

    def test_deep_link_can_include_connection_headers(self) -> None:
        store = PairingStore()
        token = store.create()
        payload = build_pairing_payload(
            machine_name="devbox",
            endpoint="wss://example.devtunnels.ms",
            token=token,
            bridge_fingerprint="sha256:test",
            headers={"X-Tunnel-Authorization": "tunnel token"},
        )

        link = encode_pairing_deep_link(payload)
        encoded = link.split("data=", 1)[1]
        decoded = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        body = json.loads(decoded)

        self.assertEqual(body["headers"], {"X-Tunnel-Authorization": "tunnel token"})

    def test_parse_connection_headers_accepts_dev_tunnel_authorization(self) -> None:
        self.assertEqual(
            _parse_connection_headers(["X-Tunnel-Authorization=tunnel token"]),
            {"X-Tunnel-Authorization": "tunnel token"},
        )

    def test_parse_connection_headers_rejects_other_headers(self) -> None:
        with self.assertRaises(SystemExit):
            _parse_connection_headers(["Authorization=Bearer token"])

    def test_validate_pairing_endpoint_accepts_websocket_schemes(self) -> None:
        self.assertEqual(_validate_pairing_endpoint("wss://example.devtunnels.ms/"), "wss://example.devtunnels.ms")
        self.assertEqual(_validate_pairing_endpoint("ws://100.64.0.10:4317"), "ws://100.64.0.10:4317")

    def test_validate_pairing_endpoint_rejects_http_scheme(self) -> None:
        with self.assertRaises(SystemExit):
            _validate_pairing_endpoint("https://example.devtunnels.ms")

    def test_terminal_qr_is_rendered(self) -> None:
        qr = render_terminal_qr("acpclient://pair?data=test")

        self.assertRegex(qr, "[▀▄█]")
        self.assertNotIn("\u00a0", qr)
        self.assertGreater(len(qr.splitlines()), 1)

    def test_terminal_qr_can_render_with_ansi_contrast(self) -> None:
        qr = render_terminal_qr("acpclient://pair?data=test", ansi=True)

        self.assertIn("\033[38;5;", qr)
        self.assertIn("\033[48;5;", qr)
        self.assertIn("\033[0m", qr)

    def test_terminal_qr_uses_compact_single_width_cells(self) -> None:
        store = PairingStore()
        token = store.create()
        payload = build_pairing_payload(
            machine_name="devbox",
            endpoint="ws://127.0.0.1:4317",
            token=token,
            bridge_fingerprint="sha256:test",
        )

        qr = render_terminal_qr(encode_pairing_deep_link(payload))
        max_line_width = max(len(line) for line in qr.splitlines())

        self.assertLess(max_line_width, 120)


if __name__ == "__main__":
    unittest.main()
