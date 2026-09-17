from __future__ import annotations

from typing import Any, Awaitable, Callable

from fastapi import FastAPI, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from . import __version__
from .device_tokens import DeviceTokenStoreError
from .account_pairing import AccountPairingError
from .runtime import BridgeRuntime, DeviceInfo as RuntimeDeviceInfo, InvalidPairingTokenError, PairingDeniedError


class DeviceInfo(BaseModel):
    name: str = Field(min_length=1)
    platform: str = Field(min_length=1)
    app_version: str = Field(alias="appVersion", min_length=1)


class PairingRedeemRequest(BaseModel):
    pairing_id: str = Field(alias="pairingId", min_length=1)
    pairing_token: str = Field(alias="pairingToken", min_length=1)
    device: DeviceInfo


def create_app(runtime: BridgeRuntime) -> FastAPI:
    app = FastAPI(title="AgentLink Bridge", version=__version__)

    @app.middleware("http")
    async def log_http(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            runtime.console.http(request.method, status)

    @app.get("/health")
    def health() -> dict[str, Any]:
        return runtime.health_response()

    @app.get("/agents")
    def agents() -> dict[str, Any]:
        return runtime.agents_response()

    @app.get("/workspaces")
    def workspaces() -> dict[str, Any]:
        return runtime.workspaces_response()

    @app.post("/pairing/redeem")
    def redeem_pairing(request: PairingRedeemRequest) -> dict[str, str]:
        device = RuntimeDeviceInfo(
            name=request.device.name,
            platform=request.device.platform,
            app_version=request.device.app_version,
        )
        try:
            return runtime.redeem_pairing(request.pairing_id, request.pairing_token, device)
        except PairingDeniedError:
            raise HTTPException(status_code=403, detail="Pairing was denied on the developer machine.")
        except InvalidPairingTokenError:
            raise HTTPException(status_code=401, detail="Pairing token is invalid, expired, or already used.")
        except DeviceTokenStoreError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from None

    def account_pairing(body: dict[str, Any], request: bool) -> dict[str, Any]:
        try:
            return runtime.account_pairing_request(body) if request else runtime.account_pairing_status(body)
        except AccountPairingError as exc:
            raise HTTPException(status_code=exc.status, detail=exc.code) from None
        except DeviceTokenStoreError:
            raise HTTPException(status_code=503, detail="device_token_store_unavailable") from None

    @app.post("/pairing/request")
    def request_account_pairing(body: dict[str, Any]) -> dict[str, Any]:
        return account_pairing(body, True)

    @app.post("/pairing/status")
    def poll_account_pairing(body: dict[str, Any]) -> dict[str, Any]:
        return account_pairing(body, False)

    @app.websocket("/ws")
    async def websocket_endpoint(websocket: WebSocket) -> None:
        token = websocket.query_params.get("token")
        if token is None or not runtime.is_device_token_valid(token):
            runtime.console.message("warning", "connection.rejected", transport="websocket")
            await websocket.close(code=1008)
            return

        await websocket.accept()
        runtime.console.message("info", "connection.opened", transport="websocket")
        try:
            while True:
                message = await websocket.receive_json()
                await websocket.send_json({"type": "bridge.echo", "payload": message})
        except WebSocketDisconnect:
            return
        finally:
            runtime.console.message("info", "connection.closed", transport="websocket")

    return app
