from __future__ import annotations

import base64
import errno
import hashlib
import json
import queue
import socket
import struct
import sys
import threading
import sqlite3
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse
from .device_tokens import DeviceTokenStoreError
from .account_pairing import AccountPairingError
from .attachments import MAX_IMAGE_BYTES, error_status
from .shared_state import ControlError

from .runtime import BridgeRuntime, InvalidPairingTokenError, PairingDeniedError, parse_device_info


class BridgeHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], runtime: BridgeRuntime) -> None:
        self.runtime = runtime
        super().__init__(server_address, BridgeRequestHandler)


class BridgeRequestHandler(BaseHTTPRequestHandler):
    server: BridgeHTTPServer

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path in {"/attachments", "/attachments/capabilities"}:
            self._attachment_request(parsed)
            return

        if parsed.path == "/health":
            self._send_json(HTTPStatus.OK, self.server.runtime.health_response())
            return
        if parsed.path == "/agents":
            self._send_json(HTTPStatus.OK, self.server.runtime.agents_response())
            return
        if parsed.path == "/workspaces":
            self._send_json(HTTPStatus.OK, self.server.runtime.public_workspaces_response())
            return
        if parsed.path == "/ws":
            self._handle_websocket(parsed.query)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/attachments":
            self._attachment_request(parsed)
            return
        if parsed.path in {"/pairing/request", "/pairing/status"}:
            body = self._read_json_body()
            try:
                handler = (
                    self.server.runtime.account_pairing_request
                    if parsed.path == "/pairing/request"
                    else self.server.runtime.account_pairing_status
                )
                self._send_json(HTTPStatus.OK, handler(body))
            except AccountPairingError as exc:
                self._send_json(HTTPStatus(exc.status), {"error": exc.code})
            except DeviceTokenStoreError:
                self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "device_token_store_unavailable"})
            return
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
        except DeviceTokenStoreError as exc:
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "device_token_store_unavailable", "message": str(exc)})
            return

        self._send_json(HTTPStatus.OK, response)

    def log_message(self, format: str, *args: Any) -> None:
        # Never format the raw request, URL, query or header-bearing parser errors.
        self.server.runtime.console.message("debug", "http.diagnostic")

    def _attachment_request(self, parsed: Any) -> None:
        runtime = self.server.runtime
        try:
            authorization = self.headers.get("Authorization", "")
            if not authorization.startswith("Bearer ") or not runtime.is_device_token_valid(authorization[7:]):
                self.close_connection = True
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            query = parse_qs(parsed.query)
            chat_id = query.get("chatId", [""])[0]
            if parsed.path == "/attachments/capabilities":
                self._send_json(HTTPStatus.OK, runtime.image_capabilities(chat_id))
            elif self.command == "POST":
                if not runtime.image_capabilities(chat_id)["imageSupported"]:
                    raise ControlError("UNSUPPORTED", "This session does not currently declare image support. Select a vision model or wait for connection.")
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    size = 0
                if not 0 < size <= MAX_IMAGE_BYTES or self.headers.get("Transfer-Encoding"):
                    raise ControlError("LIMIT_EXCEEDED", "A Content-Length between 1 byte and 5 MiB is required.")
                self.connection.settimeout(30)
                data = self.rfile.read(size)
                if len(data) != size:
                    raise ControlError("INVALID_ARGS", "Incomplete image upload.")
                self._send_json(HTTPStatus.OK, runtime.attachments.put(chat_id, self.headers.get("Content-Type", ""), data))
            else:
                image = runtime.attachments.get(chat_id, query.get("id", [""])[0])
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", image.mime_type)
                self.send_header("Content-Length", str(len(image.data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                self.wfile.write(image.data)
        except ControlError as exc:
            self.close_connection = True
            self._send_json(HTTPStatus(error_status(exc)), {"error": exc.code, "message": str(exc)})
        except (DeviceTokenStoreError, sqlite3.Error):
            self.close_connection = True
            runtime.console.message("error", "attachment.storage_failed")
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "attachment_storage_unavailable"})
        except TimeoutError:
            self.close_connection = True
            self._send_json(HTTPStatus.REQUEST_TIMEOUT, {"error": "attachment_upload_timeout"})

    def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
        status = int(code) if str(code).isdigit() else 0
        self.server.runtime.console.http(self.command, status)

    def _read_json_body(self) -> Any | None:
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if content_length <= 0 or content_length > 16_384:
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
        self.send_header("Cache-Control", "no-store")
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

        runtime = self.server.runtime
        response_queue: queue.Queue[bytes] = queue.Queue(maxsize=WEBSOCKET_QUEUE_MESSAGES)
        request_queue: queue.Queue[Any] = queue.Queue(maxsize=128)
        connection_id = f"{id(response_queue):x}"
        runtime.console.message("info", "connection.opened", transport="websocket", connection=connection_id)
        stopped = threading.Event()
        write_lock = threading.Lock()
        state_lock = threading.Lock()
        pending_bytes = 0
        write_started: float | None = None
        close_reason = "peer_closed"
        close_error = 0
        last_chat_id = "-"

        def stop_connection(reason: str, error: OSError | None = None) -> None:
            nonlocal close_reason, close_error, pending_bytes
            with state_lock:
                if stopped.is_set():
                    return
                close_reason = reason
                close_error = (getattr(error, "winerror", None) or error.errno or 0) if error else 0
                stopped.set()
                while True:
                    try:
                        pending_bytes -= len(response_queue.get_nowait())
                    except queue.Empty:
                        break
            # Wake both a blocked recv and send without touching other connections or tasks.
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError as exc:
                runtime.console.message("debug", "connection.shutdown_failed",
                                        connection=connection_id, error=exc.errno or 0)
            # makefile() keeps socket.close() from closing the handle. On Windows,
            # shutdown alone may leave another thread blocked inside recv_into().
            handle = self.connection.detach()
            if handle != -1:
                try:
                    socket.close(handle)
                except OSError as exc:
                    runtime.console.message("warning", "connection.close_failed",
                                            connection=connection_id, error=exc.errno or 0)

        def emit(response: dict[str, Any]) -> None:
            nonlocal pending_bytes
            if stopped.is_set():
                return
            raw = json.dumps(response, separators=(",", ":")).encode("utf-8")
            with state_lock:
                if stopped.is_set():
                    return
                overflow = pending_bytes + len(raw) > WEBSOCKET_QUEUE_BYTES
                if not overflow:
                    try:
                        response_queue.put_nowait(raw)
                        pending_bytes += len(raw)
                    except queue.Full:
                        overflow = True
            if overflow:
                stop_connection("send_queue_full")
        setattr(emit, "_connection_id", id(response_queue))

        def write_frame(opcode: int, raw: bytes, accounted: int = 0) -> bool:
            nonlocal write_started, pending_bytes
            with write_lock:
                try:
                    with state_lock:
                        if stopped.is_set():
                            return False
                        write_started = time.monotonic()
                    # Bound the OS send itself: closing a handle from another thread
                    # does not always interrupt a blocking Winsock send immediately.
                    timeout = (int(WEBSOCKET_WRITE_SECONDS * 1000) if sys.platform == "win32" else
                               struct.pack("ll", int(WEBSOCKET_WRITE_SECONDS),
                                           int(WEBSOCKET_WRITE_SECONDS % 1 * 1_000_000)))
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDTIMEO, timeout)
                    _write_websocket_frame(self.wfile, opcode, raw)
                    return True
                except OSError as exc:
                    timed_out = (isinstance(exc, TimeoutError) or
                                 exc.errno in {errno.EAGAIN, errno.EWOULDBLOCK, errno.ETIMEDOUT} or
                                 getattr(exc, "winerror", None) == 10060)
                    stop_connection("write_timeout" if timed_out else "write_failed", exc)
                    return False
                finally:
                    with state_lock:
                        write_started = None
                        pending_bytes -= accounted

        def watch_writes() -> None:
            while not stopped.wait(WEBSOCKET_WATCH_SECONDS):
                with state_lock:
                    expired = write_started is not None and time.monotonic() - write_started >= WEBSOCKET_WRITE_SECONDS
                if expired:
                    stop_connection("write_timeout")
                    return

        def write_responses() -> None:
            heartbeat_at = time.monotonic() + WEBSOCKET_HEARTBEAT_SECONDS
            while not stopped.is_set():
                try:
                    raw = response_queue.get(timeout=WEBSOCKET_WATCH_SECONDS)
                except queue.Empty:
                    if time.monotonic() >= heartbeat_at:
                        if not write_frame(0x1, b'{"type":"bridge.heartbeat"}'):
                            return
                        heartbeat_at = time.monotonic() + WEBSOCKET_HEARTBEAT_SECONDS
                    continue
                if not write_frame(0x1, raw, accounted=len(raw)):
                    return
                heartbeat_at = time.monotonic() + WEBSOCKET_HEARTBEAT_SECONDS

        def dispatch_requests() -> None:
            try:
                while not stopped.is_set():
                    try:
                        request = request_queue.get(timeout=WEBSOCKET_WATCH_SECONDS)
                    except queue.Empty:
                        continue
                    if stopped.is_set():
                        return
                    for response in runtime.websocket_responses(request, emit=emit):
                        emit(response)
            finally:
                stop_connection("request_dispatch_ended")
                # A request already in progress may have subscribed after the reader detached.
                runtime.detach(emit)

        writer = threading.Thread(target=write_responses, name=f"ws-writer-{connection_id}", daemon=True)
        watcher = threading.Thread(target=watch_writes, name=f"ws-watch-{connection_id}", daemon=True)
        writer.start()
        watcher.start()
        threading.Thread(target=dispatch_requests, name=f"ws-dispatch-{connection_id}", daemon=True).start()
        try:
            while not stopped.is_set():
                message = _read_websocket_text(self.rfile, self.wfile, send_control=write_frame)
                if message is None:
                    return
                try:
                    payload = json.loads(message)
                except json.JSONDecodeError:
                    payload = message
                chat_id = payload.get("chatId") if isinstance(payload, dict) else None
                if isinstance(chat_id, str) and chat_id != last_chat_id:
                    last_chat_id = chat_id
                    runtime.console.message("info", "connection.bound", connection=connection_id, chat=last_chat_id)
                emit({"type": "bridge.accepted", "chatId": payload.get("chatId") if isinstance(payload, dict) else None})
                try:
                    request_queue.put_nowait(payload)
                except queue.Full:
                    stop_connection("request_queue_full")
        except OSError as exc:
            stop_connection("read_failed", exc)
        finally:
            stop_connection("peer_closed")
            runtime.detach(emit)
            writer.join(timeout=2)
            watcher.join(timeout=2)
            runtime.console.message("info" if close_reason == "peer_closed" else "warning", "connection.closed",
                                    transport="websocket", connection=connection_id, chat=last_chat_id,
                                    reason=close_reason, error=close_error)


def run_server(runtime: BridgeRuntime) -> None:
    with BridgeHTTPServer((runtime.config.host, runtime.config.port), runtime) as server:
        runtime.console.message("info", "server.listening", port=server.server_port)
        server.serve_forever()


WEBSOCKET_HEARTBEAT_SECONDS = 5
WEBSOCKET_QUEUE_MESSAGES = 1024
WEBSOCKET_QUEUE_BYTES = 16 * 1024 * 1024
WEBSOCKET_WRITE_SECONDS = 30
WEBSOCKET_WATCH_SECONDS = 1


def _websocket_accept(key: str) -> str:
    digest = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def _read_websocket_text(
    stream: Any,
    output_stream: Any,
    write_lock: threading.Lock | None = None,
    send_control: Callable[[int, bytes], bool] | None = None,
) -> str | None:
    while True:
        frame = _read_websocket_frame(stream)
        if frame is None:
            return None
        opcode, payload = frame
        if opcode == 0x1:
            return payload.decode("utf-8")
        if opcode == 0x8:
            if send_control is not None:
                send_control(0x8, payload)
            else:
                _write_websocket_frame(output_stream, 0x8, payload, write_lock=write_lock)
            return None
        if opcode == 0x9:
            if send_control is not None:
                if not send_control(0xA, payload):
                    return None
            else:
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
