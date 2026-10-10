"""Bind explicitly declared offline adapter specs to one immutable execution.

Input benchmark-measurement-bindings-v1 contains adapters with id, kind, spec
(path or path/sha256), and explicit scope device/source/pid/session. Optional
extraction is also a path or hash record. Run/case/APK identity comes from the
manifest. This hashes and validates bindings; it does not capture, score, invent
clocks/mapping/tolerances, or modify the manifest or source specifications.
physical_av keeps original independent recording provenance and, when supplied,
the exact microphone extraction and source_extraction for a separate native
source channel; decoded video/accepted PCM cannot supply physical observations.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re

import benchmark_measurements as measurements
from benchmark_layout import original, output_path, resolve as resolve_input


def pointer(value, base):
    if isinstance(value, str):
        value = {"path": value}
    if not isinstance(value, dict) or not isinstance(value.get("path"), str) or not value["path"]:
        raise ValueError("Adapter spec/extraction needs an explicit nonempty path")
    path = resolve_input(value["path"], base=base)
    if "sha256" in value:
        return measurements.record(value, base)[1]
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"path": str(path), "sha256": digest}


def prepare(manifest_path, bindings_path, output):
    manifest_path, bindings_path = resolve_input(manifest_path), resolve_input(bindings_path)
    output = output_path(output, "runs")
    if output.exists():
        raise ValueError("Output exists; use a fresh explicit preparation directory")
    manifest_record, bindings_record = pointer(str(manifest_path), output), pointer(str(bindings_path), output)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    bindings = json.loads(bindings_path.read_text(encoding="utf-8-sig"))
    if not isinstance(manifest, dict) or not isinstance(manifest.get("run_id"), str) or not manifest["run_id"] or not isinstance(
            manifest.get("scenario", {}).get("id"), str) or not manifest["scenario"]["id"]:
        raise ValueError("An actual execution manifest with run_id/scenario.id is required")
    if not isinstance(bindings, dict) or bindings.get("schema") != "benchmark-measurement-bindings-v1":
        raise ValueError("Expected benchmark-measurement-bindings-v1")
    adapters = bindings.get("adapters")
    if (not isinstance(adapters, list) or not adapters or any(not isinstance(row, dict) or not isinstance(row.get("id"), str)
            or not re.fullmatch(r"[A-Za-z0-9_-]+", row["id"]) for row in adapters)
            or len({row["id"] for row in adapters}) != len(adapters)):
        raise ValueError("Bindings require adapters with unique nonempty IDs")
    entries, diagnostics = [], []
    for declared in adapters:
        entry = dict(declared)
        diagnostic = {"id": entry["id"], "kind": entry.get("kind"), "binding_status": "INVALID"}
        try:
            if not isinstance(entry.get("scope"), dict):
                raise ValueError("Explicit device/source/pid/session scope is required")
            scope = dict(entry["scope"])
            entry["scope"] = scope
            scope.setdefault("run_id", manifest["run_id"])
            device = manifest.get("devices", {}).get(scope.get("device"), {})
            scope.setdefault("apk_sha256", device.get("apk_sha256", ""))
            entry["spec"] = pointer(entry["spec"], original(bindings_path).parent)
            for field in ("extraction", "source_extraction"):
                if field in entry:
                    entry[field] = pointer(entry[field], original(bindings_path).parent)
            bound = measurements.validate_adapter(entry, manifest, manifest_path, original(bindings_path).parent)
            diagnostic.update(binding_status="READY", capture_provenance=bound["capture_provenance"])
        except (ValueError, OSError, KeyError, TypeError, AttributeError) as error:
            diagnostic["reason"] = str(error)
        entries.append(entry)
        diagnostics.append(diagnostic)
    measurements.record(manifest_record, original(manifest_path).parent)
    measurements.record(bindings_record, original(bindings_path).parent)
    sidecar = {"schema": "benchmark-measurements-v1", "cases": [{"case_id": manifest["scenario"]["id"],
               "manifest": manifest_record, "adapters": entries}]}
    output.mkdir(parents=True, exist_ok=False)
    sidecar_path = output / "measurements.json"
    sidecar_path.write_text(json.dumps(sidecar, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    result = {"schema": "benchmark-measurement-preparation-v1", "measurement_status": "UNMEASURED",
              "binding_status": "READY" if all(row["binding_status"] == "READY" for row in diagnostics) else "INVALID",
              "manifest": manifest_record, "bindings": bindings_record,
              "measurements": pointer(str(sidecar_path), output), "adapters": diagnostics,
              "limitations": ["Binding validation only; no capture, scoring or phone acceptance.",
                              "Original clocks/timestamps, mapping and tolerances must be supplied as recorded evidence.",
                              "Invalid adapters remain in the sidecar and must be corrected before useful analysis."]}
    (output / "preparation.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--bindings", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = prepare(args.manifest, args.bindings, args.output)
    except (ValueError, OSError, KeyError, TypeError, AttributeError) as error:
        parser.error(str(error))
    print(f"{result['binding_status']}: {len(result['adapters'])} adapters; no scoring or phone acceptance. {args.output}")
    return 0 if result["binding_status"] == "READY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
