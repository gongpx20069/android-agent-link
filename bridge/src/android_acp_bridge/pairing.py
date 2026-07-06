from __future__ import annotations

import base64
import json
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any


PAIRING_DEEP_LINK_SCHEME = "acpclient://pair"


class PairingDecision(StrEnum):
    APPROVED = "approved"
    DENIED = "denied"


@dataclass(frozen=True)
class PairingToken:
    pairing_id: str
    pairing_token: str
    expires_at: datetime

    @property
    def is_expired(self) -> bool:
        return datetime.now(UTC) >= self.expires_at


@dataclass(frozen=True)
class PairingPayload:
    version: int
    type: str
    machine_name: str
    endpoint: str
    pairing_id: str
    pairing_token: str
    expires_at: str
    bridge_fingerprint: str

    def to_wire(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "type": self.type,
            "machineName": self.machine_name,
            "endpoint": self.endpoint,
            "pairingId": self.pairing_id,
            "pairingToken": self.pairing_token,
            "expiresAt": self.expires_at,
            "bridgeFingerprint": self.bridge_fingerprint,
        }


class PairingStore:
    def __init__(self) -> None:
        self._tokens: dict[str, PairingToken] = {}

    def create(self, ttl: timedelta = timedelta(minutes=5)) -> PairingToken:
        token = PairingToken(
            pairing_id="pair_" + secrets.token_urlsafe(16),
            pairing_token=secrets.token_urlsafe(32),
            expires_at=datetime.now(UTC) + ttl,
        )
        self._tokens[token.pairing_id] = token
        return token

    def redeem(self, pairing_id: str, pairing_token: str) -> bool:
        token = self._tokens.get(pairing_id)
        if token is None or token.is_expired or not secrets.compare_digest(token.pairing_token, pairing_token):
            return False

        del self._tokens[pairing_id]
        return True

    def purge_expired(self) -> None:
        expired_ids = [pairing_id for pairing_id, token in self._tokens.items() if token.is_expired]
        for pairing_id in expired_ids:
            del self._tokens[pairing_id]


def build_pairing_payload(
    *,
    machine_name: str,
    endpoint: str,
    token: PairingToken,
    bridge_fingerprint: str,
) -> PairingPayload:
    return PairingPayload(
        version=1,
        type="acp-bridge-pairing",
        machine_name=machine_name,
        endpoint=endpoint,
        pairing_id=token.pairing_id,
        pairing_token=token.pairing_token,
        expires_at=token.expires_at.isoformat().replace("+00:00", "Z"),
        bridge_fingerprint=bridge_fingerprint,
    )


def encode_pairing_deep_link(payload: PairingPayload) -> str:
    raw = json.dumps(payload.to_wire(), separators=(",", ":"), sort_keys=True).encode("utf-8")
    encoded = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    return f"{PAIRING_DEEP_LINK_SCHEME}?data={encoded}"


def render_terminal_qr(value: str, *, ansi: bool = False) -> str:
    import qrcode
    from qrcode.constants import ERROR_CORRECT_L

    qr = qrcode.QRCode(border=2, error_correction=ERROR_CORRECT_L)
    qr.add_data(value)
    qr.make(fit=True)
    return _qr_to_terminal(qr.get_matrix(), ansi=ansi)


def _qr_to_terminal(matrix: list[list[bool]], *, ansi: bool) -> str:
    rows: list[str] = []
    for row_index in range(0, len(matrix), 2):
        top_row = matrix[row_index]
        bottom_row = matrix[row_index + 1] if row_index + 1 < len(matrix) else [False] * len(top_row)
        if ansi:
            rows.append("".join(_ansi_half_block(top, bottom) for top, bottom in zip(top_row, bottom_row)) + "\033[0m")
        else:
            rows.append("".join(_unicode_half_block(top, bottom) for top, bottom in zip(top_row, bottom_row)))
    return "\n".join(rows)


def _unicode_half_block(top: bool, bottom: bool) -> str:
    if top and bottom:
        return "█"
    if top:
        return "▀"
    if bottom:
        return "▄"
    return " "


def _ansi_half_block(top: bool, bottom: bool) -> str:
    foreground = 232 if top else 255
    background = 232 if bottom else 255
    return f"\033[38;5;{foreground}m\033[48;5;{background}m▀"
