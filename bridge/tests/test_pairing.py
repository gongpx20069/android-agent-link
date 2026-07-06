from __future__ import annotations

import base64
import json
import unittest
from datetime import timedelta

from android_acp_bridge.pairing import PairingStore, build_pairing_payload, encode_pairing_deep_link, render_terminal_qr


class PairingTests(unittest.TestCase):
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
