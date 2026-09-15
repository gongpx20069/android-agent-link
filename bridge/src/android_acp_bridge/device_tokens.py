from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import subprocess
import threading
from pathlib import Path


class DeviceTokenStoreError(RuntimeError):
    pass


class DeviceTokenStore:
    """Stores only hashes; an omitted path is deliberately memory-only for embedders."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._hashes: set[str] = set()
        if path is not None:
            try:
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                self._make_private(path.parent)
                if path.exists():
                    self._make_private(path)
                    data = json.loads(path.read_text(encoding="utf-8"))
                    hashes = data["hashes"]
                    if data.get("version") != 1 or not isinstance(hashes, list) or not all(
                        isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes
                    ):
                        raise ValueError("Invalid token store")
                    self._hashes = set(hashes)
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                raise DeviceTokenStoreError("Cannot securely read device token store; check its permissions and format.") from None

    def issue(self) -> str:
        token = "dev_" + secrets.token_urlsafe(32)
        with self._lock:
            hashes = self._hashes | {self._hash(token)}
            if self.path is not None:
                self._save(hashes)
            self._hashes = hashes
        return token

    def contains(self, token: str) -> bool:
        with self._lock:
            return self._hash(token) in self._hashes

    @staticmethod
    def _hash(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    @staticmethod
    def _make_private(path: Path) -> None:
        if path.is_symlink():
            raise ValueError("Token storage cannot be a symlink")
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes

            identity = subprocess.run(
                ["whoami", "/user", "/fo", "csv", "/nh"], capture_output=True, text=True, check=True,
            ).stdout
            match = re.search(r"S-1-\d+(?:-\d+)+", identity)
            if match is None:
                raise ValueError("Cannot determine user SID")
            advapi = ctypes.WinDLL("advapi32", use_last_error=True)
            descriptor = ctypes.c_void_p()
            convert = advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW
            convert.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
            convert.restype = wintypes.BOOL
            flags = "OICI" if path.is_dir() else ""
            if not convert(f"D:P(A;{flags};FA;;;{match[0]})", 1, ctypes.byref(descriptor), None):
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                set_security = advapi.SetFileSecurityW
                set_security.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
                set_security.restype = wintypes.BOOL
                if not set_security(str(path), 0x80000004, descriptor):
                    raise ctypes.WinError(ctypes.get_last_error())
            finally:
                free = ctypes.WinDLL("kernel32").LocalFree
                free.argtypes = [ctypes.c_void_p]
                free.restype = ctypes.c_void_p
                free(descriptor)
        else:
            path.chmod(0o700 if path.is_dir() else 0o600)

    def _save(self, hashes: set[str]) -> None:
        assert self.path is not None
        staging = self.path.with_name(self.path.name + "." + secrets.token_hex(8) + ".pending")
        try:
            fd = os.open(staging, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                self._make_private(staging)
                json.dump({"version": 1, "hashes": sorted(hashes)}, stream)
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
            raise DeviceTokenStoreError("Cannot persist device token securely; pairing was not completed.") from None
        finally:
            try:
                staging.unlink(missing_ok=True)
            except OSError:
                pass
