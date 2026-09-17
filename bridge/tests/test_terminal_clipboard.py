import subprocess
import unittest
from unittest.mock import patch, Mock

from android_acp_bridge.terminal_clipboard import copy_text


class ClipboardTests(unittest.TestCase):
    def test_windows_content_goes_to_stdin_not_executable_code(self):
        text = "# Reply\n  \u4e2d\u6587\n'; $(exit 1)\n"
        with patch("android_acp_bridge.terminal_clipboard.sys.platform", "win32"), \
                patch("android_acp_bridge.terminal_clipboard.shutil.which", return_value="powershell.exe"), \
                patch("android_acp_bridge.terminal_clipboard.subprocess.run", return_value=Mock(returncode=0)) as run:
            copy_text(text)
            args, kwargs = run.call_args
            self.assertNotIn(text, args[0])
            self.assertEqual(kwargs["input"], text.encode("utf-8"))
            self.assertNotIn("shell", kwargs)
            self.assertEqual(kwargs["timeout"], 10)

    def test_platform_utilities_and_missing_clipboard(self):
        for platform, name, flags in (
            ("darwin", "pbcopy", []), ("linux", "wl-copy", []),
            ("linux", "xclip", ["-selection", "clipboard"]), ("linux", "xsel", ["--clipboard", "--input"]),
        ):
            with self.subTest(name=name), patch("android_acp_bridge.terminal_clipboard.sys.platform", platform), \
                    patch("android_acp_bridge.terminal_clipboard.shutil.which", side_effect=lambda executable: "/bin/" + name if executable == name else None), \
                    patch("android_acp_bridge.terminal_clipboard.subprocess.run", return_value=Mock(returncode=0)) as run:
                copy_text("text")
                self.assertEqual(run.call_args.args[0], ["/bin/" + name] + flags)
        with patch("android_acp_bridge.terminal_clipboard.shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "No local clipboard"):
                copy_text("text")

    def test_failure_is_explicit_and_does_not_echo_private_stderr(self):
        with patch("android_acp_bridge.terminal_clipboard.shutil.which", return_value="utility"), \
                patch("android_acp_bridge.terminal_clipboard.subprocess.run", return_value=Mock(returncode=1, stderr=b"PRIVATE")):
            with self.assertRaisesRegex(RuntimeError, "write failed") as caught:
                copy_text("PRIVATE")
            self.assertNotIn("PRIVATE", str(caught.exception))
        with patch("android_acp_bridge.terminal_clipboard.shutil.which", return_value="utility"), \
                patch("android_acp_bridge.terminal_clipboard.subprocess.run", side_effect=subprocess.TimeoutExpired("utility", 10)):
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                copy_text("PRIVATE")
