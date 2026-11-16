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
from urllib.parse import parse_qs, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from .control import control_response
from .device_tokens import DeviceTokenStore, DeviceTokenStoreError
from .shared_state import ControlError, required_text

MAX_BODY = 512_000
MAX_RESPONSE = 4_000_000
ASSETS = Path(__file__).with_name("web")


class WebError(Exception):
    def __init__(self, status: int, message: str):
        self.status = status
        super().__init__(message)


def read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    try:
        size = int(handler.headers.get("Content-Length", "0"))
    except ValueError:
        raise WebError(400, "Invalid Content-Length.") from None
    if not 0 < size <= MAX_BODY or handler.headers.get("Transfer-Encoding"):
        raise WebError(413, "A bounded JSON body with Content-Length is required.")
    handler.connection.settimeout(15)
    raw = handler.rfile.read(size)
    if len(raw) != size:
        raise WebError(400, "Incomplete request.")
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


def remote_json(machine: dict[str, Any], path: str, body: dict[str, Any], *, pairing: bool = False) -> dict[str, Any]:
    try:
        endpoint = check_transport(machine["endpoint"])
        headers = {"Content-Type": "application/json", **machine.get("headers", {})}
        headers["Host"] = urlsplit(machine["endpoint"]).netloc
        if not pairing:
            headers["Authorization"] = "Bearer " + machine["token"]
        request = Request(endpoint + path, json.dumps(body).encode(), headers, method="POST")
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=60 if pairing else 20) as response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise WebError(502, "Remote response exceeds the limit; details are incomplete.")
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ValueError("Not an object")
        return result
    except HTTPError as error:
        status = error.code
        error.close()
        if status == 404:
            raise WebError(502, "Remote API unavailable. Update that computer's stdlib Server.") from None
        if status in {401, 403}:
            raise WebError(502, "Remote authorization denied or expired. Re-pair that computer.") from None
        raise WebError(502, f"Remote HTTP {status}; redirects are not followed. Check that computer before retrying.") from None
    except (URLError, OSError, ValueError, HTTPException):
        raise WebError(502, "Remote connection/response failed. If an action was submitted, its outcome is uncertain; do not resend automatically.") from None


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
            if self.command == "GET" and path in {"/", "/app.js", "/style.css"}:
                name = "index.html" if path == "/" else path[1:]
                raw = (ASSETS / name).read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", {"index.html": "text/html; charset=utf-8", "app.js": "text/javascript; charset=utf-8",
                                                  "style.css": "text/css; charset=utf-8"}[name])
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
                self.end_headers()
                self.wfile.write(raw)
                return
            body = read_json(self) if self.command == "POST" else {}
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
            if path == "/api/machines" and self.command == "GET":
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
            send_json(self, 400, {"error": str(error)})
        except CookieError:
            send_json(self, 400, {"error": "Invalid browser session cookie."})
        except (DeviceTokenStoreError, sqlite3.Error, OSError):
            self.server.runtime.console.message("error", "web.request_failed")
            self.close_connection = True
            send_json(self, 503, {"error": "Web storage/transport failed. An action may have been accepted; inspect its state before retrying."})
