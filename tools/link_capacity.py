"""Measure and report each phone's own Bluetooth link capacity.

Android exposes radio features but no throughput or concurrent-link API, so every phone learns
its capacity per transport (LinkCapacity in the app) from its live links and from saturating
probes. This tool drives those probes and reads what each phone has learned.

  probe   saturate one link between two phones with the debug link benchmark; both phones
          record the maximum in their LinkCapacity store. Results go to an explicit output.
  relay   one middle phone receives and forwards over two GATT links at once; reports the
          upstream, downstream and combined rate while both links run, for a role layout:
          mixed (peripheral upstream, central downstream, as the app does today) or central
          (central on both links, the source notifies upstream data).
  report  print every phone's learned capacity and self-reported radio features.

Only the owner of the named phones may run this; it force-stops the app on them.
"""

import os
import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402

PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
COMPONENT = f"{PACKAGE}/com.example.bluetoothtest.MainActivity"
DEFAULT_ADB = default_adb()
FINAL = re.compile(r"BluetoothVideo: (.*\bFINAL ([0-9.]+) Mbps.*)")
FAILED = re.compile(r"BluetoothVideo: (.*(?:benchmark failed|INTEGRITY FAILED|not found|Unknown transport experiment).*)", re.I)

# mode -> (transport extra, role started first (it advertises), its experiment, the other's experiment)
MODES = {
    "gatt-write": ("gatt", "rx", "gatt_rx", "gatt_tx"),
    "gatt-notify": ("gatt", "tx", "gatt_notify_tx", "gatt_notify_rx"),
    "coc": ("ble", "rx", "raw_rx", "raw_tx"),
    "classic": ("classic", "rx", "raw_rx", "raw_tx"),
}
# Match the app's real link setup: CoC media links are primed with a same-ACL GATT MTU exchange.
MODE_EXTRAS = {"coc": ["--ez", "test_coc_prime_gatt_mtu", "true"]}
TRANSPORT_KEYS = {"CLASSIC_RFCOMM": "classic", "BLE_L2CAP": "coc", "BLE_GATT": "gatt"}


def load_serials(path):
    devices = json.loads(Path(path).read_text(encoding="utf-8"))
    devices = devices.get("devices", devices)
    return {alias: entry["serial"] for alias, entry in devices.items()}


class Phones:
    def __init__(self, adb, serials):
        self.adb_path = adb
        self.serials = serials

    def adb(self, phone, *args, timeout=30, check=True):
        result = subprocess.run([self.adb_path, "-s", self.serials[phone], *args], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"{phone}: adb {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout

    def epoch_log(self, phone):
        return self.adb(phone, "logcat", "-d", "-v", "epoch", "-s", "BluetoothVideo:I", timeout=60)

    def start(self, phone, experiment, transport, seconds, extras=()):
        self.adb(phone, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
        self.adb(phone, "shell", "wm", "dismiss-keyguard", check=False)
        self.adb(phone, "shell", "am", "start", "-n", COMPONENT, "--es", "test_experiment", experiment,
                 "--es", "test_transport", transport, "--ei", "test_duration_seconds", str(seconds), *extras)

    def log(self, phone):
        return self.adb(phone, "logcat", "-d", "-s", "BluetoothVideo:I", timeout=60)

    def learned(self, phone):
        xml = self.adb(phone, "shell", "run-as", PACKAGE, "cat", "shared_prefs/link_capacity.xml", check=False)
        values = dict(re.findall(r'name="([^"]+)" value="(-?\d+)"', xml))
        features = re.search(r'<string name="features">([^<]*)</string>', xml)
        learned = {}
        for key, label in TRANSPORT_KEYS.items():
            ceiling = int(values.get(f"{key}.ceiling", 0))
            at = int(values.get(f"{key}.ceilingAt", 0))
            probe_at = int(values.get(f"{key}.probeAt", 0))
            learned[label] = {
                "probe_kbps": int(values.get(f"{key}.probe", 0)) or None,
                "probe_measured_at": datetime.fromtimestamp(probe_at / 1000, timezone.utc).isoformat() if probe_at else None,
                "proven_kbps": int(values.get(f"{key}.proven", 0)),
                "ceiling_kbps": ceiling or None,
                "ceiling_measured_at": datetime.fromtimestamp(at / 1000, timezone.utc).isoformat() if at else None,
            }
        return {"features": features.group(1) if features else None, "transports": learned}


def probe(args, phones):
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    transport, first_role, first_experiment, second_experiment = MODES[args.mode]
    results = []
    pairs = [(args.tx, args.rx)] + ([(args.rx, args.tx)] if args.both_directions else [])
    for tx, rx in pairs:
        for phone in (tx, rx):
            phones.adb(phone, "shell", "am", "force-stop", PACKAGE)
            phones.adb(phone, "logcat", "-c", check=False)
        started = datetime.now(timezone.utc).isoformat()
        first, second = (rx, tx) if first_role == "rx" else (tx, rx)
        extras = MODE_EXTRAS.get(args.mode, []) + (
            ["--ei", "test_payload_bytes", str(args.payload_bytes)] if args.payload_bytes else [])
        phones.start(first, first_experiment, transport, args.seconds, extras)
        time.sleep(3)
        phones.start(second, second_experiment, transport, args.seconds, extras)
        deadline = time.monotonic() + args.seconds + 45
        finals, failure = {}, None
        while time.monotonic() < deadline and len(finals) < 2 and failure is None:
            time.sleep(2)
            for role, phone in (("tx", tx), ("rx", rx)):
                text = phones.log(phone)
                match = FINAL.search(text)
                if match and role not in finals:
                    finals[role] = {"line": match.group(1), "mbps": float(match.group(2))}
                bad = FAILED.search(text)
                if bad:
                    failure = f"{phone}: {bad.group(1)}"
        for phone in (tx, rx):
            (output / f"{args.mode}-{tx}-to-{rx}-{phone}.log").write_text(phones.log(phone), encoding="utf-8")
            phones.adb(phone, "shell", "am", "force-stop", PACKAGE)
        result = {"mode": args.mode, "transmitter": tx, "receiver": rx, "requested_seconds": args.seconds,
                  "started_at": started, "status": "MEASURED" if len(finals) == 2 else "INCOMPLETE",
                  "failure": failure, **{f"{role}_final": value for role, value in finals.items()}}
        results.append(result)
        print(json.dumps(result))
        time.sleep(2)
    (output / "link-capacity.json").write_text(json.dumps({"schema_version": 1, "results": results}, indent=2),
                                               encoding="utf-8")
    return 0 if all(result["status"] == "MEASURED" for result in results) else 1


RATE = re.compile(r"^\s*([0-9.]+)\s.*BluetoothVideo: (UP |DOWN )?GATT RX ([0-9.]+) Mbps", re.M)


def rates(text, prefix):
    return [(float(when), float(mbps)) for when, label, mbps in RATE.findall(text) if (label or "") == prefix]


def overlap_mean(samples, start, end):
    values = [mbps for when, mbps in samples if start <= when <= end]
    return round(sum(values) / len(values), 3) if values else None


def relay(args, phones):
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    source, middle, sink = args.source, args.middle, args.sink
    for phone in (source, middle, sink):
        phones.adb(phone, "shell", "am", "force-stop", PACKAGE)
        phones.adb(phone, "logcat", "-c", check=False)
    tag = lambda name, value: ["--ei", name, str(value)]
    rate = tag("test_rate_kbps", args.upstream_rate_kbps) if args.upstream_rate_kbps else []
    priorities = ["--es", "test_upstream_priority", args.upstream_priority,
                  "--es", "test_downstream_priority", args.downstream_priority,
                  *tag("test_downstream_rate_kbps", args.downstream_rate_kbps)]
    phones.start(sink, "gatt_rx", "gatt", args.seconds, tag("test_advertise_tag", 2))
    time.sleep(2)
    if args.roles == "mixed":
        phones.start(middle, "gatt_relay_mixed", "gatt", args.seconds,
                     tag("test_upstream_tag", 1) + tag("test_downstream_tag", 2) + priorities)
        time.sleep(3)
        phones.start(source, "gatt_tx", "gatt", args.seconds,
                     tag("test_target_tag", 1) + rate + ["--es", "test_ble_priority", args.upstream_priority])
    else:
        phones.start(source, "gatt_notify_tx", "gatt", args.seconds, tag("test_advertise_tag", 1) + rate)
        time.sleep(2)
        phones.start(middle, "gatt_relay_central", "gatt", args.seconds,
                     tag("test_upstream_tag", 1) + tag("test_downstream_tag", 2) + priorities)
    deadline = time.monotonic() + args.seconds + 45
    while time.monotonic() < deadline:
        time.sleep(3)
        text = phones.log(middle)
        if ("UP GATT RX FINAL" in text and "DOWN GATT TX FINAL" in text) or FAILED.search(text):
            break
    time.sleep(2)
    logs = {phone: phones.epoch_log(phone) for phone in (source, middle, sink)}
    for phone, text in logs.items():
        (output / f"relay-{args.roles}-{phone}.log").write_text(text, encoding="utf-8")
        phones.adb(phone, "shell", "am", "force-stop", PACKAGE)
    upstream = rates(logs[middle], "UP ")
    downstream = rates(logs[sink], "")
    result = {"roles": args.roles, "source": source, "middle": middle, "sink": sink,
              "upstream_priority": args.upstream_priority, "downstream_priority": args.downstream_priority,
              "upstream_rate_kbps": args.upstream_rate_kbps or None,
              "downstream_rate_kbps": args.downstream_rate_kbps or None,
              "requested_seconds": args.seconds, "status": "INCOMPLETE"}
    if upstream and downstream:
        start = max(upstream[0][0], downstream[0][0]) + 1
        end = min(upstream[-1][0], downstream[-1][0]) - 1
        up, down = overlap_mean(upstream, start, end), overlap_mean(downstream, start, end)
        result.update(overlap_seconds=round(end - start, 1), upstream_mbps=up, downstream_mbps=down)
        if up is not None and down is not None and end - start >= 3:
            result.update(status="MEASURED", combined_mbps=round(up + down, 3))
    result["upstream_alone_samples"] = len(upstream)
    result["downstream_samples"] = len(downstream)
    (output / "relay-capacity.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["status"] == "MEASURED" else 1


def report(args, phones):
    rows = {phone: phones.learned(phone) for phone in args.phones.split(",")}
    if args.json:
        print(json.dumps(rows, indent=2))
        return 0
    print(f"{'phone':<8}{'transport':<10}{'probe':>8}{'proven':>8}{'ceiling':>9}  (kbps)")
    for phone, data in rows.items():
        for label, value in data["transports"].items():
            print(f"{phone:<8}{label:<10}{value['probe_kbps'] or '-':>8}{value['proven_kbps'] or '-':>8}"
                  f"{value['ceiling_kbps'] or '-':>9}")
        print(f"{'':<8}{data['features'] or 'features not recorded (open the app once)'}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--devices", default="tools/benchmark_devices.json")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    sub = parser.add_subparsers(dest="command", required=True)
    probe_parser = sub.add_parser("probe")
    probe_parser.add_argument("--tx", required=True)
    probe_parser.add_argument("--rx", required=True)
    probe_parser.add_argument("--mode", choices=sorted(MODES), required=True)
    probe_parser.add_argument("--seconds", type=int, default=8)
    probe_parser.add_argument("--both-directions", action="store_true")
    probe_parser.add_argument("--payload-bytes", type=int, default=0, help="raw payload per write (CoC/Classic)")
    probe_parser.add_argument("--output", required=True, help="new run-owned directory")
    relay_parser = sub.add_parser("relay")
    relay_parser.add_argument("--source", required=True)
    relay_parser.add_argument("--middle", required=True)
    relay_parser.add_argument("--sink", required=True)
    relay_parser.add_argument("--roles", choices=["mixed", "central"], required=True)
    relay_parser.add_argument("--seconds", type=int, default=15)
    relay_parser.add_argument("--upstream-priority", choices=["high", "balanced", "dck"], default="high")
    relay_parser.add_argument("--downstream-priority", choices=["high", "balanced", "dck"], default="high")
    relay_parser.add_argument("--upstream-rate-kbps", type=int, default=0,
                              help="pace the source like a live stream (payload kbps); 0 saturates")
    relay_parser.add_argument("--downstream-rate-kbps", type=int, default=0,
                              help="pace the middle's forwarded link (payload kbps); 0 saturates")
    relay_parser.add_argument("--output", required=True, help="new run-owned directory")
    report_parser = sub.add_parser("report")
    report_parser.add_argument("--phones", required=True)
    report_parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    phones = Phones(args.adb, load_serials(args.devices))
    return {"probe": probe, "relay": relay, "report": report}[args.command](args, phones)


if __name__ == "__main__":
    sys.exit(main())
