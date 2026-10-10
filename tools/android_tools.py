"""Locate host Android tools without machine-specific paths."""
import os
from pathlib import Path
import shutil


def default_adb() -> str:
    """adb on PATH, else $ANDROID_HOME / $ANDROID_SDK_ROOT platform-tools, else plain "adb"."""
    found = shutil.which("adb")
    if found:
        return found
    name = "adb.exe" if os.name == "nt" else "adb"
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            candidate = Path(os.environ[key]) / "platform-tools" / name
            if candidate.is_file():
                return str(candidate)
    return "adb"
