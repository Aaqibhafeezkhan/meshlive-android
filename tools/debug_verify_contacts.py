"""Make test phones mutual verified contacts without two humans scanning QR codes (DEBUG builds only).

For the rotating-ID phone test P3. The app logs `BENCHMARK identity fingerprint=<64 hex> node=<32 hex>` at
launch. This helper reads that line from each phone's logcat, then writes into every phone's app-private
storage (`adb shell run-as <package>`, which only works on a debuggable build) a one-shot file holding a fresh
token and the other phone's fingerprint. It performs no other phone action: it does not start the app. The
harness launches each phone with the printed extras (`--es test_verify_contact <token>`), the app consumes and
deletes the file, marks the contact verified exactly as the QR flow would and logs
`BENCHMARK contact_verified source=debug fingerprint_prefix=...`. `--forget` writes the forget files instead
(`test_forget_contact`), which the app handles like the Forget action. See docs/dev/switches.md "Debug contact verification".

    python tools/debug_verify_contacts.py --phones s24,s22          # mutual verify, prints the launch extras
    python tools/debug_verify_contacts.py --phones s24,s22 --forget

Phones are aliases of tools/benchmark_devices.json or raw adb serials; exactly two (one mutual pair per call).
Output is one JSON document on stdout.
"""
import argparse
import itertools
import json
import os
import re
import secrets
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
IDENTITY_LINE = re.compile(r"BENCHMARK identity fingerprint=([0-9a-f]{64}) node=([0-9a-f]{32})")
SAFE_NAME = re.compile(r"[^A-Za-z0-9 ._-]")

# Must match DebugContactControl in the app.
FILES = {
    "verify": ("debug-verify-contact", "test_verify_contact"),
    "forget": ("debug-forget-contact", "test_forget_contact"),
}


class HarnessError(RuntimeError):
    pass


def default_runner(serial, args, timeout=30):
    """Runs `adb -s <serial> <args>` and returns (returncode, stdout)."""
    from android_tools import default_adb
    result = subprocess.run([default_adb(), "-s", serial, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    return result.returncode, result.stdout


def load_registry(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))["devices"]
    except (OSError, ValueError, KeyError):
        return {}


def resolve(phone, registry):
    """(label, serial, registry node prefix or None) for an alias or a raw serial."""
    entry = registry.get(phone)
    if entry:
        return phone, entry["serial"], entry.get("node_prefix")
    return phone, phone, None


def read_fingerprint(run, serial):
    """The most recent identity line of the app on this phone, as (fingerprint, node) or raises."""
    code, out = run(serial, ["logcat", "-d", "-s", "BluetoothVideo:I"], 60)
    if code != 0:
        raise HarnessError(f"{serial}: logcat failed")
    found = IDENTITY_LINE.findall(out)
    if not found:
        raise HarnessError(f"{serial}: no 'BENCHMARK identity fingerprint=' line in logcat; launch the DEBUG app once first")
    fingerprint, node = found[-1]
    if not fingerprint.startswith(node):
        raise HarnessError(f"{serial}: identity line is inconsistent (node is not the fingerprint prefix)")
    return fingerprint, node


def write_one_shot(run, serial, kind, token, fingerprint, name):
    """Writes the one-shot file with run-as and checks it reads back; returns nothing."""
    file_name, _ = FILES[kind]
    lines = [token, fingerprint] + ([SAFE_NAME.sub("", name).strip()] if name else [])
    echoes = "; ".join(f"echo {shlex.quote(line)}" for line in lines if line)
    script = f"umask 077; mkdir -p files; {{ {echoes}; }} > files/{file_name}"
    code, _ = run(serial, ["shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(script)], 30)
    if code != 0:
        raise HarnessError(f"{serial}: run-as write failed (is this a debuggable build of {PACKAGE}?)")
    code, text = run(serial, ["shell", "run-as", PACKAGE, "cat", f"files/{file_name}"], 30)
    if code != 0 or [line.strip() for line in text.splitlines() if line.strip()] != [line for line in lines if line]:
        raise HarnessError(f"{serial}: {file_name} did not read back as written")


def plan(args, run, registry):
    phones = [resolve(phone.strip(), registry) for phone in args.phones.split(",") if phone.strip()]
    if len(phones) != 2 or len({serial for _, serial, _ in phones}) != 2:
        raise HarnessError("--phones needs exactly two different phones")
    kind = "forget" if args.forget else "verify"
    identities = {}
    warnings = []
    for label, serial, prefix in phones:
        fingerprint, node = read_fingerprint(run, serial)
        if prefix and not fingerprint.startswith(prefix):
            warnings.append(f"{label}: logged identity {fingerprint[:12]} differs from the registry node_prefix {prefix}")
        identities[label] = (serial, fingerprint)
    results = []
    for (label, serial, _) in phones:
        other = next(name for name, _, _ in phones if name != label)
        fingerprint = identities[other][1]
        token = secrets.token_hex(16)
        write_one_shot(run, serial, kind, token, fingerprint, args.name or f"Peer {other}")
        results.append({
            "phone": label, "serial": serial, "peer": other, "kind": kind,
            "peer_fingerprint_prefix": fingerprint[:12],
            "token": token,
            "launch_extras": ["--es", FILES[kind][1], token],
        })
    return {"package": PACKAGE, "kind": kind, "phones": results, "warnings": warnings}


def main(argv=None, run=default_runner):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--phones", required=True, help="two registry aliases or adb serials, comma separated")
    parser.add_argument("--forget", action="store_true", help="write the forget files instead of the verify files")
    parser.add_argument("--name", help="display name stored for the contact (default: 'Peer <alias>')")
    parser.add_argument("--devices", default=str(ROOT / "tools/benchmark_devices.json"))
    args = parser.parse_args(argv)
    try:
        report = plan(args, run, load_registry(args.devices))
    except HarnessError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    for warning in report["warnings"]:
        print(f"warning: {warning}", file=sys.stderr)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
