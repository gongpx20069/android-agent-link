from __future__ import annotations

import http.client
import io
import json
import socket
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.control import execute
from android_acp_bridge.device_tokens import DeviceTokenStoreError
from android_acp_bridge.pairing import PairingStore, build_pairing_payload, encode_pairing_deep_link
from android_acp_bridge.runtime import BridgeRuntime
from android_acp_bridge.stdlib_server import BridgeHTTPServer, BridgeRequestHandler
from android_acp_bridge.web_gateway import MachineStore, WebError, WebServer, check_transport, endpoint_url, remote_json
from test_runtime import FakeAgentManager


class WebGatewayTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.servers = []
        self.runtimes = []
        self.local = self.runtime("Local")
        self.remote = self.runtime("VM")
        self.bridge = self.start(BridgeHTTPServer(("127.0.0.1", 0), self.remote))
        self.web = self.start(WebServer(self.local, store=Path(self.directory.name) / "web-machines.json"))
        self.token = self.remote.issue_device_token()
        self.endpoint = f"http://127.0.0.1:{self.bridge.server_port}"
        self.cookie = ""

    def runtime(self, name):
        runtime = BridgeRuntime(BridgeConfig(machine_name=name), PairingStore(),
                                agent_manager=FakeAgentManager(), require_local_pairing_confirmation=False,
                                console=Mock())
        runtime.agents_response = lambda: {"agents": [{"id": "copilot-cli", "displayName": "Copilot", "status": "available"}]}
        execute(runtime, {"action": "chat.register", "chatId": "same-id", "agentId": "copilot-cli",
                          "workspacePath": self.directory.name, "chatTitle": name})
        self.runtimes.append(runtime)
        return runtime

    def start(self, server):
        worker = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        worker.start()
        self.servers.append((server, worker))
        return server

    def tearDown(self):
        for runtime in self.runtimes:
            for approval in runtime.local_approvals():
                runtime._resolve_approval(approval["approvalId"], "denied", approval["chatId"])
        for server, worker in reversed(self.servers):
            server.shutdown()
            server.server_close()
            worker.join(2)
        for runtime in self.runtimes:
            deadline = time.monotonic() + 3
            while runtime._active_prompts and time.monotonic() < deadline:
                time.sleep(0.01)
            runtime.shared.close()
        self.directory.cleanup()

    def request(self, path, body=None, *, headers=None, server=None):
        target = server or self.web
        connection = http.client.HTTPConnection("127.0.0.1", target.server_port, timeout=5)
        values = {"Origin": self.web.origin, "X-AgentLink-Web": "1", "Cookie": self.cookie}
        values.update(headers or {})
        try:
            connection.request("GET" if body is None else "POST", path,
                               None if body is None else json.dumps(body), values)
            response = connection.getresponse()
            raw = response.read()
            return response.status, dict(response.getheaders()), json.loads(raw) if response.getheader("Content-Type", "").startswith("application/json") else raw
        finally:
            connection.close()

    def login(self):
        status, headers, _ = self.request("/api/login", {"code": self.web.access_code})
        self.assertEqual(status, 200)
        self.cookie = headers["Set-Cookie"].split(";")[0]
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])

    def connect_remote(self):
        self.login()
        status, _, result = self.request("/api/machines/save", {"id": "vm", "name": "VM",
            "endpoint": self.endpoint, "token": self.token, "headers": {}})
        self.assertEqual((status, result), (200, {"ok": True}))

    def control(self, machine, action, **values):
        status, _, result = self.request("/api/control", {"machineId": machine, "request": {"action": action, **values}})
        self.assertEqual(status, 200, result)
        return result

    def test_loopback_auth_origin_host_logout_and_static_assets(self):
        self.assertEqual(self.web.server_address[0], "127.0.0.1")
        self.assertEqual(self.request("/api/machines")[0], 401)
        for headers in ({"Host": "attacker.invalid"}, {"Origin": "https://attacker.invalid"}, {"X-AgentLink-Web": ""}):
            self.assertEqual(self.request("/api/login", {"code": self.web.access_code}, headers=headers)[0], 403)
        self.assertEqual(self.request("/api/login", {"code": "wrong"})[0], 401)
        self.login()
        self.assertEqual(self.request("/api/machines")[0], 200)
        for path in ("/", "/style.css", "/app.js"):
            status, headers, body = self.request(path)
            self.assertEqual(status, 200)
            self.assertGreater(len(body), 500)
            self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
            self.assertNotIn(self.web.access_code.encode(), body)
        self.assertEqual(self.request("/../web_gateway.py")[0], 404)
        self.request("/api/logout", {})
        self.assertEqual(self.request("/api/machines")[0], 401)

    def test_remote_auth_is_required_and_machine_secrets_never_return(self):
        self.assertEqual(self.request("/control", {"action": "chat.list"}, server=self.bridge)[0], 401)
        self.connect_remote()
        result = self.control("vm", "chat.list")
        self.assertEqual(result["data"]["chats"][0]["chatTitle"], "VM")
        self.assertEqual(self.control("local", "chat.list")["data"]["chats"][0]["chatTitle"], "Local")
        status, _, public = self.request("/api/machines")
        self.assertEqual(status, 200)
        self.assertNotIn(self.token, json.dumps(public))
        saved = MachineStore(self.web.machines.path)
        self.assertEqual(saved.get("vm")["token"], self.token)
        self.assertEqual(set(saved.public()[0]), {"id", "name", "endpoint"})
        with patch.object(self.remote, "is_device_token_valid", return_value=False):
            status, _, result = self.request("/api/control", {"machineId": "vm", "request": {"action": "chat.list"}})
            self.assertEqual(status, 502)
            self.assertIn("authorization", result["error"])
        self.assertEqual(self.control("local", "chat.list")["status"], "ok")

    def test_local_and_remote_send_deduplicate_and_share_real_journal(self):
        self.connect_remote()
        for machine, runtime in (("local", self.local), ("vm", self.remote)):
            for _ in range(2):
                self.assertEqual(self.control(machine, "chat.send", chatId="same-id", operationId="one", content="hello")["status"], "ok")
            deadline = time.monotonic() + 3
            while runtime.shared.task("same-id", "one")["state"] != "completed":
                self.assertLess(time.monotonic(), deadline)
                time.sleep(0.01)
            read = self.control(machine, "chat.read", chatId="same-id", afterEventId=0, limit=100)["data"]
            self.assertEqual(sum(e["type"] == "operation.accepted" for e in read["events"]), 1)
            self.assertTrue(any(e.get("update", {}).get("content", {}).get("text") == "hello back" for e in read["events"]))
            self.assertEqual(self.control(machine, "chat.send", chatId="same-id", operationId="one", content="changed")["code"], "CONFLICT")

    def test_remote_approval_uses_exact_options_and_first_decision_wins(self):
        self.connect_remote()
        self.control("vm", "chat.send", chatId="same-id", operationId="approval", content="needs approval")
        deadline = time.monotonic() + 3
        while not self.remote.local_approvals():
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.01)
        approval = self.remote.local_approvals()[0]
        args = {"chatId": "same-id", "approvalId": approval["approvalId"], "decision": "approved"}
        self.assertEqual(self.control("vm", "approval.decide", **args, optionId="invented")["code"], "CONFLICT")
        self.assertEqual(self.control("local", "approval.decide", **args, optionId="allow-once")["code"], "CONFLICT")
        self.assertTrue(self.control("vm", "approval.decide", **args, optionId="allow-once")["data"]["resolved"])
        prior = self.control("vm", "approval.decide", **{**args, "decision": "denied"}, optionId="reject-once")["data"]
        self.assertEqual((prior["status"], prior["optionId"]), ("approved", "allow-once"))

    def test_pairing_consumes_link_keeps_credentials_private_and_reuses_machine_identity(self):
        self.login()
        def link():
            return encode_pairing_deep_link(build_pairing_payload(machine_name="VM", endpoint=self.endpoint,
                token=self.remote.pairing_store.create(), bridge_fingerprint=self.remote.config.bridge_fingerprint))
        first = link()
        status, _, result = self.request("/api/machines/pair", {"link": first})
        self.assertEqual((status, result), (200, {"ok": True}))
        identity = self.web.machines.public()[0]["id"]
        token = self.web.machines.get(identity)["token"]
        self.assertTrue(self.remote.is_device_token_valid(token))
        self.assertNotIn(token, json.dumps(self.request("/api/machines")[2]))
        self.assertEqual(self.request("/api/machines/pair", {"link": first})[0], 502)
        self.assertEqual(self.request("/api/machines/pair", {"link": link()})[0], 200)
        self.assertEqual([m["id"] for m in self.web.machines.public()], [identity])
        self.assertEqual(self.control(identity, "chat.list")["status"], "ok")
        self.assertEqual(self.request("/api/machines/remove", {"id": identity})[0], 200)
        self.assertEqual(self.web.machines.public(), [])
        self.assertEqual(self.remote.shared.chat("same-id")["chatTitle"], "VM")

    def test_invalid_input_and_failed_persistence_do_not_replace_registry(self):
        self.connect_remote()
        for link in ("not a link", "acpclient://[broken", "acpclient://pair?data=!!", "acpclient://pair?data=bnVsbA"):
            self.assertEqual(self.request("/api/machines/pair", {"link": link})[0], 400)
        for changes in ({"id": "local"}, {"endpoint": "http://[broken"}, {"headers": {"Authorization": "leak"}},
                        {"token": "a\r\nb"}, {"endpoint": "http://user:pass@localhost"}):
            payload = {"id": "vm", "name": "VM", "endpoint": self.endpoint, "token": self.token, **changes}
            self.assertEqual(self.request("/api/machines/save", payload)[0], 400)
        with patch("android_acp_bridge.web_gateway.os.replace", side_effect=OSError("disk")):
            self.assertEqual(self.request("/api/machines/remove", {"id": "vm"})[0], 503)
        self.assertEqual(self.web.machines.public()[0]["id"], "vm")
        self.assertEqual(MachineStore(self.web.machines.path).public()[0]["id"], "vm")
        self.assertEqual(list(Path(self.directory.name).glob("*.pending")), [])
        self.assertEqual(self.request("/api/control", {"machineId": "local", "request": []})[0], 400)
        self.assertEqual(self.request("/api/control", {}, headers={"Content-Length": "512001"})[0], 413)
        corrupt = Path(self.directory.name) / "corrupt.json"
        corrupt.write_text("[null]", encoding="utf-8")
        with self.assertRaises(DeviceTokenStoreError):
            MachineStore(corrupt)

    def test_plain_http_pins_validated_address_preserves_host_and_rejects_public_ip(self):
        original = socket.getaddrinfo
        lookups = []
        hosts = []
        post = BridgeRequestHandler.do_POST
        def capture(handler):
            hosts.append(handler.headers["Host"])
            post(handler)
        def resolve(host, port, *args, **kwargs):
            if host == "private.invalid":
                lookups.append(host)
                return original("127.0.0.1", port, *args, **kwargs)
            return original(host, port, *args, **kwargs)
        with patch("android_acp_bridge.web_gateway.socket.getaddrinfo", side_effect=resolve), \
                patch.object(BridgeRequestHandler, "do_POST", capture):
            result = remote_json({"endpoint": f"http://private.invalid:{self.bridge.server_port}", "token": self.token},
                                 "/control", {"action": "chat.list"})
        self.assertEqual(result["status"], "ok")
        self.assertEqual(lookups, ["private.invalid"])
        self.assertEqual(hosts, [f"private.invalid:{self.bridge.server_port}"])
        with patch("android_acp_bridge.web_gateway.socket.getaddrinfo",
                   return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 80))]):
            with self.assertRaisesRegex(WebError, "Unencrypted"):
                check_transport("http://public.invalid")
        self.assertEqual(endpoint_url("wss://relay.invalid/ws"), "https://relay.invalid")

    def test_remote_redirects_are_not_followed_with_credentials(self):
        received = []
        class Redirect(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append(self.path)
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{self.server.server_port}/destination")
                self.send_header("Content-Length", "0")
                self.end_headers()
            def log_message(self, *_):
                pass
        server = self.start(ThreadingHTTPServer(("127.0.0.1", 0), Redirect))
        with self.assertRaisesRegex(WebError, "redirects"):
            remote_json({"endpoint": f"http://127.0.0.1:{server.server_port}", "token": self.token},
                        "/control", {"action": "chat.list"})
        self.assertEqual(received, ["/control"])

    def test_cli_start_defaults_to_web_and_closes_listener_after_server_exit(self):
        from android_acp_bridge.main import main
        created = []
        def web(runtime, port, store):
            instance = WebServer(runtime, port, store)
            created.append(instance)
            return instance
        def serve(runtime):
            self.assertIs(runtime, self.local)
            self.assertIs(created[0].runtime, runtime)
            self.assertEqual(self.request("/", server=created[0])[0], 200)
        with patch("android_acp_bridge.main.BridgeRuntime", return_value=self.local), \
                patch("android_acp_bridge.web_gateway.WebServer", side_effect=web), \
                patch("android_acp_bridge.main.run_server", side_effect=serve), \
                patch("android_acp_bridge.main.render_terminal_qr", return_value="QR"), \
                patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(main(["start", "--transport", "local", "--web-port", "0",
                                  "--device-token-store", str(Path(self.directory.name) / "tokens.json")]), 0)
        self.assertEqual(created[0].socket.fileno(), -1)
        self.assertFalse(any(thread.name == "agentlink-web" for thread in threading.enumerate()))
        self.runtimes.remove(self.local)

    def test_cli_no_web_never_constructs_web_listener_or_registry(self):
        from android_acp_bridge.main import main
        with patch("android_acp_bridge.main.BridgeRuntime", return_value=self.local), \
                patch("android_acp_bridge.web_gateway.WebServer") as web, \
                patch("android_acp_bridge.main.run_server") as server, \
                patch("android_acp_bridge.main.render_terminal_qr", return_value="QR"), \
                patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(main(["start", "--transport", "local", "--no-web"]), 0)
            web.assert_not_called()
            server.assert_called_once_with(self.local)
        self.runtimes.remove(self.local)

    def test_cli_web_defaults_follow_backend_and_keep_explicit_web_compatibility(self):
        from android_acp_bridge.main import main
        for flags, expected in (([], True), (["--no-web"], False), (["--web"], True),
                                (["--server", "fastapi"], False), (["--server", "fastapi", "--web"], True)):
            with self.subTest(flags=flags), patch("android_acp_bridge.main._start", return_value=0) as start:
                self.assertEqual(main(["start", *flags]), 0)
                self.assertIs(start.call_args.args[0].web, expected)

    def test_cli_rejects_unsupported_web_backend_before_transport_setup(self):
        from android_acp_bridge.main import main
        with patch("android_acp_bridge.main.setup_devtunnel") as setup, patch("sys.stderr", new_callable=io.StringIO):
            self.assertEqual(main(["start", "--web", "--server", "fastapi"]), 1)
            self.assertEqual(main(["start", "--web", "--web-port", "-1"]), 1)
            setup.assert_not_called()


if __name__ == "__main__":
    unittest.main()
