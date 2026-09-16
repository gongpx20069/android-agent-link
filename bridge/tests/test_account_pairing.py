from __future__ import annotations

import io
import json
import unittest
import threading
import urllib.request
import urllib.error
from unittest.mock import MagicMock, patch

from android_acp_bridge.account_pairing import AccountPairing, AccountPairingError
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.device_tokens import DeviceTokenStoreError
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.stdlib_server import BridgeRequestHandler, BridgeHTTPServer


class AccountPairingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.now = 1000.0
        self.issue = MagicMock(return_value={"machineId": "test", "deviceToken": "device", "bridgeFingerprint": "fingerprint"})
        self.confirm = MagicMock(return_value=True)
        self.store = AccountPairing(self.issue, self.confirm, lambda: self.now)
        self.thread = patch("android_acp_bridge.account_pairing.threading.Thread").start()
        self.addCleanup(patch.stopall)

    def approve(self) -> None:
        kwargs = self.thread.call_args.kwargs
        kwargs["target"](*kwargs["args"])

    def test_pending_never_issues_token_and_approved_result_is_consumed_once(self) -> None:
        request = self.store.request("Phone")
        self.assertEqual(len(request["confirmationCode"]), 6)
        self.assertEqual(request["status"], "pending")
        self.assertEqual(self.store.poll(request["requestId"], request["pollToken"]), {"status": "pending"})
        self.issue.assert_not_called()
        self.approve()
        self.confirm.assert_called_once_with("Phone", request["confirmationCode"])
        self.assertEqual(self.store.poll(request["requestId"], request["pollToken"])["deviceToken"], "device")
        self.issue.assert_called_once()
        with self.assertRaises(AccountPairingError):
            self.store.poll(request["requestId"], request["pollToken"])

    def test_wrong_poll_secret_never_issues_a_token(self) -> None:
        request = self.store.request("Phone")
        self.approve()
        with self.assertRaises(AccountPairingError) as error:
            self.store.poll(request["requestId"], "wrong")
        self.assertEqual(error.exception.status, 401)
        self.issue.assert_not_called()

    def test_denial_and_late_approval_never_issue_token(self) -> None:
        for accepted, late, expected in ((False, False, "denied"), (True, True, "expired")):
            with self.subTest(expected=expected):
                self.store = AccountPairing(self.issue, self.confirm, lambda: self.now)
                self.confirm.return_value = accepted
                request = self.store.request("Phone")
                if late:
                    self.now += 121
                self.approve()
                self.assertEqual(self.store.poll(request["requestId"], request["pollToken"]), {"status": expected})
                self.issue.assert_not_called()

    def test_approval_expires_before_collection(self) -> None:
        request = self.store.request("Phone")
        self.approve()
        self.now += 121
        self.assertEqual(self.store.poll(request["requestId"], request["pollToken"]), {"status": "expired"})
        self.issue.assert_not_called()

    def test_only_one_console_prompt_even_when_expired(self) -> None:
        self.store.request("Phone")
        self.now += 121
        with self.assertRaises(AccountPairingError) as error:
            self.store.request("Another phone")
        self.assertEqual(error.exception.status, 429)
        self.assertEqual(self.thread.call_count, 1)

    def test_token_storage_failure_does_not_consume_approval(self) -> None:
        request = self.store.request("Phone")
        self.approve()
        self.issue.side_effect = DeviceTokenStoreError("Storage failed")
        with self.assertRaises(DeviceTokenStoreError):
            self.store.poll(request["requestId"], request["pollToken"])
        self.issue.side_effect = None
        self.assertEqual(self.store.poll(request["requestId"], request["pollToken"])["status"], "approved")

    def test_eof_is_denial(self) -> None:
        request = self.store.request("Phone")
        self.confirm.side_effect = EOFError()
        self.approve()
        self.assertEqual(self.store.poll(request["requestId"], request["pollToken"])["status"], "denied")


class AccountPairingRuntimeTests(unittest.TestCase):
    def runtime(self, enabled: bool = True) -> BridgeRuntime:
        return BridgeRuntime(BridgeConfig(machine_name="test"), PairingStore(), False, account_pairing_enabled=enabled)

    def test_disabled_outside_account_mode(self) -> None:
        runtime = self.runtime(False)
        self.assertFalse(runtime.health_response()["accountPairing"])
        with self.assertRaises(AccountPairingError) as error:
            runtime.account_pairing_request({})
        self.assertEqual(error.exception.status, 403)

    def test_input_labels_cannot_inject_console_controls(self) -> None:
        for name in ("Phone\nAPPROVE", "\x1b[2J", "x" * 81):
            with self.subTest(name=name), self.assertRaises(AccountPairingError):
                self.runtime().account_pairing_request({"device": {"name": name, "platform": "android", "appVersion": "1"}})

    def test_auto_approve_flag_cannot_bypass_account_confirmation(self) -> None:
        runtime = self.runtime()
        with patch("builtins.input", return_value="yes"), patch("sys.stdout", new_callable=io.StringIO):
            self.assertFalse(runtime._confirm_account_pairing("Phone", "123456"))
        with patch("builtins.input", return_value="123456"), patch("sys.stdout", new_callable=io.StringIO):
            self.assertTrue(runtime._confirm_account_pairing("Phone", "123456"))

    def test_console_is_not_shared_with_another_pairing_prompt(self) -> None:
        runtime = self.runtime()
        with runtime._console_pairing_lock, patch("builtins.input") as read:
            self.assertFalse(runtime._confirm_account_pairing("Phone", "123456"))
        read.assert_not_called()

    def test_http_routes_return_pending_and_poll_failures(self) -> None:
        handler = object.__new__(BridgeRequestHandler)
        handler.server = MagicMock()
        handler.server.runtime = self.runtime()
        handler._send_json = MagicMock()
        handler._read_json_body = lambda: {"requestId": "missing", "pollToken": "wrong"}
        handler.path = "/pairing/status"
        handler.do_POST()
        self.assertEqual(handler._send_json.call_args.args[0], 401)
        handler.path = "/pairing/request"
        handler._read_json_body = lambda: {"device": {"name": "Phone", "platform": "android", "appVersion": "1"}}
        with patch("android_acp_bridge.account_pairing.threading.Thread"):
            handler.do_POST()
        self.assertEqual(handler._send_json.call_args.args[0], 200)
        self.assertEqual(handler._send_json.call_args.args[1]["status"], "pending")

    def test_request_body_is_size_limited(self) -> None:
        handler = object.__new__(BridgeRequestHandler)
        handler.headers = {"Content-Length": "20000"}
        handler.rfile = io.BytesIO(json.dumps({"device": "x"}).encode())
        self.assertIsNone(handler._read_json_body())

    def test_http_end_to_end_pairing_then_device_authentication(self) -> None:
        runtime = self.runtime()
        confirmed = threading.Event()
        release = threading.Event()

        def confirm(name: str, code: str) -> bool:
            confirmed.set()
            return release.wait(3)

        runtime._account_pairing = AccountPairing(runtime._pairing_result, confirm)
        with BridgeHTTPServer(("127.0.0.1", 0), runtime) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            base = f"http://127.0.0.1:{server.server_port}"

            def post(path: str, body: dict) -> dict:
                request = urllib.request.Request(base + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=3) as response:
                    return json.load(response)

            try:
                request = post("/pairing/request", {"device": {"name": "Phone", "platform": "android", "appVersion": "1"}})
                self.assertTrue(confirmed.wait(2))
                poll = {"requestId": request["requestId"], "pollToken": request["pollToken"]}
                self.assertEqual(post("/pairing/status", poll), {"status": "pending"})
                release.set()
                # Synchronize completion without relying on HTTP timing.
                for _ in range(100):
                    result = post("/pairing/status", poll)
                    if result["status"] != "pending":
                        break
                    threading.Event().wait(0.01)
                self.assertEqual(result["status"], "approved")
                self.assertTrue(runtime.is_device_token_valid(result["deviceToken"]))
                with self.assertRaises(urllib.error.HTTPError) as error:
                    post("/pairing/status", poll)
                self.assertEqual(error.exception.code, 401)
            finally:
                release.set()
                server.shutdown()
                worker.join(3)


if __name__ == "__main__":
    unittest.main()
