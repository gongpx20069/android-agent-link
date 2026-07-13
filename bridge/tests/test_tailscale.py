from __future__ import annotations

import json
import subprocess
import sys
import unittest

from android_acp_bridge.tailscale import (
    TailscaleState,
    build_install_command,
    build_websocket_endpoint,
    default_runner,
    ensure_tailscale_ready,
    get_status,
    install_failure_guidance,
    install_guidance,
    parse_status,
)


class TailscaleTests(unittest.TestCase):
    def test_default_runner_decodes_utf8_output_independent_of_system_locale(self) -> None:
        completed = default_runner(
            [sys.executable, "-c", "import os; os.write(1, '安装完成'.encode('utf-8'))"],
            timeout=5,
        )

        self.assertEqual(completed.stdout, "安装完成")

    def test_running_status_prefers_dns_name(self) -> None:
        status = parse_status(
            {
                "BackendState": "Running",
                "Self": {
                    "TailscaleIPs": ["100.64.0.10"],
                    "DNSName": "devbox.tailnet.ts.net.",
                },
            }
        )

        self.assertEqual(status.state, TailscaleState.RUNNING)
        self.assertEqual(status.preferred_endpoint_host, "devbox.tailnet.ts.net")
        self.assertEqual(build_websocket_endpoint(status, 4317), "ws://devbox.tailnet.ts.net:4317")

    def test_needs_login_when_auth_url_exists(self) -> None:
        status = parse_status({"BackendState": "NeedsLogin", "AuthURL": "https://login.tailscale.com/a/test"})

        self.assertEqual(status.state, TailscaleState.NEEDS_LOGIN)
        self.assertEqual(status.auth_url, "https://login.tailscale.com/a/test")

    def test_stopped_when_not_running(self) -> None:
        status = parse_status({"BackendState": "Stopped", "Self": {"TailscaleIPs": []}})

        self.assertEqual(status.state, TailscaleState.STOPPED)
        self.assertIsNone(build_websocket_endpoint(status, 4317))

    def test_get_status_reports_missing_cli(self) -> None:
        status = get_status(command_exists=lambda _command: None)

        self.assertEqual(status.state, TailscaleState.CLI_MISSING)
        self.assertIn("requires Tailscale", status.message or "")

    def test_build_install_command_uses_winget_on_windows(self) -> None:
        command = build_install_command("Windows", lambda name: f"C:\\tools\\{name}.exe" if name == "winget" else None)

        self.assertEqual(command[:6], ["winget", "install", "--id", "Tailscale.Tailscale", "--exact", "--source"])

    def test_build_install_command_uses_official_script_on_linux(self) -> None:
        command = build_install_command("Linux", lambda name: f"/usr/bin/{name}" if name in {"curl", "sh"} else None)

        self.assertEqual(command, ["sh", "-c", "curl -fsSL https://tailscale.com/install.sh | sh"])

    def test_install_guidance_mentions_platform_package_manager(self) -> None:
        self.assertIn("winget install", install_guidance("Windows"))
        self.assertIn("brew install", install_guidance("Darwin"))
        self.assertIn("tailscale.com/install.sh", install_guidance("Linux"))

    def test_windows_policy_blocked_install_has_specific_guidance(self) -> None:
        guidance = install_failure_guidance("Windows", 1625, "组织策略正在阻止安装。请与管理员联系。")

        self.assertIn("organization policy", guidance)
        self.assertIn("company software portal", guidance)
        self.assertIn("will not bypass", guidance)

    def test_ensure_ready_installs_then_runs_tailscale_up(self) -> None:
        state = {"installed": False, "running": False}
        commands: list[list[str]] = []

        def command_exists(command: str) -> str | None:
            if command == "tailscale":
                return "tailscale" if state["installed"] else None
            if command == "winget":
                return "winget"
            return None

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[0] == "winget":
                state["installed"] = True
                return subprocess.CompletedProcess(args, 0, "", "")
            if args == ["tailscale", "up", "--qr"]:
                state["running"] = True
                return subprocess.CompletedProcess(args, 0, "https://login.tailscale.com/a/test", "")
            if args == ["tailscale", "status", "--json"]:
                payload = (
                    {"BackendState": "Running", "Self": {"TailscaleIPs": ["100.64.0.10"], "DNSName": "devbox.tailnet.ts.net."}}
                    if state["running"]
                    else {"BackendState": "NeedsLogin", "AuthURL": "https://login.tailscale.com/a/test"}
                )
                return subprocess.CompletedProcess(args, 0, json.dumps(payload), "")
            raise AssertionError(f"unexpected command: {args}")

        result = ensure_tailscale_ready(runner=runner, command_exists=command_exists, system="Windows", sleep=lambda _seconds: None)

        self.assertEqual(result.status.state, TailscaleState.RUNNING)
        self.assertIn(["winget", "install", "--id", "Tailscale.Tailscale", "--exact", "--source", "winget", "--accept-package-agreements", "--accept-source-agreements"], commands)
        self.assertIn(["tailscale", "up", "--qr"], commands)
        self.assertTrue(any("same Tailscale account" in step for step in result.steps))
        self.assertTrue(any("Waiting for Tailscale" in step for step in result.steps))

    def test_ensure_ready_reports_policy_blocked_winget_install(self) -> None:
        commands: list[list[str]] = []

        def command_exists(command: str) -> str | None:
            return "winget" if command == "winget" else None

        def runner(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            commands.append(args)
            if args[0] == "winget":
                return subprocess.CompletedProcess(args, 1625, "", "组织策略正在阻止安装。请与管理员联系。")
            raise AssertionError(f"unexpected command: {args}")

        result = ensure_tailscale_ready(runner=runner, command_exists=command_exists, system="Windows")

        self.assertEqual(result.status.state, TailscaleState.ERROR)
        self.assertIn("organization policy", result.status.message or "")
        self.assertIn(["winget", "install", "--id", "Tailscale.Tailscale", "--exact", "--source", "winget", "--accept-package-agreements", "--accept-source-agreements"], commands)


if __name__ == "__main__":
    unittest.main()
