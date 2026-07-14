from __future__ import annotations

import base64
import hashlib
import json
import queue
import struct
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from .runtime import BridgeRuntime, InvalidPairingTokenError, PairingDeniedError, parse_device_info


class BridgeHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], runtime: BridgeRuntime) -> None:
        self.runtime = runtime
        super().__init__(server_address, BridgeRequestHandler)


class BridgeRequestHandler(BaseHTTPRequestHandler):
    server: BridgeHTTPServer

    def do_GET(self) -> None:
        parsed = urlparse(self.path)

        if parsed.path == "/health":
            self._send_json(HTTPStatus.OK, self.server.runtime.health_response())
            return
        if parsed.path == "/agents":
            self._send_json(HTTPStatus.OK, self.server.runtime.agents_response())
            return
        if parsed.path == "/workspaces":
            self._send_json(HTTPStatus.OK, self.server.runtime.workspaces_response())
            return
        if parsed.path == "/ws":
            self._handle_websocket(parsed.query)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/pairing/redeem":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        body = self._read_json_body()
        if body is None:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json"})
            return

        device = parse_device_info(body.get("device"))
        pairing_id = body.get("pairingId")
        pairing_token = body.get("pairingToken")
        if device is None or not isinstance(pairing_id, str) or not isinstance(pairing_token, str):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_pairing_request"})
            return

        try:
            response = self.server.runtime.redeem_pairing(pairing_id, pairing_token, device)
        except PairingDeniedError as exc:
            self._send_json(HTTPStatus.FORBIDDEN, {"error": "pairing_denied", "message": str(exc)})
            return
        except InvalidPairingTokenError as exc:
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid_pairing_token", "message": str(exc)})
            return

        self._send_json(HTTPStatus.OK, response)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")

    def _read_json_body(self) -> Any | None:
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if content_length <= 0:
            return None
        raw = self.rfile.read(content_length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _handle_websocket(self, query: str) -> None:
        token = parse_qs(query).get("token", [None])[0]
        if token is None or not self.server.runtime.is_device_token_valid(token):
            self.send_response(HTTPStatus.FORBIDDEN.value)
            self.end_headers()
            return

        key = self.headers.get("Sec-WebSocket-Key")
        upgrade = self.headers.get("Upgrade", "").lower()
        if upgrade != "websocket" or not key:
            self.send_response(HTTPStatus.BAD_REQUEST.value)
            self.end_headers()
            return

        accept = _websocket_accept(key)
        self.send_response(HTTPStatus.SWITCHING_PROTOCOLS.value)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()

        response_queue: queue.Queue[dict[str, Any] | None] = queue.Queue()
        request_queue: queue.Queue[Any | None] = queue.Queue()
        stopped = threading.Event()
        write_lock = threading.Lock()

        def emit(response: dict[str, Any]) -> None:
            if not stopped.is_set():
                response_queue.put(response)
        setattr(emit, "_connection_id", id(response_queue))

        def write_responses() -> None:
            while not stopped.is_set():
                try:
                    response = response_queue.get(timeout=WEBSOCKET_HEARTBEAT_SECONDS)
                except queue.Empty:
                    response = {"type": "bridge.heartbeat"}
                if response is None:
                    return
                if not _write_websocket_json(self.wfile, response, write_lock=write_lock):
                    stopped.set()
                    return

        def dispatch_requests() -> None:
            while not stopped.is_set():
                request = request_queue.get()
                if request is None:
                    return
                for response in self.server.runtime.websocket_responses(request, emit=emit):
                    emit(response)

        threading.Thread(target=write_responses, daemon=True).start()
        threading.Thread(target=dispatch_requests, daemon=True).start()
        try:
            while not stopped.is_set():
                message = _read_websocket_text(self.rfile, self.wfile, write_lock=write_lock)
                if message is None:
                    return
                try:
                    payload = json.loads(message)
                except json.JSONDecodeError:
                    payload = message
                emit({"type": "bridge.accepted", "chatId": payload.get("chatId") if isinstance(payload, dict) else None})
                request_queue.put(payload)
        finally:
            stopped.set()
            request_queue.put(None)
            response_queue.put(None)


def run_server(runtime: BridgeRuntime) -> None:
    server = BridgeHTTPServer((runtime.config.host, runtime.config.port), runtime)
    print(f"Bridge server listening on http://{runtime.config.host}:{runtime.config.port}")
    server.serve_forever()


WEBSOCKET_HEARTBEAT_SECONDS = 5


def _websocket_accept(key: str) -> str:
    digest = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def _read_websocket_text(
    stream: Any,
    output_stream: Any,
    write_lock: threading.Lock | None = None,
) -> str | None:
    while True:
        frame = _read_websocket_frame(stream)
        if frame is None:
            return None
        opcode, payload = frame
        if opcode == 0x1:
            return payload.decode("utf-8")
        if opcode == 0x8:
            _write_websocket_frame(output_stream, 0x8, payload, write_lock=write_lock)
            return None
        if opcode == 0x9:
            _write_websocket_frame(output_stream, 0xA, payload, write_lock=write_lock)
            continue
        if opcode == 0xA:
            continue
        return None


def _read_websocket_frame(stream: Any) -> tuple[int, bytes] | None:
    header = stream.read(2)
    if len(header) < 2:
        return None
    first, second = header
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        extended = stream.read(2)
        if len(extended) < 2:
            return None
        length = struct.unpack("!H", extended)[0]
    elif length == 127:
        extended = stream.read(8)
        if len(extended) < 8:
            return None
        length = struct.unpack("!Q", extended)[0]

    mask = stream.read(4) if masked else b""
    if masked and len(mask) < 4:
        return None
    payload = stream.read(length)
    if len(payload) < length:
        return None
    if masked:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return opcode, payload


def _write_websocket_json(
    stream: Any,
    payload: dict[str, Any],
    write_lock: threading.Lock | None = None,
) -> bool:
    try:
        _write_websocket_text(stream, json.dumps(payload, separators=(",", ":")), write_lock=write_lock)
        return True
    except OSError:
        return False


def _write_websocket_text(
    stream: Any,
    message: str,
    write_lock: threading.Lock | None = None,
) -> None:
    _write_websocket_frame(stream, 0x1, message.encode("utf-8"), write_lock=write_lock)


def _write_websocket_frame(
    stream: Any,
    opcode: int,
    payload: bytes,
    write_lock: threading.Lock | None = None,
) -> None:
    length = len(payload)
    if length < 126:
        header = struct.pack("!BB", 0x80 | opcode, length)
    elif length < 65536:
        header = struct.pack("!BBH", 0x80 | opcode, 126, length)
    else:
        header = struct.pack("!BBQ", 0x80 | opcode, 127, length)
    if write_lock is None:
        stream.write(header + payload)
        stream.flush()
        return
    with write_lock:
        stream.write(header + payload)
        stream.flush()
