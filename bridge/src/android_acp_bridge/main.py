from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

from .config import DEFAULT_PORT, default_config, default_device_token_store
from .console_log import ConsoleLog
from .device_tokens import DeviceTokenStoreError
from .devtunnel import DevTunnelAuthError, DevTunnelConflictError, DevTunnelHost, default_tunnel_id, setup_devtunnel
from .pairing import PairingStore, build_pairing_payload, encode_pairing_deep_link, render_terminal_qr
from .runtime import BridgeRuntime
from .stdlib_server import run_server
from .tailscale import TailscaleState, TailscaleStatus, build_websocket_endpoint, ensure_tailscale_ready, get_status


def main(argv: list[str] | None = None) -> int:
    _configure_stdout()

    parser = argparse.ArgumentParser(prog="android-acp-bridge")
    subparsers = parser.add_subparsers(dest="command", required=True)

    start_parser = subparsers.add_parser("start", help="Start the bridge server and print a pairing QR code")
    start_parser.add_argument("--transport", choices=("tailscale", "devtunnel", "local"), default="devtunnel", help="How Android reaches the bridge. Defaults to devtunnel.")
    start_parser.add_argument("--host", default=None, help="Host to bind. Defaults to the Tailscale IP in Tailscale mode.")
    start_parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to bind.")
    start_parser.add_argument("--device-token-store", type=Path, default=default_device_token_store(), help="Hash store in a dedicated user-private directory.")
    start_parser.add_argument("--pairing-endpoint", help="Endpoint to put in the Android pairing QR/link when a relay forwards to this bridge, e.g. wss://<id>-4317.devtunnels.ms.")
    start_parser.add_argument("--allow-non-tailscale", action="store_true", help="Allow localhost/manual endpoint when Tailscale is unavailable.")
    start_parser.add_argument("--no-tailscale-setup", action="store_true", help="Skip automatic Tailscale install/login and only report current status.")
    start_parser.add_argument("--devtunnel-id", default=default_tunnel_id(), help="Microsoft Dev Tunnel ID to create or reuse when --transport devtunnel is selected.")
    start_parser.add_argument("--devtunnel-cli", help="Path to devtunnel CLI. Defaults to PATH or bridge\\.tools\\devtunnel.exe.")
    start_parser.add_argument("--devtunnel-login", choices=("github", "microsoft"), help="Explicitly sign in with this provider to match the phone. Otherwise reuse the CLI account.")
    start_parser.add_argument("--auto-approve-pairing", action="store_true", help="Skip local pairing confirmation. Use only for tests or trusted local demos.")
    start_parser.add_argument("--server", choices=("stdlib", "fastapi"), default="stdlib", help="Server backend. Defaults to the standard-library backend.")
    start_parser.add_argument("--log-level", choices=("debug", "info", "warning", "error"), default="info", help="Console verbosity; debug includes event metadata, never message bodies.")
    start_parser.add_argument("--interactive", action="store_true", help="Enable AgentLink terminal chat alongside Android (stdlib backend; requires interactive extra and a terminal).")
    start_parser.add_argument("--connection-header", action="append", default=[], metavar="NAME=VALUE", help="Header Android must send when connecting through a relay, e.g. X-Tunnel-Authorization='tunnel <token>'.")

    subparsers.add_parser("tailscale-status", help="Print the detected Tailscale status")

    pairing_parser = subparsers.add_parser("pairing", help="Create and print a standalone pairing payload")
    pairing_parser.add_argument("--endpoint", required=True)
    pairing_parser.add_argument("--machine-name", default=None)
    pairing_parser.add_argument("--no-qr", action="store_true")
    pairing_parser.add_argument("--connection-header", action="append", default=[], metavar="NAME=VALUE", help="Header Android must send when connecting through a relay.")

    args = parser.parse_args(argv)

    if args.command == "tailscale-status":
        return _print_tailscale_status()
    if args.command == "pairing":
        config = default_config()
        machine_name = args.machine_name or config.machine_name
        return _print_pairing(machine_name, args.endpoint, config.bridge_fingerprint, args.no_qr, _parse_connection_headers(args.connection_header))
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
    terminal_client = None
    if args.interactive:
        if args.server != "stdlib":
            print("--interactive requires --server stdlib; the optional FastAPI backend does not run ACP chat.", file=sys.stderr)
            return 1
        if any(importlib.util.find_spec(module) is None for module in ("prompt_toolkit", "rich", "markdown_it")):
            print("Terminal chat requires: python -m pip install -r requirements-interactive.txt (run from bridge).", file=sys.stderr)
            return 1
        if not sys.stdin.isatty() or not sys.stdout.isatty():
            print("--interactive requires an interactive terminal for input and output; do not pipe or redirect it.", file=sys.stderr)
            return 1
        from .terminal import TerminalClient, run_interactive
        terminal_client = TerminalClient()
    devtunnel_host: DevTunnelHost | None = None
    connection_headers = _parse_connection_headers(args.connection_header)

    if args.transport == "devtunnel":
        bind_host = args.host or "127.0.0.1"
        endpoint = f"ws://{bind_host}:{args.port}"
        try:
            devtunnel_host = setup_devtunnel(
                bridge_root=Path(__file__).resolve().parents[2],
                tunnel_id=args.devtunnel_id,
                local_port=args.port,
                cli_path=args.devtunnel_cli,
                login_provider=args.devtunnel_login,
            )
        except (DevTunnelAuthError, DevTunnelConflictError) as exc:
            print(str(exc), file=sys.stderr)
            return 1
        pairing_endpoint = _validate_pairing_endpoint(args.pairing_endpoint) if args.pairing_endpoint else devtunnel_host.config.websocket_endpoint
        connection_headers.setdefault("X-Tunnel-Authorization", f"tunnel {devtunnel_host.config.connect_token}")
    elif args.transport == "local":
        bind_host = args.host or "127.0.0.1"
        endpoint = f"ws://{bind_host}:{args.port}"
        pairing_endpoint = _validate_pairing_endpoint(args.pairing_endpoint) if args.pairing_endpoint else endpoint
    else:
        status = get_status() if args.allow_non_tailscale or args.no_tailscale_setup else _prepare_tailscale()
        endpoint = build_websocket_endpoint(status, args.port)

        if endpoint is None:
            if not args.allow_non_tailscale:
                print(f"Tailscale is not ready: {status.state}", file=sys.stderr)
                if status.message:
                    print(status.message, file=sys.stderr)
                print("Tailscale transport requires a working Tailscale connection. Use the default Dev Tunnel transport or --transport local for localhost/manual testing.", file=sys.stderr)
                return 1
            bind_host = args.host or "127.0.0.1"
            endpoint = f"ws://{bind_host}:{args.port}"
        else:
            bind_host = args.host or status.tailscale_ips[0]

        pairing_endpoint = _validate_pairing_endpoint(args.pairing_endpoint) if args.pairing_endpoint else endpoint

    config = default_config(host=bind_host, port=args.port, device_token_store=args.device_token_store)

    pairing_store = PairingStore()
    token = pairing_store.create()
    payload = build_pairing_payload(
        machine_name=config.machine_name,
        endpoint=pairing_endpoint,
        token=token,
        bridge_fingerprint=config.bridge_fingerprint,
        headers=connection_headers,
    )
    deep_link = encode_pairing_deep_link(payload)

    print(f"Bridge bind endpoint: ws://{config.host}:{config.port}", flush=True)
    print(f"Android pairing endpoint: {pairing_endpoint}", flush=True)
    print(f"Pairing expires at: {payload.expires_at}", flush=True)
    print("Android pairing link:", flush=True)
    print(deep_link, flush=True)
    print("Android pairing QR:", flush=True)
    print(render_terminal_qr(deep_link, ansi=sys.stdout.isatty()), flush=True)

    console = ConsoleLog("error" if terminal_client is not None else args.log_level)
    try:
        runtime = BridgeRuntime(
            config=config,
            pairing_store=pairing_store,
            require_local_pairing_confirmation=not args.auto_approve_pairing,
            account_pairing_enabled=args.transport == "devtunnel",
            console=console,
            local_client=terminal_client,
        )
        if terminal_client is None:
            console.start()
        console.message("info", "server.starting", backend=args.server, port=config.port)
        if terminal_client is not None:
            run_interactive(runtime, terminal_client)
        elif args.server == "fastapi":
            _run_fastapi(runtime)
        else:
            run_server(runtime)
        return 0
    except DeviceTokenStoreError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    finally:
        console.close()
        console.message("info", "server.stopped")
        if devtunnel_host is not None:
            devtunnel_host.stop()


def _prepare_tailscale() -> TailscaleStatus:
    setup = ensure_tailscale_ready()
    for step in setup.steps:
        print(step, flush=True)
    return setup.status


def _print_pairing(machine_name: str, endpoint: str, bridge_fingerprint: str, no_qr: bool, headers: dict[str, str]) -> int:
    token = PairingStore().create()
    payload = build_pairing_payload(
        machine_name=machine_name,
        endpoint=endpoint,
        token=token,
        bridge_fingerprint=bridge_fingerprint,
        headers=headers,
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

    uvicorn.run(create_app(runtime), host=runtime.config.host, port=runtime.config.port, access_log=False, log_level="warning")


def _parse_connection_headers(values: list[str]) -> dict[str, str]:
    headers: dict[str, str] = {}
    for value in values:
        name, separator, header_value = value.partition("=")
        if not separator or not name.strip() or not header_value.strip():
            raise SystemExit(f"Invalid --connection-header value: {value}. Expected NAME=VALUE.")
        header_name = name.strip()
        if header_name.lower() != "x-tunnel-authorization":
            raise SystemExit("Only X-Tunnel-Authorization is currently supported as a pairing connection header.")
        headers[header_name] = header_value.strip()
    return headers


def _validate_pairing_endpoint(value: str) -> str:
    endpoint = value.strip().rstrip("/")
    if not endpoint.startswith(("ws://", "wss://")):
        raise SystemExit("--pairing-endpoint must start with ws:// or wss://.")
    return endpoint


def _configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")


if __name__ == "__main__":
    raise SystemExit(main())
