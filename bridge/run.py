from __future__ import annotations

import os
import subprocess
import sys
import venv
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    bridge_root = Path(__file__).resolve().parent
    venv_root = bridge_root / ".venv"
    python = _venv_python(venv_root)
    install_marker = venv_root / ".android-acp-bridge-editable"
    pyproject = bridge_root / "pyproject.toml"
    requirements = bridge_root / "requirements.txt"

    if not python.exists():
        print("Creating bridge virtual environment...", flush=True)
        venv.EnvBuilder(with_pip=True).create(venv_root)

    if _needs_install(install_marker, [pyproject, requirements]):
        print("Installing android-acp-bridge from requirements.txt...", flush=True)
        subprocess.check_call(
            [
                str(python),
                "-m",
                "pip",
                "install",
                "--quiet",
                "--disable-pip-version-check",
                "-r",
                str(requirements),
            ],
            cwd=bridge_root,
        )
        install_marker.touch()

    if not args:
        args = ["start"]

    env = os.environ.copy()
    env.setdefault("PYTHONIOENCODING", "utf-8")
    return subprocess.call([str(python), "-m", "android_acp_bridge.main", *args], env=env)


def _venv_python(venv_root: Path) -> Path:
    if sys.platform == "win32":
        return venv_root / "Scripts" / "python.exe"
    return venv_root / "bin" / "python"


def _needs_install(install_marker: Path, dependency_files: list[Path]) -> bool:
    if not install_marker.exists():
        return True
    install_time = install_marker.stat().st_mtime
    return any(install_time < dependency_file.stat().st_mtime for dependency_file in dependency_files)


if __name__ == "__main__":
    raise SystemExit(main())
