"""Own temporary debug images explicitly; successful review consumes them, not evidence assets."""
import argparse
import contextlib
import hashlib
import json
import os
import re
import stat
import uuid
from pathlib import Path

from evidence_relocation import declared_junction


class DebugImages:
    """Lazy, exact-name ownership inside an output's .debug-images directory.

    Reserve before creating an agent image, register its finished bytes, then review
    or consume it. Failed reviews remain pending. Retention requires a useful reason.
    Nothing scans, imports or deletes other output files or source evidence.
    """

    def __init__(self, output):
        self.output = Path(os.path.abspath(output))
        self.folder = self.output / ".debug-images"
        self.index = self.output / "debug-image-lifecycle.json"

    @staticmethod
    def _guard(path):
        for item in reversed((path, *path.parents)):
            try:
                info = item.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & getattr(
                    stat, "FILE_ATTRIBUTE_REPARSE_POINT", 1024):
                if declared_junction(item):
                    continue
                raise ValueError(f"Debug image path cannot use a link or junction: {item}")

    def _path(self, name):
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", name) or \
                Path(name).suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp", ".bmp"} or \
                name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(
                    f"{prefix}{number}" for prefix in ("COM", "LPT") for number in range(1, 10))}:
            raise ValueError("Use a simple image filename, without directories or a reserved Windows name")
        path = self.folder / name
        self._guard(path)
        path.resolve().relative_to(self.folder.resolve())
        return path

    def _load(self):
        self._guard(self.index)
        if not self.index.exists():
            return {"schema": "debug-image-lifecycle-v1", "images": {}}
        value = json.loads(self.index.read_text(encoding="utf-8"))
        if value.get("schema") != "debug-image-lifecycle-v1" or not isinstance(value.get("images"), dict):
            raise ValueError("Unsupported debug image ownership index")
        return value

    def _save(self, value):
        self._guard(self.index)
        self.output.mkdir(parents=True, exist_ok=True)
        temporary = self.index.with_name(self.index.name + "." + uuid.uuid4().hex + ".tmp")
        try:
            with temporary.open("x", encoding="utf-8") as handle:
                handle.write(json.dumps(value, indent=2) + "\n")
            self._guard(self.index)
            temporary.replace(self.index)
        finally:
            if temporary.exists():
                temporary.unlink()

    @staticmethod
    def _fingerprint(path):
        if not path.is_file():
            raise ValueError(f"Reserved debug image has not been created: {path}")
        with path.open("rb") as handle:
            return {"sha256": hashlib.file_digest(handle, "sha256").hexdigest(), "bytes": path.stat().st_size}

    def reserve(self, name, purpose):
        if not isinstance(purpose, str) or not purpose.strip():
            raise ValueError("A temporary debug image needs a review purpose")
        path, value = self._path(name), self._load()
        if name in value["images"] or path.exists():
            raise ValueError("Debug image name is already owned or already exists; use a new name")
        self.folder.mkdir(parents=True, exist_ok=True)
        value["images"][name] = {"state": "PENDING", "purpose": purpose.strip(), "sha256": None}
        self._save(value)
        return path

    def register(self, name):
        path, value = self._path(name), self._load()
        item = value["images"].get(name)
        if not item or item["state"] not in {"PENDING", "CONSUMED", "RETAINED"}:
            raise ValueError("Reserve an agent-created debug image before registering its bytes")
        item.update(self._fingerprint(path))
        item["state"] = "PENDING"
        self._save(value)
        return path

    def _verified(self, name, value):
        path = self._path(name)
        item = value["images"].get(name)
        if not item or not item.get("sha256"):
            raise ValueError("Register the completed image before consuming or retaining it")
        if any(item.get(key) != actual for key, actual in self._fingerprint(path).items()):
            raise ValueError("Debug image changed after registration; review and explicitly register its new bytes")
        return path, item

    def consume(self, name):
        value = self._load()
        _, item = self._verified(name, value)
        if item["state"] not in {"PENDING", "RETAINED"}:
            raise ValueError("Only a pending or explicitly released retained image can be consumed")
        item["state"] = "CONSUMED"
        self._save(value)
        self.finalize()

    def retain(self, name, reason):
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError("Retention needs a visual comparison, decision or measurement purpose")
        value = self._load()
        _, item = self._verified(name, value)
        if item["state"] != "PENDING":
            raise ValueError("Only a pending debug image can be retained")
        item.update(state="RETAINED", retention_reason=reason.strip())
        self._save(value)

    @contextlib.contextmanager
    def review(self, name):
        """Delete after successful consumption; errors and interruptions keep pending bytes."""
        path, item = self._verified(name, self._load())
        if item["state"] != "PENDING":
            raise ValueError("Review requires a pending debug image")
        yield path
        if self._load()["images"][name]["state"] == "PENDING":
            self.consume(name)

    def finalize(self):
        """Finish exact consumed entries only. Pending and retained images are untouched."""
        self._guard(self.index)
        if not self.index.exists():
            return
        value = self._load()
        changed = False
        for name, item in value["images"].items():
            if item["state"] != "CONSUMED":
                continue
            path = self._path(name)
            if path.exists():
                self._verified(name, value)
                path.unlink()
            item["state"] = "DELETED"
            changed = True
        if changed:
            self._save(value)
        self._guard(self.folder)
        if self.folder.is_dir() and not any(self.folder.iterdir()):
            self.folder.rmdir()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.finalize()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("reserve", "register", "consume", "retain", "finalize"))
    parser.add_argument("--output", type=Path, required=True, help="Owned run/analysis output, never a source evidence folder")
    parser.add_argument("--name", help="Simple filename inside output/.debug-images")
    parser.add_argument("--purpose", help="Review purpose for reserve, or retention reason for retain")
    args = parser.parse_args(argv)
    if args.action != "finalize" and not args.name:
        parser.error("This action requires an exact image name")
    images = DebugImages(args.output)
    if args.action in {"reserve", "retain"}:
        path = getattr(images, args.action)(args.name, args.purpose)
    elif args.action == "finalize":
        images.finalize()
        path = None
    else:
        path = getattr(images, args.action)(args.name)
    if path is not None:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
