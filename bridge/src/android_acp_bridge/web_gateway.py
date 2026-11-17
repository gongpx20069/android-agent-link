"""Loopback Web client. Agent execution remains owned by BridgeRuntime."""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import threading
import time
from http.client import HTTPException
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from .control import control_response
from .attachments import MAX_IMAGE_BYTES, IMAGE_MIME_TYPES, error_status, image_id, image_metadata
from .device_tokens import DeviceTokenStore, DeviceTokenStoreError
from .shared_state import ControlError, required_text

MAX_BODY = 512_000
MAX_RESPONSE = 4_000_000
ASSETS = Path(__file__).with_name("web")


class WebError(Exception):
    def __init__(self, status: int, message: str):
        self.status = status
        super().__init__(message)


def read_body(handler: BaseHTTPRequestHandler, limit: int) -> bytes:
    try:
        size = int(handler.headers.get("Content-Length", "0"))
    except ValueError:
        raise WebError(400, "Invalid Content-Length.") from None
    if not 0 < size <= limit or handler.headers.get("Transfer-Encoding"):
        raise WebError(413, f"A body with Content-Length between 1 and {limit} bytes is required.")
    handler.connection.settimeout(15)
    raw = handler.rfile.read(size)
    if len(raw) != size:
        raise WebError(400, "Incomplete request.")
    return raw


def read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    raw = read_body(handler, MAX_BODY)
    try:
        body = json.loads(raw)
    except (ValueError, UnicodeError):
        raise WebError(400, "Invalid JSON.") from None
    if not isinstance(body, dict):
        raise WebError(400, "A JSON object is required.")
    return body


def send_json(handler: BaseHTTPRequestHandler, status: int, body: dict[str, Any], cookie: str | None = None) -> None:
    raw = json.dumps(body, ensure_ascii=True).encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(raw)))
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("X-Content-Type-Options", "nosniff")
    if cookie:
        handler.send_header("Set-Cookie", cookie)
    handler.end_headers()
    handler.wfile.write(raw)


def serve_control(handler: BaseHTTPRequestHandler, runtime: Any) -> None:
    """The existing revocable device credential also protects HTTP control."""
    try:
        auth = handler.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or not runtime.is_device_token_valid(auth[7:]):
            raise WebError(401, "Device authorization required.")
        send_json(handler, 200, control_response(runtime, read_json(handler))[0])
    except WebError as error:
        handler.close_connection = True
        send_json(handler, error.status, {"error": str(error)})
    except (DeviceTokenStoreError, sqlite3.Error):
        runtime.console.message("error", "web.control_storage_failed")
        send_json(handler, 503, {"error": "Control storage unavailable; inspect task state before retrying."})
    except TimeoutError:
        handler.close_connection = True
        send_json(handler, 408, {"error": "Request timed out."})


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


def endpoint_url(value: Any) -> str:
    raw = required_text(value, "endpoint", 2048)
    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError:
        raise WebError(400, "Invalid endpoint address or port.") from None
    scheme = {"ws": "http", "wss": "https"}.get(parsed.scheme, parsed.scheme)
    if (scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in {"", "/", "/ws"}
            or any(ch.isspace() or ord(ch) < 32 for ch in raw) or "\\" in raw or port == 0):
        raise WebError(400, "Use a credential-free HTTP(S)/WS(S) bridge address without a query or path.")
    return urlunsplit((scheme, parsed.netloc, "", "", ""))


def check_transport(endpoint: str) -> str:
    parsed = urlsplit(endpoint)
    if parsed.scheme == "https":
        return endpoint
    addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 80, type=socket.SOCK_STREAM)
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if ip.is_global or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            raise WebError(400, "Unencrypted connections are limited to loopback/private networks. Use HTTPS for relays.")
    if not addresses:
        raise WebError(502, "Computer address could not be resolved.")
    # Pin plain-HTTP requests to the checked address instead of resolving twice.
    address = addresses[0][4][0]
    host = f"[{address}]" if ":" in address else address
    return f"http://{host}:{parsed.port or 80}"


def remote_request(machine: dict[str, Any], path: str, body: bytes | None, mime: str = "application/json",
                   *, pairing: bool = False, attachment: bool = False) -> tuple[bytes, str]:
    try:
        endpoint = check_transport(machine["endpoint"])
        headers = {"Content-Type": mime, **machine.get("headers", {})}
        headers["Host"] = urlsplit(machine["endpoint"]).netloc
        if not pairing:
            headers["Authorization"] = "Bearer " + machine["token"]
        request = Request(endpoint + path, body, headers, method="GET" if body is None else "POST")
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=60 if pairing else 20) as response:
            limit = MAX_IMAGE_BYTES if attachment else MAX_RESPONSE
            raw = response.read(limit + 1)
            if len(raw) > limit:
                raise WebError(502, "Remote response exceeds the limit; details are incomplete.")
            return raw, response.headers.get_content_type()
    except HTTPError as error:
        status = error.code
        error.close()
        if attachment and status in {400, 404, 409, 413}:
            messages = {400: "Remote image request was rejected. Check PNG/JPEG format and pixel dimensions.",
                        404: "Image or chat unavailable, cache cleared, or remote image API needs an update.",
                        409: "This remote session does not declare image support. Connect a vision-capable model first.",
                        413: "Remote image limit or cache quota exceeded. Use an image up to 5 MiB / 16 million pixels."}
            raise WebError(status, messages[status]) from None
        if status == 404:
            raise WebError(502, "Remote API unavailable. Update that computer's stdlib Server.") from None
        if status in {401, 403}:
            raise WebError(502, "Remote authorization denied or expired. Re-pair that computer.") from None
        raise WebError(502, f"Remote HTTP {status}; redirects are not followed. Check that computer before retrying.") from None
    except (URLError, OSError, ValueError, HTTPException):
        raise WebError(502, "Remote connection/response failed. If an action was submitted, its outcome is uncertain; do not resend automatically.") from None


def json_object(raw: bytes) -> dict[str, Any]:
    try:
        result = json.loads(raw)
        if isinstance(result, dict):
            return result
    except (ValueError, UnicodeError):
        pass
    raise WebError(502, "Remote Server returned invalid JSON.")


def remote_json(machine: dict[str, Any], path: str, body: dict[str, Any], *, pairing: bool = False) -> dict[str, Any]:
    raw, _ = remote_request(machine, path, json.dumps(body).encode(), pairing=pairing)
    return json_object(raw)


def relay_headers(value: Any) -> dict[str, str]:
    if not isinstance(value, dict) or any(key.lower() != "x-tunnel-authorization" for key in value):
        raise WebError(400, "Only X-Tunnel-Authorization is supported as a relay header.")
    result = {}
    for text in value.values():
        if not isinstance(text, str) or not text or len(text) > 8192 or any(ord(ch) < 32 for ch in text):
            raise WebError(400, "Invalid relay authorization.")
        result["X-Tunnel-Authorization"] = text
    return result


class MachineStore:
    def __init__(self, path: Path | None):
        self.path = path
        self.lock = threading.RLock()
        self.items: list[dict[str, Any]] = []
        if path is not None:
            try:
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                DeviceTokenStore._make_private(path.parent)
                if path.exists():
                    DeviceTokenStore._make_private(path)
                    items = json.loads(path.read_text(encoding="utf-8"))
                    if not isinstance(items, list) or len(items) > 50:
                        raise ValueError("Invalid machine store")
                    for item in items:
                        self.validate(item)
                    if len({item["id"] for item in items}) != len(items):
                        raise ValueError("Duplicate machine IDs")
                    self.items = items
            except (OSError, ValueError, KeyError, TypeError, ControlError, WebError, subprocess.SubprocessError):
                raise DeviceTokenStoreError("Cannot securely load Web computer credentials.") from None

    @staticmethod
    def validate(item: dict[str, Any]) -> None:
        if not isinstance(item, dict):
            raise WebError(400, "Invalid computer entry.")
        identity = required_text(item.get("id"), "machineId", 128)
        if identity == "local":
            raise WebError(400, "The local computer cannot be replaced.")
        item["id"] = identity
        item["name"] = required_text(item.get("name"), "name", 100)
        token = required_text(item.get("token"), "token", 8192)
        if any(ord(ch) < 33 or ord(ch) > 126 for ch in token):
            raise WebError(400, "Invalid device credential.")
        item["token"] = token
        item["endpoint"] = endpoint_url(item.get("endpoint"))
        item["headers"] = relay_headers(item.get("headers", {}))

    def public(self) -> list[dict[str, str]]:
        with self.lock:
            return [{key: item[key] for key in ("id", "name", "endpoint")} for item in self.items]

    def get(self, identity: str) -> dict[str, Any]:
        with self.lock:
            item = next((item for item in self.items if item["id"] == identity), None)
            if item is None:
                raise WebError(404, "Computer not found.")
            return {**item, "headers": dict(item["headers"])}

    def update(self, item: dict[str, Any] | None, identity: str) -> None:
        with self.lock:
            if item is not None:
                self.validate(item)
                if item["id"] != identity:
                    raise WebError(400, "Computer identity does not match its registry entry.")
            items = [old for old in self.items if old["id"] != identity] + ([item] if item else [])
            if len(items) > 50:
                raise WebError(400, "At most 50 remote computers are supported.")
            if self.path:
                staging = self.path.with_name(self.path.name + "." + secrets.token_hex(8) + ".pending")
                try:
                    with os.fdopen(os.open(staging, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as stream:
                        DeviceTokenStore._make_private(staging)
                        json.dump(items, stream)
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(staging, self.path)
                    if os.name != "nt":
                        directory = os.open(self.path.parent, os.O_RDONLY)
                        try:
                            os.fsync(directory)
                        finally:
                            os.close(directory)
                except (OSError, ValueError, subprocess.SubprocessError):
                    raise DeviceTokenStoreError("Credential persistence failed; inspect private storage before retrying.") from None
                finally:
                    try:
                        staging.unlink(missing_ok=True)
                    except OSError:
                        raise DeviceTokenStoreError("Credential staging cleanup failed; inspect the private storage directory.") from None
            self.items = items


class WebServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, runtime: Any, port: int = 0, store: Path | None = None):
        self.runtime = runtime
        self.machines = MachineStore(store)
        self.access_code = secrets.token_urlsafe(24)
        self.code_hash = hashlib.sha256(self.access_code.encode()).digest()
        self.sessions: dict[str, float] = {}
        self.attempts: list[float] = []
        self.lock = threading.Lock()
        super().__init__(("127.0.0.1", port), WebHandler)
        self.origin = f"http://127.0.0.1:{self.server_port}"

    def control(self, machine_id: str, body: dict[str, Any]) -> dict[str, Any]:
        if machine_id == "local":
            return control_response(self.runtime, body)[0]
        result = remote_json(self.machines.get(machine_id), "/control", body)
        if result.get("type") != "control.result" or result.get("status") not in {"ok", "error"}:
            raise WebError(502, "Remote Server returned an invalid control response.")
        return result


class WebHandler(BaseHTTPRequestHandler):
    server: WebServer

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_: Any) -> None:
        self.server.runtime.console.message("debug", "web.http")

    def do_GET(self) -> None:
        self.dispatch()

    def do_POST(self) -> None:
        self.dispatch()

    def dispatch(self) -> None:
        try:
            if self.headers.get("Host") != self.server.origin.removeprefix("http://"):
                raise WebError(403, "Unexpected Host.")
            if self.command == "POST" and (self.headers.get("Origin") != self.server.origin
                                          or self.headers.get("X-AgentLink-Web") != "1"):
                raise WebError(403, "Same-origin Web requests are required.")
            path = urlsplit(self.path).path
            if self.command == "GET" and path in {"/", "/app.js", "/style.css", "/agentlink.svg"}:
                name = "index.html" if path == "/" else path[1:]
                raw = (ASSETS / name).read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", {"index.html": "text/html; charset=utf-8", "app.js": "text/javascript; charset=utf-8",
                                                  "style.css": "text/css; charset=utf-8", "agentlink.svg": "image/svg+xml"}[name])
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
                self.end_headers()
                self.wfile.write(raw)
                return
            body = read_json(self) if self.command == "POST" and path != "/api/attachments" else {}
            if path == "/api/login" and self.command == "POST":
                with self.server.lock:
                    now = time.monotonic()
                    self.server.attempts = [attempt for attempt in self.server.attempts if now - attempt < 60]
                    if len(self.server.attempts) >= 10:
                        raise WebError(429, "Too many login attempts. Wait one minute.")
                    self.server.attempts.append(now)
                    code = body.get("code")
                    if not isinstance(code, str) or not secrets.compare_digest(hashlib.sha256(code.encode()).digest(), self.server.code_hash):
                        raise WebError(401, "Incorrect access code.")
                    self.server.sessions = {key: expiry for key, expiry in self.server.sessions.items() if expiry > now}
                    if len(self.server.sessions) >= 16:
                        raise WebError(429, "Too many browser sessions. Sign out or restart the Web entry.")
                    session = secrets.token_urlsafe(32)
                    self.server.sessions[session] = now + 8 * 3600
                send_json(self, 200, {"ok": True}, f"agentlink_web={session}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800")
                return
            cookies = SimpleCookie()
            cookies.load(self.headers.get("Cookie", ""))
            session = cookies["agentlink_web"].value if "agentlink_web" in cookies else ""
            with self.server.lock:
                if self.server.sessions.get(session, 0) <= time.monotonic():
                    raise WebError(401, "Sign in using the access code printed by this Server.")
            if path in {"/api/attachments", "/api/attachments/capabilities"}:
                self.attachment_request(path)
            elif path == "/api/machines" and self.command == "GET":
                send_json(self, 200, {"machines": [{"id": "local", "name": self.server.runtime.config.machine_name,
                    "endpoint": "local"}] + self.server.machines.public()})
            elif path == "/api/control" and self.command == "POST":
                machine = required_text(body.get("machineId"), "machineId", 128)
                request = body.get("request")
                if not isinstance(request, dict):
                    raise WebError(400, "Control request must be an object.")
                send_json(self, 200, self.server.control(machine, request))
            elif path == "/api/machines/save" and self.command == "POST":
                identity = body.get("id")
                identity = secrets.token_hex(16) if identity in (None, "") else required_text(identity, "machineId", 128)
                item = {key: body.get(key) for key in ("name", "endpoint", "token")}
                item.update(id=identity, headers=body.get("headers", {}))
                self.server.machines.validate(item)
                result = remote_json(item, "/control", {"action": "workspace.list"})
                if result.get("type") != "control.result" or result.get("status") != "ok":
                    raise WebError(502, "Remote control check failed. Connection was not saved.")
                self.server.machines.update(item, identity)
                send_json(self, 200, {"ok": True})
            elif path == "/api/machines/pair" and self.command == "POST":
                try:
                    link = urlsplit(required_text(body.get("link"), "link", 32768))
                    values = parse_qs(link.query)
                    if link.scheme != "acpclient" or link.netloc != "pair" or len(values.get("data", [])) != 1:
                        raise ValueError("Invalid link")
                    encoded = values["data"][0]
                    payload = json.loads(base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True))
                    if not isinstance(payload, dict) or payload.get("version") != 1 or payload.get("type") != "acp-bridge-pairing":
                        raise ValueError("Invalid payload")
                except (ValueError, UnicodeError):
                    raise WebError(400, "Invalid AgentLink pairing link.") from None
                item = {"id": secrets.token_hex(16), "name": required_text(payload.get("machineName"), "name", 100),
                        "endpoint": endpoint_url(payload.get("endpoint")), "headers": relay_headers(payload.get("headers", {}))}
                existing = [machine for machine in self.server.machines.public() if machine["endpoint"] == item["endpoint"]]
                if len(existing) > 1:
                    raise WebError(409, "Multiple connections use this endpoint. Remove duplicates before pairing again.")
                if existing:
                    item["id"] = existing[0]["id"]
                paired = remote_json(item, "/pairing/redeem", {
                    "pairingId": required_text(payload.get("pairingId"), "pairingId", 256),
                    "pairingToken": required_text(payload.get("pairingToken"), "pairingToken", 256),
                    "device": {"name": "AgentLink Web gateway", "platform": "web-gateway", "appVersion": "1"},
                }, pairing=True)
                item["token"] = paired.get("deviceToken")
                self.server.machines.validate(item)
                check = remote_json(item, "/control", {"action": "workspace.list"})
                if check.get("type") != "control.result" or check.get("status") != "ok":
                    raise WebError(502, "Pairing returned, but control is unavailable. Update the remote Server; do not reuse a consumed link.")
                self.server.machines.update(item, item["id"])
                send_json(self, 200, {"ok": True})
            elif path == "/api/machines/remove" and self.command == "POST":
                identity = required_text(body.get("id"), "id", 128)
                self.server.machines.get(identity)
                self.server.machines.update(None, identity)
                send_json(self, 200, {"ok": True})
            elif path == "/api/logout" and self.command == "POST":
                with self.server.lock:
                    self.server.sessions.pop(session, None)
                send_json(self, 200, {"ok": True}, "agentlink_web=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0")
            else:
                raise WebError(404, "Not found.")
        except WebError as error:
            self.close_connection = True
            send_json(self, error.status, {"error": str(error)})
        except ControlError as error:
            self.close_connection = True
            send_json(self, error_status(error), {"error": str(error), "code": error.code})
        except CookieError:
            send_json(self, 400, {"error": "Invalid browser session cookie."})
        except (DeviceTokenStoreError, sqlite3.Error, OSError):
            self.server.runtime.console.message("error", "web.request_failed")
            self.close_connection = True
            send_json(self, 503, {"error": "Web storage/transport failed. An action may have been accepted; inspect its state before retrying."})

    def attachment_request(self, path: str) -> None:
        query = parse_qs(urlsplit(self.path).query)
        machine_id = required_text(query.get("machineId", [""])[0], "machineId", 128)
        chat_id = required_text(query.get("chatId", [""])[0], "chatId", 256)
        capability = path.endswith("/capabilities")
        if capability and self.command != "GET":
            raise WebError(405, "Image capabilities require GET.")
        identity = image_id(query.get("id", [""])[0]) if self.command == "GET" and not capability else None
        mime = self.headers.get("Content-Type", "")
        data = None
        runtime = self.server.runtime
        machine = self.server.machines.get(machine_id) if machine_id != "local" else None
        if self.command == "POST":
            if mime not in IMAGE_MIME_TYPES:
                raise WebError(400, "Only PNG and JPEG images are supported.")
            if machine is None and not runtime.image_capabilities(chat_id)["imageSupported"]:
                raise WebError(409, "This session does not declare image support. Send an initial text message to connect, or select a vision-capable model.")
            data = read_body(self, MAX_IMAGE_BYTES)
        if machine is None:
            if capability:
                send_json(self, 200, runtime.image_capabilities(chat_id))
                return
            if data is not None:
                send_json(self, 200, runtime.attachments.put(chat_id, mime, data))
                return
            image = runtime.attachments.get(chat_id, identity)
            raw, content_type = image.data, image.mime_type
        else:
            remote_path = "/attachments/capabilities" if capability else "/attachments"
            remote_path += "?" + urlencode({"chatId": chat_id, **({"id": identity} if identity else {})})
            raw, content_type = remote_request(machine, remote_path, data, mime, attachment=True)
            if capability or data is not None:
                result = json_object(raw)
                if capability:
                    if result.get("version") != 1 or type(result.get("imageSupported")) is not bool:
                        raise WebError(502, "Remote Server returned invalid image capabilities.")
                else:
                    try:
                        metadata = image_metadata(result)
                    except ControlError:
                        raise WebError(502, "Remote Server returned invalid image metadata.") from None
                    if metadata is None or metadata["id"] != hashlib.sha256(data).hexdigest() or metadata["size"] != len(data) or metadata["mimeType"] != mime:
                        raise WebError(502, "Remote upload receipt does not match the selected image.")
                send_json(self, 200, result)
                return
            if content_type not in IMAGE_MIME_TYPES or not raw or hashlib.sha256(raw).hexdigest() != identity:
                raise WebError(502, "Remote image bytes do not match their identifier or MIME type.")
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(raw)
