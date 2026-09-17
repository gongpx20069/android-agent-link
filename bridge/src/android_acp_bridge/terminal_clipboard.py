"""Explicit local clipboard writes. Content is passed on stdin, never as shell code."""
from __future__ import annotations

import shutil
import subprocess
import sys


def copy_text(text: str) -> None:
    if sys.platform == "win32":
        executable = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
        command = [executable, "-NoProfile", "-NonInteractive", "-STA", "-Command",
                   "$ErrorActionPreference='Stop'; "
                   "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                   "$text=[Console]::In.ReadToEnd(); Set-Clipboard -Value $text"] if executable else None
    elif sys.platform == "darwin":
        executable = shutil.which("pbcopy")
        command = [executable] if executable else None
    else:
        executable = shutil.which("wl-copy") or shutil.which("xclip") or shutil.which("xsel")
        if executable:
            name = executable.rsplit("/", 1)[-1]
            command = [executable] + (["-selection", "clipboard"] if name == "xclip" else
                                      ["--clipboard", "--input"] if name == "xsel" else [])
        else:
            command = None
    if command is None:
        raise RuntimeError("No local clipboard utility found. Use terminal text selection instead.")
    try:
        result = subprocess.run(command, input=text.encode("utf-8"), stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Local clipboard unavailable or timed out. Use terminal text selection instead.") from error
    if result.returncode:
        # Utility stderr can contain the copied content. Never echo it into the transcript.
        raise RuntimeError("Local clipboard write failed. Check the desktop/clipboard session.")
