from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from android_acp_bridge.devtunnel import (
    DevTunnelAuthError,
    DevTunnelConflictError,
    create_or_reuse_tunnel,
    default_tunnel_id,
    ensure_devtunnel_login,
    ensure_devtunnel_cli,
    ensure_tunnel_port,
    issue_connect_token,
    parse_connect_token,
    parse_host_url,
    parse_list_tunnel_ids,
    parse_tunnel_id,
    setup_devtunnel,
    to_websocket_endpoint,
)


class DevTunnelTests(unittest.TestCase):
    def test_parse_host_url_prefers_requested_port(self) -> None:
        output = "Hosting port 4317 at https://abc123-4317.usw2.devtunnels.ms/"

        self.assertEqual(parse_host_url(output, 4317), "https://abc123-4317.usw2.devtunnels.ms")

    def test_parse_host_url_falls_back_to_any_devtunnel_url(self) -> None:
        output = "Available at https://abc123.usw2.devtunnels.ms/"

        self.assertEqual(parse_host_url(output, 4317), "https://abc123.usw2.devtunnels.ms")

    def test_parse_tunnel_id_from_show_output(self) -> None:
        output = "Tunnel service cluster    : jpe1\nTunnel ID             : agentlink.jpe1\nPorts                 : 0"

        self.assertEqual(parse_tunnel_id(output), "agentlink.jpe1")

    def test_parse_list_tunnel_ids(self) -> None:
        output = """
Found 2 tunnels.

Tunnel ID                           Host Connections     Labels                    Ports                Expiration                Description
agentlink.jpe1                      0                                              1                    29.4 days
agentlink-cpc-peixi-3hwbj.jpe1      0                                              0                    30 days
        """

        self.assertEqual(parse_list_tunnel_ids(output), ["agentlink.jpe1", "agentlink-cpc-peixi-3hwbj.jpe1"])

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
            return subprocess.CompletedProcess(args, 0, "Tunnel ID             : agentlink.jpe1", "")

        tunnel_id = create_or_reuse_tunnel("devtunnel", "agentlink", runner)

        self.assertEqual(tunnel_id, "agentlink.jpe1")
        self.assertEqual(commands, [["devtunnel", "show", "agentlink"]])

    def test_create_or_reuse_tunnel_creates_missing_tunnel(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[1] == "show":
                stdout = "Tunnel ID             : agentlink.jpe1" if len(commands) > 2 else ""
                return subprocess.CompletedProcess(args, 1 if len(commands) == 1 else 0, stdout, "")
            return subprocess.CompletedProcess(args, 0, "", "")

        tunnel_id = create_or_reuse_tunnel("devtunnel", "agentlink", runner)

        self.assertEqual(tunnel_id, "agentlink.jpe1")
        self.assertEqual(commands, [["devtunnel", "show", "agentlink"], ["devtunnel", "list"], ["devtunnel", "create", "agentlink"], ["devtunnel", "show", "agentlink"]])

    def test_create_or_reuse_tunnel_uses_visible_cluster_id_from_list(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[1] == "show":
                return subprocess.CompletedProcess(args, 1, "", "Tunnel not found")
            if args[1] == "list":
                return subprocess.CompletedProcess(args, 0, "agentlink-cpc-peixi-3hwbj.jpe1      0      0      30 days", "")
            raise AssertionError(f"unexpected command: {args}")

        tunnel_id = create_or_reuse_tunnel("devtunnel", "agentlink-cpc-peixi-3hwbj", runner)

        self.assertEqual(tunnel_id, "agentlink-cpc-peixi-3hwbj.jpe1")
        self.assertEqual(commands, [["devtunnel", "show", "agentlink-cpc-peixi-3hwbj"], ["devtunnel", "list"]])

    def test_create_or_reuse_tunnel_does_not_trust_zero_exit_without_tunnel_id(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[1] == "show":
                return subprocess.CompletedProcess(args, 0, "Tunnel not found in jpe1: agentlink-cpc-peixi-3hwbj", "")
            if args[1] == "list":
                return subprocess.CompletedProcess(args, 0, "agentlink-cpc-peixi-3hwbj.jpe1      0      0      30 days", "")
            raise AssertionError(f"unexpected command: {args}")

        tunnel_id = create_or_reuse_tunnel("devtunnel", "agentlink-cpc-peixi-3hwbj", runner)

        self.assertEqual(tunnel_id, "agentlink-cpc-peixi-3hwbj.jpe1")

    def test_setup_devtunnel_uses_resolved_cluster_tunnel_id(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[1:] == ["user", "show"]:
                return subprocess.CompletedProcess(args, 0, "User: test", "")
            if args[1:] == ["show", "agentlink"]:
                return subprocess.CompletedProcess(args, 0, "Tunnel ID             : agentlink.jpe1", "")
            if args[1:4] == ["port", "create", "agentlink.jpe1"]:
                return subprocess.CompletedProcess(args, 0, "", "")
            if args[1:] == ["token", "agentlink.jpe1", "--scopes", "connect"]:
                return subprocess.CompletedProcess(args, 0, "aaa.bbb.ccc", "")
            raise AssertionError(f"unexpected command: {args}")

        import android_acp_bridge.devtunnel as devtunnel

        original_start_host = devtunnel.start_devtunnel_host
        try:
            devtunnel.start_devtunnel_host = lambda **kwargs: type(  # type: ignore[assignment]
                "FakeHost",
                (),
                {
                    "config": type(
                        "FakeConfig",
                        (),
                        {
                            "connect_token": kwargs["connect_token"],
                            "websocket_endpoint": "wss://agentlink-4317.jpe1.devtunnels.ms",
                        },
                    )(),
                    "process": None,
                    "output_thread": None,
                    "stop": lambda self: None,
                },
            )()
            setup_devtunnel(bridge_root=Path("."), tunnel_id="agentlink", local_port=4317, cli_path="devtunnel", runner=runner)
        finally:
            devtunnel.start_devtunnel_host = original_start_host  # type: ignore[assignment]

        self.assertIn(["devtunnel", "port", "create", "agentlink.jpe1", "-p", "4317", "--protocol", "http"], commands)
        self.assertIn(["devtunnel", "token", "agentlink.jpe1", "--scopes", "connect"], commands)

    def test_create_or_reuse_tunnel_reports_anonymous_create_denial(self) -> None:
        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if args[1] == "show":
                return subprocess.CompletedProcess(args, 1, "", "")
            return subprocess.CompletedProcess(args, 3, "", "Unauthorized tunnel creation access: Anonymous does not have 'create' access scope.")

        with self.assertRaises(DevTunnelAuthError):
            create_or_reuse_tunnel("devtunnel", "agentlink", runner)

    def test_create_or_reuse_tunnel_reports_id_conflict(self) -> None:
        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if args[1] == "show":
                return subprocess.CompletedProcess(args, 1, "", "")
            return subprocess.CompletedProcess(args, 1, "", "Conflict with existing entity. Retry tunnel operation.")

        with self.assertRaises(DevTunnelConflictError):
            create_or_reuse_tunnel("devtunnel", "agentlink", runner)

    def test_default_tunnel_id_is_agentlink_prefixed(self) -> None:
        self.assertEqual(default_tunnel_id(), "agentlink")

    def test_ensure_devtunnel_login_treats_anonymous_show_as_logged_out(self) -> None:
        commands: list[list[str]] = []

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            return subprocess.CompletedProcess(args, 0, "Anonymous", "")

        original_run = subprocess.run
        try:
            subprocess.run = lambda args, check=False: subprocess.CompletedProcess(args, 0)  # type: ignore[assignment]
            ensure_devtunnel_login("devtunnel", runner)
        finally:
            subprocess.run = original_run  # type: ignore[assignment]

        self.assertEqual(commands, [["devtunnel", "user", "show"]])

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

            self.assertEqual(ensure_devtunnel_cli(bridge_root, command_exists=lambda _command: None), str(local_cli.resolve()))

    def test_ensure_devtunnel_cli_prefers_repo_root_tool(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            bridge_root = repo_root / "bridge"
            bridge_root.mkdir()
            repo_cli = repo_root / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
            repo_cli.write_text("", encoding="utf-8")

            self.assertEqual(ensure_devtunnel_cli(bridge_root, command_exists=lambda _command: None), str(repo_cli.resolve()))

    def test_ensure_devtunnel_cli_prefers_repo_root_over_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            bridge_root = repo_root / "bridge"
            bridge_root.mkdir()
            repo_cli = repo_root / ("devtunnel.exe" if sys.platform == "win32" else "devtunnel")
            repo_cli.write_text("", encoding="utf-8")

            self.assertEqual(
                ensure_devtunnel_cli(bridge_root, command_exists=lambda _command: "devtunnel.exe"),
                str(repo_cli.resolve()),
            )


if __name__ == "__main__":
    unittest.main()
