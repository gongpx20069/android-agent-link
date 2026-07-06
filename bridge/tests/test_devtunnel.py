from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from android_acp_bridge.devtunnel import (
    create_or_reuse_tunnel,
    ensure_devtunnel_cli,
    ensure_tunnel_port,
    issue_connect_token,
    parse_connect_token,
    parse_host_url,
    to_websocket_endpoint,
)


class DevTunnelTests(unittest.TestCase):
    def test_parse_host_url_prefers_requested_port(self) -> None:
        output = "Hosting port 4317 at https://abc123-4317.usw2.devtunnels.ms/"

        self.assertEqual(parse_host_url(output, 4317), "https://abc123-4317.usw2.devtunnels.ms")

    def test_parse_host_url_falls_back_to_any_devtunnel_url(self) -> None:
        output = "Available at https://abc123.usw2.devtunnels.ms/"

        self.assertEqual(parse_host_url(output, 4317), "https://abc123.usw2.devtunnels.ms")

    def test_to_websocket_endpoint_converts_https(self) -> None:
        self.assertEqual(to_websocket_endpoint("https://abc123-4317.usw2.devtunnels.ms/"), "wss://abc123-4317.usw2.devtunnels.ms")

    def test_to_websocket_endpoint_rejects_non_https(self) -> None:
        with self.assertRaises(ValueError):
            to_websocket_endpoint("http://abc123-4317.usw2.devtunnels.ms")

    def test_parse_connect_token_extracts_jwt(self) -> None:
        token = "aaa.bbb.ccc"

        self.assertEqual(parse_connect_token(f"Tunnel access token:\n{token}\n"), token)

    def test_parse_connect_token_uses_last_non_empty_line(self) -> None:
        self.assertEqual(parse_connect_token("token:\nopaque-token\n"), "opaque-token")

    def test_create_or_reuse_tunnel_skips_existing_tunnel(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            return subprocess.CompletedProcess(args, 0, "", "")

        create_or_reuse_tunnel("devtunnel", "agentlink", runner)

        self.assertEqual(commands, [["devtunnel", "show", "agentlink"]])

    def test_create_or_reuse_tunnel_creates_missing_tunnel(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            return subprocess.CompletedProcess(args, 1 if args[1] == "show" else 0, "", "")

        create_or_reuse_tunnel("devtunnel", "agentlink", runner)

        self.assertEqual(commands, [["devtunnel", "show", "agentlink"], ["devtunnel", "create", "agentlink"]])

    def test_ensure_tunnel_port_ignores_existing_port(self) -> None:
        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            return subprocess.CompletedProcess(args, 1, "", "port already exists")

        ensure_tunnel_port("devtunnel", "agentlink", 4317, runner)

    def test_issue_connect_token_runs_connect_scope_command(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            return subprocess.CompletedProcess(args, 0, "aaa.bbb.ccc", "")

        self.assertEqual(issue_connect_token("devtunnel", "agentlink", runner), "aaa.bbb.ccc")
        self.assertEqual(commands, [["devtunnel", "token", "agentlink", "--scopes", "connect"]])

    def test_ensure_devtunnel_cli_uses_local_tool(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            bridge_root = Path(temp_dir)
            local_cli = bridge_root / ".tools" / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
            local_cli.parent.mkdir()
            local_cli.write_text("", encoding="utf-8")

            self.assertEqual(ensure_devtunnel_cli(bridge_root, command_exists=lambda _command: None), str(local_cli))


if __name__ == "__main__":
    unittest.main()
