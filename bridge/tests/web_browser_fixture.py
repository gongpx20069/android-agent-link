"""Isolated fake peers for verify_web_browser.ps1; never starts a provider."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from android_acp_bridge.control import execute
from android_acp_bridge.pairing import build_pairing_payload, encode_pairing_deep_link
from android_acp_bridge.stdlib_server import BridgeHTTPServer
from test_runtime import FakeAgentManager
from test_web_gateway import WebGatewayTests
from test_attachments import png


class BrowserAgent(FakeAgentManager):
    images_supported = True

    def supports_images(self, chat_id):
        return self.images_supported

    def prompt(self, request, permission_callback=None, update_callback=None, session_callback=None):
        if request.image:
            return [{"type": "session/update", "update": {"sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": f"Image received: {len(request.image.data)} bytes; text: {request.prompt}"}}}]
        if request.prompt.startswith("markdown:"):
            return [{"type": "session/update", "update": {"sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": request.prompt.removeprefix("markdown:")}}}]
        if request.prompt == "needs question":
            result = permission_callback({"method": "elicitation/create", "params": {
                "mode": "form", "message": "Choose a branch", "requestedSchema": {
                    "type": "object", "properties": {"branch": {"type": "string", "minLength": 1},
                                                    "tests": {"type": "boolean"}},
                    "required": ["branch"],
                }}})
            return [{"type": "session/update", "update": {"sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "Question answered: " + json.dumps(result)}}}]
        return super().prompt(request, permission_callback, update_callback, session_callback)


def main():
    fixture = WebGatewayTests()
    fixture.setUp()
    try:
        for runtime in fixture.runtimes:
            runtime.agent_manager = BrowserAgent()
            execute(runtime, {"action": "chat.register", "chatId": "second", "agentId": "copilot-cli",
                              "workspacePath": fixture.directory.name, "chatTitle": "<img src=x onerror=alert(1)>"})
        link = encode_pairing_deep_link(build_pairing_payload(machine_name="VM", endpoint=fixture.endpoint,
            token=fixture.remote.pairing_store.create(), bridge_fingerprint=fixture.remote.config.bridge_fingerprint))
        image_path = Path(fixture.directory.name) / "browser-image.png"
        image_path.write_bytes(png())
        print(json.dumps({"url": fixture.web.origin, "code": fixture.web.access_code, "link": link,
                          "imagePath": str(image_path)}), flush=True)
        for line in sys.stdin:
            command = line.strip()
            if command == "quit":
                break
            if command == "offline":
                fixture.bridge.shutdown()
                fixture.bridge.server_close()
            elif command == "online":
                fixture.bridge = fixture.start(BridgeHTTPServer(("127.0.0.1", fixture.bridge.server_port), fixture.remote))
            elif command == "reset":
                fixture.remote.shared.reset_events("same-id")
                fixture.remote._record_broadcast("same-id", {"type": "session/update", "update": {
                    "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "New generation only"}}})
            elif command == "expire":
                with fixture.web.lock:
                    fixture.web.sessions.clear()
            elif command in {"images-off", "images-on"}:
                fixture.local.agent_manager.images_supported = command == "images-on"
            elif command == "delivery":
                for event in (
                    {"type": "operation.accepted", "operationId": "original", "content": "Original work", "state": "starting"},
                    {"type": "operation.started", "operationId": "original"},
                    {"type": "operation.accepted", "operationId": "supplement", "content": "Immediate supplement", "state": "sending"},
                    {"type": "operation.delivered", "operationId": "supplement", "status": "delivered", "parentOperationId": "original"},
                    {"type": "chat.status", "status": "busy", "operationId": "original"},
                ):
                    fixture.remote._record_broadcast("same-id", event)
            else:
                raise ValueError("Unknown fixture command")
            print(json.dumps({"ok": command}), flush=True)
    finally:
        fixture.tearDown()


if __name__ == "__main__":
    main()
