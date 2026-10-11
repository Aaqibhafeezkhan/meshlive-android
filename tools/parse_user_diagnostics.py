#!/usr/bin/env python3
"""Turn a pasted in-app "Export diagnostics" block into a DeviceFamilyPolicy evidence suggestion.

    python tools/parse_user_diagnostics.py report.txt            # JSON suggestion
    python tools/parse_user_diagnostics.py report.txt --format text
    type report.txt | python tools/parse_user_diagnostics.py -

The field list is the contract in docs/ARCHITECTURE.md "Diagnostics block". The parser is an
allowlist: an unknown field, a MAC-like value or a long hex run (a possible key) is an error, because
the export must never carry media, messages, contact names, addresses or full identity keys.
A user report is always unverified evidence: it never edits the registry, it only drafts text for a
human to review against official sources (CONTRIBUTING "Device-family policies").
"""
import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_release import EXPECTED_CERT_SHA256, normalize_fingerprint, ReleaseError  # noqa: E402

HEADER = "=== BLUETOOTH-VIDEO DIAGNOSTICS v1 ==="
FOOTER_RE = re.compile(r"^=== END \(lines=(\d+)\) ===$")

SCALAR_KEYS = {
    "app.version": "required", "app.build": "required", "app.build_type": "required",
    "app.signing_sha256": "required",
    "device.manufacturer": "required", "device.model": "required", "device.soc": "required",
    "device.android": "required", "device.sdk": "required", "device.security_patch": "required",
    "bluetooth.le_2m": "required", "bluetooth.le_coded": "required",
    "bluetooth.le_extended_advertising": "required", "bluetooth.le_coc": "required",
    "bluetooth.le_max_adv_data_length": "optional",
    "identity.short_hash": "required", "device_policy": "required",
}
RECORD_KEYS = {
    "conn": {"t", "transport", "role", "peer", "status", "phy_req", "phy_obs", "mtu_req", "mtu_obs",
             "interval_req_ms", "interval_obs_ms", "outcome", "reason"},
    "adaptive_phy": {"t", "link", "from", "to", "reason", "kbps", "blocked"},
    "codec": {"t", "role", "mime", "component", "hw", "req", "eff"},
}
RECORD_LIMITS = {"conn": 50, "adaptive_phy": 50, "codec": 20}
MAX_BYTES = 16 * 1024
MAC_RE = re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")
LONG_HEX_RE = re.compile(r"[0-9A-Fa-f]{20,}")
SHORT_HASH_RE = re.compile(r"^[0-9a-f]{12}$")
STATUS_FAIL_MIN = 3


class DiagnosticsError(Exception):
    pass


def extract_block(text):
    lines = [line.rstrip("\r") for line in text.splitlines()]
    try:
        start = lines.index(HEADER)
    except ValueError:
        raise DiagnosticsError(f"missing header line {HEADER!r}")
    body = []
    for line in lines[start + 1:]:
        match = FOOTER_RE.match(line)
        if match:
            if int(match.group(1)) != len(body):
                raise DiagnosticsError(f"truncated or edited: footer says {match.group(1)} lines, found {len(body)}")
            return body
        body.append(line)
    raise DiagnosticsError("missing footer line '=== END (lines=N) ==='")


def parse_pairs(value):
    pairs = {}
    for part in value.split(" | "):
        key, sep, val = part.partition("=")
        if not sep or not key.strip():
            raise DiagnosticsError(f"bad key=value segment {part!r}")
        pairs[key.strip()] = val.strip()
    return pairs


def check_private(key, value):
    if key != "app.signing_sha256":
        if MAC_RE.search(value):
            raise DiagnosticsError(f"{key}: MAC-like address in value")
        if LONG_HEX_RE.search(value):
            raise DiagnosticsError(f"{key}: long hex run (possible key or full identity)")


def parse_diagnostics(text, known_policy_ids=None):
    if len(text.encode("utf-8")) > MAX_BYTES * 4:
        raise DiagnosticsError("input too large")
    body = extract_block(text)
    if sum(len(line) + 1 for line in body) > MAX_BYTES:
        raise DiagnosticsError(f"block over {MAX_BYTES} bytes")
    scalars, records, warnings = {}, {name: [] for name in RECORD_KEYS}, []
    for line in body:
        if not line.strip():
            continue
        key, sep, value = line.partition(": ")
        if not sep and line.endswith(":"):
            key, value = line[:-1], ""
        elif not sep:
            raise DiagnosticsError(f"unparseable line {line[:60]!r}")
        if key in SCALAR_KEYS:
            if key in scalars:
                raise DiagnosticsError(f"duplicate field {key}")
            check_private(key, value)
            scalars[key] = value.strip()
        elif key in RECORD_KEYS:
            check_private(key, value)
            pairs = parse_pairs(value)
            extra = set(pairs) - RECORD_KEYS[key]
            if extra:
                raise DiagnosticsError(f"{key}: unexpected field(s) {sorted(extra)}")
            records[key].append(pairs)
        else:
            raise DiagnosticsError(f"unexpected field {key!r} (the export is an allowlist)")
    missing = [k for k, need in SCALAR_KEYS.items() if need == "required" and k not in scalars]
    if missing:
        raise DiagnosticsError(f"missing required field(s): {missing}")
    for name, limit in RECORD_LIMITS.items():
        if len(records[name]) > limit:
            raise DiagnosticsError(f"more than {limit} {name} records")
    if not SHORT_HASH_RE.match(scalars["identity.short_hash"]):
        raise DiagnosticsError("identity.short_hash must be 12 lowercase hex characters")
    for rec in records["conn"]:
        if "peer" in rec and not SHORT_HASH_RE.match(rec["peer"]):
            raise DiagnosticsError("conn peer must be a 12-hex short hash")
    try:
        fingerprint = normalize_fingerprint(scalars["app.signing_sha256"])
    except ReleaseError as exc:
        raise DiagnosticsError(f"app.signing_sha256: {exc}")
    policy = scalars["device_policy"]
    m = re.match(r"^matched=([a-z0-9,\-]*|none)(?: \| registry=(\d+))?$", policy)
    if not m:
        raise DiagnosticsError("device_policy must look like 'matched=id1,id2 | registry=N'")
    matched = [] if m.group(1) in ("", "none") else m.group(1).split(",")
    if known_policy_ids is not None:
        unknown = [i for i in matched if i not in known_policy_ids]
        if unknown:
            warnings.append(f"policy id(s) not in this tree's registry: {unknown} (older/newer app?)")
    return {"scalars": scalars, "records": records, "fingerprint": fingerprint,
            "matched": matched, "warnings": warnings}


def _num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def summarize(parsed):
    conns = parsed["records"]["conn"]
    failed = [c for c in conns if c.get("outcome", "connected") != "connected" or c.get("status", "ok") not in ("ok", "0")]
    status_hist = {}
    for c in failed:
        status_hist[c.get("status", "?")] = status_hist.get(c.get("status", "?"), 0) + 1
    phy_pairs = [(c["phy_req"], c["phy_obs"]) for c in conns if c.get("phy_req") and c.get("phy_obs")]
    downgrades = [p for p in phy_pairs if p[0] != p[1]]
    mtu_short = [c for c in conns if _num(c.get("mtu_req")) and _num(c.get("mtu_obs"))
                 and _num(c["mtu_obs"]) < _num(c["mtu_req"])]
    interval_slow = [c for c in conns if _num(c.get("interval_req_ms")) and _num(c.get("interval_obs_ms"))
                     and _num(c["interval_obs_ms"]) > 1.5 * _num(c["interval_req_ms"])]
    reasons = {}
    for a in parsed["records"]["adaptive_phy"]:
        reasons[a.get("reason", "?")] = reasons.get(a.get("reason", "?"), 0) + 1
    return {
        "connections": len(conns), "failed": len(failed), "failure_status_histogram": status_hist,
        "phy_requested_vs_observed_mismatch": f"{len(downgrades)}/{len(phy_pairs)}",
        "mtu_below_requested": len(mtu_short), "interval_over_1.5x_requested": len(interval_slow),
        "adaptive_phy_events": len(parsed["records"]["adaptive_phy"]), "adaptive_phy_reasons": reasons,
        "codecs": [{k: c.get(k) for k in ("role", "mime", "component", "hw", "req", "eff")}
                   for c in parsed["records"]["codec"]],
    }


def suggest(parsed, expected_cert=EXPECTED_CERT_SHA256):
    s = parsed["scalars"]
    official = parsed["fingerprint"] == normalize_fingerprint(expected_cert) and s["app.build_type"] == "release"
    obs = summarize(parsed)
    family_key = "/".join(x.lower().replace(" ", "-") for x in
                          (s["device.manufacturer"], s["device.model"], f"sdk{s['device.sdk']}"))
    ident = (f"user report from identity {s['identity.short_hash']}, app {s['app.version']} ({s['app.build']}), "
             f"{'official signed build' if official else 'UNOFFICIAL or debug build'}, "
             f"{s['device.manufacturer']} {s['device.model']} ({s['device.soc']}), Android {s['device.android']} "
             f"patch {s['device.security_patch']}")
    lines = [f"{ident}: {obs['connections']} connections, {obs['failed']} failed "
             f"(status {obs['failure_status_histogram'] or 'none'}), PHY requested!=observed "
             f"{obs['phy_requested_vs_observed_mismatch']}, MTU below request {obs['mtu_below_requested']}, "
             f"interval >1.5x request {obs['interval_over_1.5x_requested']}, adaptive_phy {obs['adaptive_phy_reasons'] or 'none'}"]
    suggestions = [{"action": "append-evidence", "entry_id": entry_id, "text": lines[0],
                    "confidence_note": "unverified user report; does not by itself raise confidence"}
                   for entry_id in parsed["matched"]]
    for status, count in obs["failure_status_histogram"].items():
        if count >= STATUS_FAIL_MIN:
            suggestions.append({
                "action": "candidate-new-entry",
                "trigger": f"status {status} on {count} failed connections",
                "matcher_draft": f"manufacturer={s['device.manufacturer']} model={s['device.model']} sdk={s['device.sdk']}",
                "needs": ["official source (AOSP/CDD/vendor/SIG)", "second report or local reproduction",
                          "off-switch", "confidence statement"],
            })
    mismatch, total = (int(x) for x in obs["phy_requested_vs_observed_mismatch"].split("/"))
    if total >= 4 and mismatch * 2 >= total:
        suggestions.append({
            "action": "candidate-new-entry",
            "trigger": f"PHY observed differs from requested on {mismatch}/{total} connections",
            "matcher_draft": f"manufacturer={s['device.manufacturer']} model={s['device.model']} sdk={s['device.sdk']}",
            "needs": ["official source", "second report or local reproduction", "off-switch", "confidence statement"],
        })
    caps = {k.split(".", 1)[1]: s[k] for k in s if k.startswith("bluetooth.")}
    return {
        "source": "user-report", "confidence": "low (unverified user report)",
        "official_build": official, "signing_sha256": parsed["fingerprint"],
        "family_key": family_key,
        "family": {k.split(".", 1)[1]: s[k] for k in s if k.startswith("device.")},
        "capabilities": caps, "policy_matched": parsed["matched"], "observations": obs,
        "evidence_lines": lines, "registry_suggestions": suggestions, "warnings": parsed["warnings"]
        + ([] if official else ["not an official signed release build: treat evidence as lower quality"]),
    }


def registry_ids(policy_path):
    """Rule ids from the registry sources: a single .kt file, or every *Policy*.kt / NativeCompat.kt in a directory."""
    path = Path(policy_path)
    if path.is_dir():
        files = sorted(set(path.glob("*Policy*.kt")) | set(path.glob("NativeCompat.kt")))
    else:
        files = [path]
    ids = set()
    found = False
    for file in files:
        try:
            ids.update(re.findall(r'\bid = "([a-z0-9\-]+)"', file.read_text(encoding="utf-8")))
            found = True
        except OSError:
            continue
    return ids if found else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("input", help="file with the pasted block, or - for stdin")
    parser.add_argument("--format", choices=("json", "text"), default="json")
    parser.add_argument("--policy-file", help="registry source file or directory", default=str(
        Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/bluetoothtest"))
    args = parser.parse_args(argv)
    text = sys.stdin.read() if args.input == "-" else Path(args.input).read_text(encoding="utf-8")
    try:
        result = suggest(parse_diagnostics(text, registry_ids(args.policy_file)))
    except DiagnosticsError as exc:
        print(f"parse_user_diagnostics: {exc}", file=sys.stderr)
        return 1
    if args.format == "json":
        print(json.dumps(result, indent=2))
    else:
        print(f"{result['family_key']}  official_build={result['official_build']}  matched={result['policy_matched']}")
        for line in result["evidence_lines"]:
            print("evidence:", line)
        for item in result["registry_suggestions"]:
            print(f"suggest: {item['action']} {item.get('entry_id') or item.get('trigger')}")
        for warning in result["warnings"]:
            print("warning:", warning)
    return 0


if __name__ == "__main__":
    sys.exit(main())
