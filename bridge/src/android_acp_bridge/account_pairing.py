from __future__ import annotations

import secrets
import threading
import time
from typing import Any, Callable


class AccountPairingError(RuntimeError):
    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


class AccountPairing:
    """A bounded console approval, independent of the caller's claimed identity."""

    def __init__(
        self,
        issue: Callable[[], dict[str, str]],
        confirm: Callable[[str, str], bool],
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._issue = issue
        self._confirm = confirm
        self._clock = clock
        self._lock = threading.Lock()
        self._attempt: dict[str, Any] | None = None
        self._confirming = False
        self._next_request = 0.0

    def request(self, device_name: str) -> dict[str, Any]:
        now = self._clock()
        with self._lock:
            if self._confirming or now < self._next_request:
                raise AccountPairingError(429, "pairing_busy")
            if self._attempt is not None and now < self._attempt["expiresAt"] / 1000:
                raise AccountPairingError(429, "pairing_busy")
            attempt = {
                "requestId": secrets.token_urlsafe(24),
                "pollToken": secrets.token_urlsafe(32),
                "confirmationCode": f"{secrets.randbelow(1_000_000):06d}",
                "expiresAt": int((now + 120) * 1000),
                "status": "pending",
            }
            self._attempt = attempt
            self._confirming = True
            self._next_request = now + 10
            result = dict(attempt)
        threading.Thread(target=self._approve, args=(attempt, device_name), daemon=True).start()
        return result

    def _approve(self, attempt: dict[str, Any], device_name: str) -> None:
        try:
            accepted = self._confirm(device_name, attempt["confirmationCode"])
        except (EOFError, OSError):
            accepted = False
        finally:
            with self._lock:
                self._confirming = False
        with self._lock:
            if self._attempt is not attempt:
                return
            if self._clock() * 1000 >= attempt["expiresAt"]:
                attempt["status"] = "expired"
            else:
                attempt["status"] = "approved" if accepted else "denied"

    def poll(self, request_id: str, poll_token: str) -> dict[str, Any]:
        with self._lock:
            attempt = self._attempt
            if (
                attempt is None
                or not secrets.compare_digest(attempt["requestId"], request_id)
                or not secrets.compare_digest(attempt["pollToken"], poll_token)
            ):
                raise AccountPairingError(401, "invalid_pairing_request")
            if self._clock() * 1000 >= attempt["expiresAt"]:
                attempt["status"] = "expired"
            status = attempt["status"]
            if status == "approved":
                # Do not consume on a storage failure, and never issue before approval.
                result = {"status": status, **self._issue()}
                self._attempt = None
                return result
            return {"status": status}
