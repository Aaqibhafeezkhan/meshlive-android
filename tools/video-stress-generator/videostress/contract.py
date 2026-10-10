"""Strict file contract compatible with the read-only Bluetooth input binder."""

import hashlib
import json
import re
from pathlib import Path

COLOR_FIELDS = ("range", "primaries", "matrix", "transfer", "chroma_location")


def positive_int(value, name):
    if type(value) is not int or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def mode(row):
    if not isinstance(row,dict):
        raise ValueError("Native mode must be a width/height/fps object")
    values = tuple(positive_int(row.get(k), k) for k in ("width", "height", "fps"))
    if values[0] % 2 or values[1] % 2:
        raise ValueError("Native I420 width and height must be even; no substitution")
    return values


def digest(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def read_json(path):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(data,dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return data


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def file_record(spec, base):
    path = spec.get("path")
    sha = spec.get("sha256")
    if not isinstance(path, str) or not path or Path(path).is_absolute() or ".." in Path(path).parts:
        raise ValueError("Fixture/sidecar path must be relative and stay within the manifest directory")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
        raise ValueError("Every fixture/sidecar needs an exact 64-hex SHA256")
    resolved = (base / path).resolve()
    if not resolved.is_relative_to(base.resolve()):
        raise ValueError("Fixture/sidecar resolves outside its manifest directory")
    if digest(resolved) != sha.lower():
        raise ValueError(f"SHA256 mismatch: {resolved}")
    return resolved


def validate_manifest(path):
    path = Path(path)
    data = read_json(path)
    if type(data.get("schema_version")) is not int or data["schema_version"] != 1:
        raise ValueError("Raw fixture manifest needs schema_version 1")
    rows = data.get("fixtures")
    if not isinstance(rows, list) or not rows:
        raise ValueError("Manifest needs a nonempty fixtures array")
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Fixture must be an object")
        key = mode(row)
        if key in seen:
            raise ValueError(f"Ambiguous duplicate native fixture: {key}; use separate variant manifests")
        seen.add(key)
        if row.get("format") != "I420" or type(row.get("bit_depth")) is not int or row["bit_depth"] != 8:
            raise ValueError("Only 8-bit I420 is supported; optional formats are unsupported")
        n = positive_int(row.get("frame_count"), "frame_count")
        color = row.get("color")
        if not isinstance(color, dict) or any(not isinstance(color.get(k), str) or not color[k].strip() for k in COLOR_FIELDS):
            raise ValueError("Explicit color declarations required; unknown imported tags must be UNSPECIFIED")
        raw = file_record(row, path.parent)
        if raw.stat().st_size != key[0] * key[1] * 3 // 2 * n:
            raise ValueError("Truncated/padded/wrong-layout input: byte size differs from declared whole frames")
        sidecars = row.get("sidecars", {})
        if not isinstance(sidecars, dict) or any(not isinstance(v, dict) for v in sidecars.values()):
            raise ValueError("Sidecars must map names to path/SHA256 objects")
        for spec in sidecars.values():
            file_record(spec, path.parent)
    return data


def requirements(path):
    data = read_json(path)
    if data.get("schema") != "benchmark-input-requirements-v1":
        raise ValueError("Expected benchmark-input-requirements-v1")
    contract = data.get("raw_contract", {})
    if not isinstance(contract,dict):
        raise ValueError("Requirements raw_contract must be an object")
    for key, value in {"format": "I420", "bit_depth": 8, "plane_order": ["Y", "U", "V"]}.items():
        if key in contract and (contract[key] != value or (key == "bit_depth" and type(contract[key]) is not int)):
            raise ValueError(f"Unsupported consumer requirement: {key}={contract[key]!r}")
    rows = data.get("dimensions")
    if not isinstance(rows, list) or not rows:
        raise ValueError("Requirements need a nonempty dimensions array")
    result = set()
    for row in rows:
        key = mode(row)
        if "frame_bytes" in row and (type(row["frame_bytes"]) is not int or row["frame_bytes"] != key[0] * key[1] * 3 // 2):
            raise ValueError("Requirements frame_bytes do not match native I420")
        if "timebase" in row and (not isinstance(row["timebase"],dict) or
                any(type(row["timebase"].get(k)) is not int for k in ("numerator","denominator")) or
                row["timebase"] != {"numerator": 1, "denominator": key[2]}):
            raise ValueError("Unsupported requirements timebase; integer native FPS only")
        if key in result:
            raise ValueError("Duplicate requirements mode")
        result.add(key)
    return result
