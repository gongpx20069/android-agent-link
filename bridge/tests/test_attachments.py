from __future__ import annotations

import base64
import http.client
import io
import json
import queue
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from PIL import Image

from android_acp_bridge.acp_agent import AcpAgentError, AcpAgentSession
from android_acp_bridge.attachments import AttachmentStore, ImageInput, MAX_IMAGE_BYTES, IMAGE_MAX_AGE_SECONDS
from android_acp_bridge.config import BridgeConfig
from android_acp_bridge.history import HistoryStore
from android_acp_bridge.pairing import PairingStore
from android_acp_bridge.runtime import BridgeRuntime, DeviceInfo
from android_acp_bridge.shared_state import ControlError, SharedState, prompt_digest
from android_acp_bridge.stdlib_server import BridgeHTTPServer


def png(color="red", size=(8, 8)):
    with io.BytesIO() as output:
        Image.new("RGB", size, color).save(output, format="PNG")
        return output.getvalue()


def register(shared, identity="chat"):
    shared.register({"chatId": identity, "agentId": "copilot-cli", "workspacePath": str(Path.cwd())})


class AttachmentStoreTests(unittest.TestCase):
    def setUp(self):
        self.shared = SharedState()
        self.addCleanup(self.shared.close)
        register(self.shared)
        register(self.shared, "other")
        self.store = AttachmentStore(self.shared)
        self.data = png()

    def test_exact_bytes_deduplicated_and_chat_scoped(self):
        metadata = self.store.put("chat", "image/png", self.data)
        self.assertEqual(metadata, self.store.put("chat", "image/png", self.data))
        self.assertEqual(metadata, self.store.resolve("chat", metadata))
        self.assertEqual(self.store.get("chat", metadata["id"]).data, self.data)
        self.assertEqual(self.shared.db.execute("SELECT COUNT(*) FROM images").fetchone()[0], 1)
        with self.assertRaisesRegex(ControlError, "unavailable"):
            self.store.get("other", metadata["id"])
        with self.assertRaises(ControlError):
            self.store.get("chat", "../private")

    def test_rejects_invalid_mime_size_pixels_and_metadata(self):
        for mime, data in [("image/gif", self.data), ("image/jpeg", self.data),
                           ("image/png", b"not an image"), ("image/png", b""),
                           ("image/png", b"x" * (MAX_IMAGE_BYTES + 1))]:
            with self.subTest(mime=mime, size=len(data)), self.assertRaises(ControlError):
                self.store.put("chat", mime, data)
        with patch("android_acp_bridge.attachments.MAX_IMAGE_PIXELS", 63):
            with self.assertRaises(ControlError):
                self.store.put("chat", "image/png", self.data)
        metadata = self.store.put("chat", "image/png", self.data)
        for changed in [{**metadata, "size": metadata["size"] + 1},
                        {**metadata, "size": float(metadata["size"])},
                        {**metadata, "mimeType": "image/jpeg"}, {**metadata, "data": "base64"}]:
            with self.assertRaises(ControlError):
                self.store.resolve("chat", changed)

    def test_quota_failure_does_not_evict_used_history_and_ttl_prunes_only_unused(self):
        retained = self.store.put("chat", "image/png", self.data)
        self.store.retain("chat", retained)
        old = self.store.put("other", "image/png", self.data)
        with self.shared.db:
            self.shared.db.execute("UPDATE images SET created=0,accessed=0")
        with patch("android_acp_bridge.attachments.IMAGE_QUOTA", len(self.data)):
            with self.assertRaisesRegex(ControlError, "full"):
                self.store.put("chat", "image/png", png("blue"))
        self.assertEqual(self.store.get("chat", retained["id"]).data, self.data)
        self.store.put("chat", "image/png", png("blue"))
        with self.assertRaises(ControlError):
            self.store.get("other", old["id"])
        self.assertEqual(self.store.get("chat", retained["id"]).data, self.data)

    def test_bytes_and_retention_survive_reopen(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.sqlite"
            first = SharedState(path)
            register(first)
            metadata = AttachmentStore(first).put("chat", "image/png", self.data)
            AttachmentStore(first).retain("chat", metadata)
            first.close()
            second = SharedState(path)
            try:
                self.assertEqual(AttachmentStore(second).get("chat", metadata["id"]).data, self.data)
                self.assertEqual(second.db.execute("SELECT used FROM images").fetchone()[0], 1)
            finally:
                second.close()

    def test_lru_evicts_completed_images_and_reads_refresh_recency(self):
        images = [self.store.put("chat", "image/png", png(color)) for color in ["red", "blue"]]
        for image in images:
            self.store.retain("chat", image)
            self.store.release("chat", image)
        with self.shared.db:
            self.shared.db.execute("UPDATE images SET accessed=1")
        self.store.get("chat", images[0]["id"])
        data = png("green")
        with patch("android_acp_bridge.attachments.IMAGE_QUOTA", images[0]["size"] + len(data)):
            new = self.store.put("chat", "image/png", data)
        self.assertEqual(self.store.get("chat", new["id"]).data, data)
        self.store.get("chat", images[0]["id"])
        with self.assertRaisesRegex(ControlError, "automatically cleared"):
            self.store.get("chat", images[1]["id"])

    def test_shared_image_stays_pinned_until_every_task_releases_it(self):
        image = self.store.put("chat", "image/png", self.data)
        self.store.retain("chat", image)
        self.store.retain("chat", image)
        self.store.release("chat", image)
        with patch("android_acp_bridge.attachments.IMAGE_QUOTA", len(png("blue"))):
            with self.assertRaisesRegex(ControlError, "protected"):
                self.store.put("chat", "image/png", png("blue"))
            self.store.release("chat", image)
            self.store.put("chat", "image/png", png("blue"))
        with self.assertRaises(ControlError):
            self.store.get("chat", image["id"])

    def test_fresh_upload_grace_and_failed_eviction_are_atomic(self):
        image = self.store.put("chat", "image/png", self.data)
        with patch("android_acp_bridge.attachments.IMAGE_QUOTA", len(self.data)):
            with self.assertRaisesRegex(ControlError, "protected"):
                self.store.put("chat", "image/png", png("blue"))
        self.store.get("chat", image["id"])
        with self.shared.db:
            self.shared.db.execute("UPDATE images SET accessed=0")
        self.store.retain("chat", image)
        with patch("android_acp_bridge.attachments.IMAGE_QUOTA", 1):
            with self.assertRaises(ControlError):
                self.store.put("chat", "image/png", png("blue"))
        self.store.get("chat", image["id"])

    def test_old_image_schema_migrates_without_losing_bytes(self):
        self.shared.db.execute("DROP TABLE images")
        self.shared.db.execute("""CREATE TABLE images(chat TEXT,id TEXT,mime TEXT,data BLOB,
                               created REAL,used INTEGER,PRIMARY KEY(chat,id))""")
        self.shared.db.execute("INSERT INTO images VALUES(?,?,?,?,?,?)",
                               ("chat", "a" * 64, "image/png", self.data, 123, 1))
        store = AttachmentStore(self.shared)
        self.assertEqual(self.shared.db.execute("SELECT accessed FROM images").fetchone()[0], 123)
        self.assertEqual(store.get("chat", "a" * 64).data, self.data)

    def test_seven_day_age_is_checked_only_on_upload_even_below_quota(self):
        now = 1_000_000
        with patch("android_acp_bridge.attachments.time.time", return_value=now):
            old, boundary, active = [self.store.put("chat", "image/png", png(color)) for color in ["red", "blue", "green"]]
            self.store.retain("chat", active)
            with self.shared.db:
                self.shared.db.execute("UPDATE images SET created=?", (now - IMAGE_MAX_AGE_SECONDS - 1,))
                self.shared.db.execute("UPDATE images SET created=? WHERE id=?", (now - IMAGE_MAX_AGE_SECONDS, boundary["id"]))
            self.store.get("chat", old["id"])
            self.assertEqual(self.shared.db.execute("SELECT COUNT(*) FROM images").fetchone()[0], 3)
            fresh = self.store.put("chat", "image/png", png("yellow"))
            with self.assertRaises(ControlError):
                self.store.get("chat", old["id"])
            self.store.get("chat", boundary["id"])
            self.store.get("chat", active["id"])
            self.store.release("chat", active)
            self.assertEqual(fresh, self.store.put("chat", "image/png", png("yellow")))
            with self.assertRaises(ControlError):
                self.store.get("chat", active["id"])

    def test_native_blocks_have_exact_mime_and_base64(self):
        image = ImageInput(self.data, "image/png")
        self.assertEqual(base64.b64decode(image.acp_block()["data"]), self.data)
        self.assertEqual(image.acp_block()["type"], "image")
        self.assertEqual(image.copilot_blob()["type"], "blob")
        self.assertEqual(image.copilot_blob()["mimeType"], "image/png")

    def test_actual_byte_and_pixel_boundaries(self):
        exact_bytes = self.data + b"\0" * (MAX_IMAGE_BYTES - len(self.data))
        self.assertEqual(self.store.put("chat", "image/png", exact_bytes)["size"], MAX_IMAGE_BYTES)
        with self.assertRaises(ControlError):
            self.store.put("chat", "image/png", exact_bytes + b"\0")
        self.store.put("chat", "image/png", png(size=(4000, 4000)))
        with self.assertRaises(ControlError):
            self.store.put("chat", "image/png", png(size=(4001, 4000)))


class ImageManager:
    def __init__(self):
        self.calls = []
        self.started = threading.Event()
        self.release = threading.Event()
        self.release.set()
        self.supported = True

    def supports_images(self, chat_id):
        return self.supported

    def prompt(self, request, **kwargs):
        self.calls.append(request)
        self.started.set()
        if not self.release.wait(5):
            raise AcpAgentError("Test prompt timed out")
        return []


class AttachmentRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.manager = ImageManager()
        self.runtime = BridgeRuntime(BridgeConfig(machine_name="images"), PairingStore(),
                                     require_local_pairing_confirmation=False, agent_manager=self.manager)
        self.addCleanup(self.runtime.shared.close)
        register(self.runtime.shared)
        self.data = png()
        self.image = self.runtime.attachments.put("chat", "image/png", self.data)

    def send(self, operation, text="", image=None, emit=None):
        return self.runtime.websocket_responses({
            "type": "chat.prompt", "chatId": "chat", "operationId": operation,
            "agentId": "copilot-cli", "workspacePath": str(Path.cwd()), "content": text,
            **({"image": image} if image is not None else {}),
        }, emit=emit)

    def test_image_only_dispatch_journal_metadata_and_idempotency(self):
        events = self.send("one", image=self.image)
        self.assertEqual(len(self.manager.calls), 1)
        self.assertEqual(self.manager.calls[0].image.data, self.data)
        for event in events:
            if event["type"] in {"operation.accepted", "operation.started"}:
                self.assertEqual(event["image"], self.image)
        self.assertNotIn(base64.b64encode(self.data).decode(), json.dumps(events))
        self.send("one", image=self.image)
        self.assertEqual(len(self.manager.calls), 1)
        other = self.runtime.attachments.put("chat", "image/png", png("blue"))
        conflict = self.send("one", image=other)
        self.assertTrue(any("different content" in item.get("error", "") for item in conflict))
        self.assertEqual(len(self.manager.calls), 1)
        self.assertNotEqual(prompt_digest(""), prompt_digest("", self.image))
        self.assertEqual(self.runtime.attachments._pins, {})
        self.runtime._prompt_operations.clear()
        with self.runtime.shared.db:
            self.runtime.shared.db.execute("DELETE FROM images")
        duplicate = self.send("one", image=self.image)
        self.assertTrue(duplicate[0]["duplicate"])
        self.assertEqual(len(self.manager.calls), 1)

    def test_image_is_never_batched_with_neighbors_and_queue_order_is_preserved(self):
        self.manager.release.clear()
        events = []
        self.send("active", "active", emit=events.append)
        self.assertTrue(self.manager.started.wait(2))
        for identity, text, image in [("a", "a", None), ("b", "b", None), ("image", "", self.image),
                                       ("c", "c", None), ("d", "d", None)]:
            self.send(identity, text, image, events.append)
        final = self.runtime._prompt_operations[("chat", "d")]
        self.manager.release.set()
        self.assertTrue(final.completed.wait(3))
        self.assertEqual([item.prompt for item in self.manager.calls], ["active", "a\n\nb", "", "c\n\nd"])
        self.assertEqual([item.image is not None for item in self.manager.calls], [False, False, True, False])
        done = [item for item in events if item["type"] == "operation.done"]
        self.assertEqual([item["queueRemaining"] for item in done], [5, 4, 3, 2, 1, 0])

    def test_unavailable_image_is_explicit_failure_without_dispatch(self):
        self.runtime.shared.db.execute("DELETE FROM images")
        events = self.send("missing", image=self.image)
        self.assertEqual(events[0]["status"], "failed")
        self.assertEqual(self.manager.calls, [])

    def test_queue_cancellation_releases_only_that_attachment_pin(self):
        self.manager.release.clear()
        events = []
        self.send("active-image", image=self.image, emit=events.append)
        self.assertTrue(self.manager.started.wait(2))
        self.send("queued-image", image=self.image, emit=events.append)
        self.assertEqual(self.runtime.attachments._pins[("chat", self.image["id"])], 2)
        self.runtime.websocket_responses({"type": "chat.prompt.remove", "chatId": "chat", "operationId": "queued-image"})
        self.assertEqual(self.runtime.attachments._pins[("chat", self.image["id"])], 1)
        final = self.runtime._prompt_operations[("chat", "active-image")]
        self.manager.release.set()
        self.assertTrue(final.completed.wait(3))
        self.assertEqual(self.runtime.attachments._pins, {})

    def test_inline_provider_images_do_not_enter_event_or_history_payloads(self):
        raw = {"type": "session/update", "update": {"sessionUpdate": "user_message_chunk",
               "content": ImageInput(self.data, "image/png").acp_block()}}
        event = self.runtime._append_event("chat", raw)
        history = HistoryStore().create("chat", "session", [raw], 1, 20)
        self.assertNotIn(base64.b64encode(self.data).decode(), json.dumps([event, history]))
        self.assertIn("Image content omitted", json.dumps([event, history]))
        self.assertEqual(raw["update"]["content"]["type"], "image")
        self.assertEqual(event["update"]["image"], self.image)
        recovered = HistoryStore().create("chat", "session", [event], 1, 20)
        self.assertEqual(recovered["messages"][0]["image"], self.image)
        self.assertEqual(recovered["messages"][0]["text"], "")

    def test_replayed_copilot_blob_maps_back_to_chat_scoped_image(self):
        from android_acp_bridge.copilot_session import CopilotEventProjection
        raw = CopilotEventProjection().updates({"type": "user.message", "id": "provider-message", "data": {
            "content": "describe", "attachments": [ImageInput(self.data, "image/png").copilot_blob()],
        }}, history=True)
        updates = [self.runtime.attachments.project_update("chat", item) for item in raw]
        page = HistoryStore().create("chat", "session", updates, 1, 20)
        self.assertEqual(len(page["messages"]), 1)
        self.assertEqual(page["messages"][0]["text"], "describe")
        self.assertEqual(page["messages"][0]["image"], self.image)
        register(self.runtime.shared, "other")
        other = [self.runtime.attachments.project_update("other", item) for item in raw]
        self.assertNotIn('"image":', json.dumps(other))
        self.assertNotIn(base64.b64encode(self.data).decode(), json.dumps(other))

    def test_acp_negotiation_and_native_image_block(self):
        session = AcpAgentSession(MagicMock(), queue.Queue(), "session")
        with patch.object(session, "_request", return_value=({}, [])) as request:
            with self.assertRaisesRegex(AcpAgentError, "advertise"):
                session.prompt("", image=ImageInput(self.data, "image/png"))
            request.assert_not_called()
            session._capabilities = {"promptCapabilities": {"image": True}}
            session.prompt("describe", image=ImageInput(self.data, "image/png"))
            blocks = request.call_args.args[1]["prompt"]
            self.assertEqual(blocks[0], {"type": "text", "text": "describe"})
            self.assertEqual(blocks[1], ImageInput(self.data, "image/png").acp_block())


class AttachmentHttpTests(unittest.TestCase):
    def setUp(self):
        AttachmentRuntimeTests.setUp(self)
        pair = self.runtime.pairing_store.create()
        self.token = self.runtime.redeem_pairing(pair.pairing_id, pair.pairing_token, DeviceInfo("Test", "android", "test"))["deviceToken"]
        self.server = BridgeHTTPServer(("127.0.0.1", 0), self.runtime)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(3)

    def request(self, path, method="GET", data=None, authorized=True, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            request_headers = {"Authorization": "Bearer " + self.token} if authorized else {}
            request_headers.update(headers or {})
            connection.request(method, path, data, request_headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_auth_capability_upload_download_and_scope(self):
        for path in ["/attachments/capabilities?chatId=chat", "/attachments?chatId=chat&id=" + self.image["id"]]:
            self.assertEqual(self.request(path, authorized=False)[0], 401)
        status, headers, body = self.request("/attachments/capabilities?chatId=chat")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["imageSupported"])
        self.assertEqual(headers["Cache-Control"], "no-store")
        status, _, body = self.request("/attachments?chatId=chat", "POST", self.data, headers={"Content-Type": "image/png"})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), self.image)
        status, headers, body = self.request("/attachments?chatId=chat&id=" + self.image["id"])
        self.assertEqual((status, body), (200, self.data))
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(self.request("/attachments?chatId=unknown&id=" + self.image["id"])[0], 404)

    def test_unsupported_invalid_and_over_limit_uploads_are_rejected(self):
        self.manager.supported = False
        self.assertEqual(self.request("/attachments?chatId=chat", "POST", self.data)[0], 409)
        self.manager.supported = True
        self.assertEqual(self.request("/attachments?chatId=chat", "POST", self.data, headers={"Content-Type": "text/plain"})[0], 400)
        self.assertEqual(self.request("/attachments?chatId=chat", "POST", b"", headers={"Content-Length": str(MAX_IMAGE_BYTES + 1)})[0], 413)


if __name__ == "__main__":
    unittest.main()
