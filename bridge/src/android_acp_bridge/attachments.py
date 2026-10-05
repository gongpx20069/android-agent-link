"""Bounded, chat-scoped image bytes; event journals contain metadata only."""
from __future__ import annotations

import base64
import binascii
import hashlib
import io
import re
import time
from dataclasses import dataclass
from typing import Any

from PIL import Image, UnidentifiedImageError

from .shared_state import ControlError, SharedState, required_text

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_IMAGE_PIXELS = 16_000_000
IMAGE_QUOTA = 256 * 1024 * 1024
UPLOAD_GRACE_SECONDS = 600
IMAGE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
IMAGE_MIME_TYPES = ("image/png", "image/jpeg")


def error_status(error: ControlError) -> int:
    return {"NOT_FOUND": 404, "UNSUPPORTED": 409, "LIMIT_EXCEEDED": 413}.get(error.code, 400)


def image_id(value: Any) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ControlError("INVALID_ARGS", "Invalid image identifier.")
    return value


def image_metadata(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) != {"id", "mimeType", "size"}:
        raise ControlError("INVALID_ARGS", "Invalid image metadata.")
    image_id(value["id"])
    if type(value["size"]) is not int or not 0 < value["size"] <= MAX_IMAGE_BYTES or value["mimeType"] not in IMAGE_MIME_TYPES:
        raise ControlError("INVALID_ARGS", "Invalid image size or MIME type.")
    return dict(value)


@dataclass(frozen=True)
class ImageInput:
    data: bytes
    mime_type: str

    def acp_block(self) -> dict[str, str]:
        return {"type": "image", "mimeType": self.mime_type,
                "data": base64.b64encode(self.data).decode("ascii")}

    def copilot_blob(self) -> dict[str, str]:
        return {**self.acp_block(), "type": "blob"}


class AttachmentStore:
    def __init__(self, shared: SharedState) -> None:
        self.shared = shared
        self._pins: dict[tuple[str, str], int] = {}
        with shared.lock, shared.db:
            shared.db.execute("""CREATE TABLE IF NOT EXISTS images(
                chat TEXT NOT NULL, id TEXT NOT NULL, mime TEXT NOT NULL,
                data BLOB NOT NULL, created REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(chat,id))""")
            if "accessed" not in {row[1] for row in shared.db.execute("PRAGMA table_info(images)")}:
                shared.db.execute("ALTER TABLE images ADD COLUMN accessed REAL NOT NULL DEFAULT 0")
                shared.db.execute("UPDATE images SET accessed=created")

    def put(self, chat_id: str, mime: str, data: bytes) -> dict[str, Any]:
        required_text(chat_id, "chatId", 256)
        if mime not in IMAGE_MIME_TYPES or not 0 < len(data) <= MAX_IMAGE_BYTES:
            raise ControlError("INVALID_ARGS", "Use a PNG or JPEG image no larger than 5 MiB.")
        try:
            with Image.open(io.BytesIO(data)) as image:
                if image.width * image.height > MAX_IMAGE_PIXELS or image.width < 1 or image.height < 1:
                    raise ValueError("Image exceeds pixel limit.")
                if Image.MIME.get(image.format) != mime:
                    raise ValueError("Image format does not match Content-Type.")
                image.verify()
            with Image.open(io.BytesIO(data)) as image:
                image.load()
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as exc:
            raise ControlError("INVALID_ARGS", "Invalid image, MIME type, or pixel dimensions.") from exc
        identity = hashlib.sha256(data).hexdigest()
        with self.shared.lock, self.shared.db:
            self.shared.chat(chat_id)
            now = time.time()
            for chat, item in self.shared.db.execute(
                "SELECT chat,id FROM images WHERE created<?", (now - IMAGE_MAX_AGE_SECONDS,)
            ).fetchall():
                if (chat, item) not in self._pins:
                    self.shared.db.execute("DELETE FROM images WHERE chat=? AND id=?", (chat, item))
            exists = self.shared.db.execute("SELECT 1 FROM images WHERE chat=? AND id=?", (chat_id, identity)).fetchone()
            if not exists:
                size = self.shared.db.execute("SELECT COALESCE(SUM(length(data)),0) FROM images").fetchone()[0]
                if size + len(data) > IMAGE_QUOTA:
                    for chat, item, length in self.shared.db.execute(
                        "SELECT chat,id,length(data) FROM images WHERE used<>0 OR accessed<? ORDER BY accessed,chat,id",
                        (now - UPLOAD_GRACE_SECONDS,),
                    ).fetchall():
                        if (chat, item) in self._pins:
                            continue
                        self.shared.db.execute("DELETE FROM images WHERE chat=? AND id=?", (chat, item))
                        size -= length
                        if size + len(data) <= IMAGE_QUOTA:
                            break
                    if size + len(data) > IMAGE_QUOTA:
                        raise ControlError("LIMIT_EXCEEDED", "Image cache is full of protected uploads or active tasks. Retry after they finish.")
                self.shared.db.execute("INSERT INTO images(chat,id,mime,data,created,accessed) VALUES(?,?,?,?,?,?)",
                                       (chat_id, identity, mime, data, now, now))
            else:
                self.shared.db.execute("UPDATE images SET created=?,accessed=? WHERE chat=? AND id=?", (now, now, chat_id, identity))
        return {"id": identity, "mimeType": mime, "size": len(data)}

    def get(self, chat_id: str, identity: str) -> ImageInput:
        required_text(chat_id, "chatId", 256)
        image_id(identity)
        with self.shared.lock, self.shared.db:
            self.shared.chat(chat_id)
            row = self.shared.db.execute("SELECT mime,data FROM images WHERE chat=? AND id=?",
                                         (chat_id, identity)).fetchone()
            if row is not None:
                self.shared.db.execute("UPDATE images SET accessed=? WHERE chat=? AND id=?", (time.time(), chat_id, identity))
        if row is None:
            raise ControlError("NOT_FOUND", "Image is unavailable or was automatically cleared from this chat's cache.")
        return ImageInput(row[1], row[0])

    def resolve(self, chat_id: str, value: Any) -> dict[str, Any] | None:
        value = image_metadata(value)
        if value is None:
            return None
        identity = image_id(value.get("id"))
        image = self.get(chat_id, identity)
        metadata = {"id": identity, "mimeType": image.mime_type, "size": len(image.data)}
        if value != metadata:
            raise ControlError("INVALID_ARGS", "Image metadata does not match the uploaded image.")
        return metadata

    def retain(self, chat_id: str, image: dict[str, Any] | None) -> None:
        if image is not None:
            with self.shared.lock, self.shared.db:
                changed = self.shared.db.execute("UPDATE images SET used=1 WHERE chat=? AND id=?", (chat_id, image["id"]))
                if changed.rowcount != 1:
                    raise ControlError("NOT_FOUND", "Image expired before acceptance. Upload it again.")
                key = (chat_id, image["id"])
                self._pins[key] = self._pins.get(key, 0) + 1

    def release(self, chat_id: str, image: dict[str, Any] | None) -> None:
        if image is not None:
            with self.shared.lock:
                key = (chat_id, image["id"])
                count = self._pins.get(key, 0)
                if count > 1:
                    self._pins[key] = count - 1
                else:
                    self._pins.pop(key, None)

    def project_update(self, chat_id: str, event: dict[str, Any]) -> dict[str, Any]:
        projected = without_inline_images(event)
        update = event.get("update", event)
        if not isinstance(update, dict):
            return projected
        content = update.get("content")
        if update.get("sessionUpdate") != "user_message_chunk" or not isinstance(content, dict):
            return projected
        encoded = content.get("data")
        if content.get("type") != "image" or not isinstance(encoded, str) or len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
            return projected
        try:
            identity = hashlib.sha256(base64.b64decode(encoded, validate=True)).hexdigest()
        except (binascii.Error, ValueError):
            return projected
        with self.shared.lock:
            row = self.shared.db.execute("SELECT mime,length(data) FROM images WHERE chat=? AND id=?", (chat_id, identity)).fetchone()
        if row is not None and row[0] == content.get("mimeType"):
            target = projected.get("update", projected)
            target["content"] = {"type": "text", "text": ""}
            target["image"] = {"id": identity, "mimeType": row[0], "size": row[1]}
        return projected


def without_inline_images(value: Any) -> Any:
    if isinstance(value, dict):
        if value.get("type") in {"image", "blob"} and "data" in value:
            return {"type": "text", "text": "[Image content omitted from provider replay; use the original attachment.]"}
        return {key: without_inline_images(item) for key, item in value.items()}
    if isinstance(value, list):
        return [without_inline_images(item) for item in value]
    return value
