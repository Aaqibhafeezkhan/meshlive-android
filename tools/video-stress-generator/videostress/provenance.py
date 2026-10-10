"""Fingerprint installed generator source to keep changed versions distinguishable."""

from functools import lru_cache
import hashlib
from pathlib import Path

from . import __version__


@lru_cache(maxsize=1)
def code_identity():
    files = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(Path(__file__).parent.glob("*.py"))}
    combined = hashlib.sha256("\n".join(f"{name}:{sha}" for name,sha in files.items()).encode()).hexdigest()
    return {"generator_version":__version__,"source_sha256":combined,"files":files}
