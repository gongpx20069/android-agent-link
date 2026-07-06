from __future__ import annotations

import argparse
import sys

from .config import DEFAULT_PORT, default_config
from .pairing import PairingStore, build_pairing_payload, encode_pairing_deep_link, render_terminal_qr
from .runtime import BridgeRuntime
from .stdlib_server import run_server
from .tailscale import TailscaleState, build_websocket_endpoint, get_status


def main(argv: list[str] | None = None) -> int:
    _configure_stdout()

    parser = argparse.ArgumentParser(prog="android-acp-bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)

    start_parser = subparsers.add_parser("start", help="Start the bridge server and print a pairing QR code")
    start_parser.add_argument("--host", default="127.0.0.1", help="Host to bind. Defaults to localhost.")
    start_parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to bind.")
    start_parser.add_argument("--workspace", help="Allowed workspace path. Defaults to the current directory.")
    start_parser.add_argument("--allow-non-tailscale", action="store_true", help="Allow localhost/manual endpoint when Tailscale is unavailable.")
    start_parser.add_argument("--auto-approve-pairing", action="store_true", help="Skip local pairing confirmation. Use only for tests or trusted local demos.")
    start_parser.add_argument("--server", choices=("stdlib", "fastapi"), default="stdlib", help="Server backend. Defaults to the standard-library backend.")

    subparsers.add_parser("tailscale-status", help="Print the detected Tailscale status")

    pairing_parser = subparsers.add_parser("pairing", help="Create and print a standalone pairing payload")
    pairing_parser.add_argument("--endpoint", required=True)
    pairing_parser.add_argument("--machine-name", default=None)
    pairing_parser.add_argument("--no-qr", action="store_true")

    args = parser.parse_args(argv)

    if args.command == "tailscale-status":
        return _print_tailscale_status()
    if args.command == "pairing":
        config = default_config()
        machine_name = args.machine_name or config.machine_name
        return _print_pairing(machine_name, args.endpoint, config.bridge_fingerprint, args.no_qr)
    if args.command == "start":
        return _start(args)

    parser.error("unknown command")
    return 2


def _print_tailscale_status() -> int:
    status = get_status()
    print(f"state: {status.state}")
    if status.backend_state:
        print(f"backend: {status.backend_state}")
    if status.preferred_endpoint_host:
        print(f"host: {status.preferred_endpoint_host}")
    if status.message:
        print(status.message)
    return 0 if status.state == TailscaleState.RUNNING else 1


def _start(args: argparse.Namespace) -> int:
    config = default_config(host=args.host, port=args.port, workspace=args.workspace)
    status = get_status()
    endpoint = build_websocket_endpoint(status, config.port)

    if endpoint is None:
        if not args.allow_non_tailscale:
            print(f"Tailscale is not ready: {status.state}", file=sys.stderr)
            if status.message:
                print(status.message, file=sys.stderr)
            print("Use --allow-non-tailscale for localhost/manual testing.", file=sys.stderr)
            return 1
        endpoint = f"ws://{config.host}:{config.port}"

    pairing_store = PairingStore()
    token = pairing_store.create()
    payload = build_pairing_payload(
        machine_name=config.machine_name,
        endpoint=endpoint,
        token=token,
        bridge_fingerprint=config.bridge_fingerprint,
    )
    deep_link = encode_pairing_deep_link(payload)

    print(f"Bridge endpoint: {endpoint}", flush=True)
    print(f"Pairing expires at: {payload.expires_at}", flush=True)
    print("Android pairing link:", flush=True)
    print(deep_link, flush=True)
    print("Android pairing QR:", flush=True)
    print(render_terminal_qr(deep_link, ansi=sys.stdout.isatty()), flush=True)

    runtime = BridgeRuntime(
        config=config,
        pairing_store=pairing_store,
        require_local_pairing_confirmation=not args.auto_approve_pairing,
    )
    if args.server == "fastapi":
        _run_fastapi(runtime)
    else:
        run_server(runtime)
    return 0


def _print_pairing(machine_name: str, endpoint: str, bridge_fingerprint: str, no_qr: bool) -> int:
    token = PairingStore().create()
    payload = build_pairing_payload(
        machine_name=machine_name,
        endpoint=endpoint,
        token=token,
        bridge_fingerprint=bridge_fingerprint,
    )
    deep_link = encode_pairing_deep_link(payload)
    print(deep_link)
    if not no_qr:
        print(render_terminal_qr(deep_link, ansi=sys.stdout.isatty()))
    return 0


def _run_fastapi(runtime: BridgeRuntime) -> None:
    try:
        import uvicorn

        from .server import create_app
    except ImportError as exc:
        raise SystemExit("FastAPI server backend requires: python -m pip install -r requirements-fastapi.txt") from exc

    uvicorn.run(create_app(runtime), host=runtime.config.host, port=runtime.config.port)


def _configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")


if __name__ == "__main__":
    raise SystemExit(main())
