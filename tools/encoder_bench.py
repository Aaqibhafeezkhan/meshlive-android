#!/usr/bin/env python3
"""Host helper for the DEBUG radio-free encoder bench (`test_encoder_bench`, docs/dev/switches.md).

For one phone it runs a grid of encoder settings, one launch per cell, and collects the app's
`BENCHMARK encoder_bench:` lines into an explicit, fresh output directory:

    python tools/encoder_bench.py --serial R58M123ABC --out <fresh dir> \
        --raw-yuv <declared native raw fixture>.yuv --raw-width 640 --raw-height 360 --grid grid.json

The grid file is JSON: {"base": {extras shared by every cell}, "axes": {extra: [values, ...]}}; cells are the
Cartesian product of the axes, in order. Extras are the app's `test_*` launch extras (booleans become --ez,
integers --ei, anything else --es). Example:

    {"base": {"test_fps": 30, "test_bitrate_kbps": 600, "test_bench_duration_s": 20},
     "axes": {"test_bench_codec": ["av1", "vp9", "libaom-native", "hevc", "avc"],
              "test_bench_bitrate_mode": ["auto", "cbr", "vbr"],
              "test_intra_refresh_frames": [null, 30]}}

`test_bench_codec` also takes the bench-only "hevc"/"avc" (or video/hevc, video/avc, an exact encoder name);
they default to an explicit CBR mode. A null value leaves that extra out of the launch. Settings the encoder cannot honour come back as
`result=refused` with the reason and the requested/effective values; nothing is retried or changed here.

Power: every sample and the summary carry `power=[...]` (battery current, voltage, estimated mW, plugged state,
temperature). The estimate is whole-phone discharge power and is only valid unplugged: an adb USB cable or any
charger sets `power_valid=false`, and `results.jsonl` keeps that flag next to the figures. Run unplugged (adb over
Wi-Fi) when power matters.

Safety: the bench opens no Bluetooth, advertising or scanning, but the phone must still be owned by the caller
(see AGENTS.md): every cell force-stops the app first (a running app refuses a bench launch) and again after.
`--dry-run` prints the plan and every adb command and touches no phone.

Output directory (must be new or empty):
    manifest.json   serial, package, APK hash, device properties, grid, raw fixture identity, times
    results.jsonl   one record per cell: extras, requested/effective, the parsed summary and sample count, and
                    `power` (battery power series and summary: mean/p95 mW, mJ per frame, power_valid)
    cases/case-NNNN.log   that cell's `BENCHMARK encoder_bench:` lines, verbatim

A cell keeps only the lines of its own app process (pid read after the launch) written at or after the launch
(device clock). `logcat -c` does not always clear what the previous cell's process just wrote; lane10
b2d-asus-omx-hevc-soak cell 2 stopped early and inherited cell 1's summary. A cell without a summary from its own
process is `complete: false` with `incomplete_reason`; nothing is borrowed from a neighbour.

Multi-cell grids on the ASUS (lane11 f1-asus-bench-vbr, f1b): every second cell kept about 3 samples. The cause is the
same stale summary: ASUS `logcat -c` leaves the previous process's last lines (sample + summary) in the buffer, the
first poll of the next cell (2 s after launch) saw that summary and ended the cell, and the next cell had no summary
to inherit, so it ran its full length (cells 1 and 3 full, 2 and 4 truncated). Those results.jsonl records carry no
`pids`/`launch_window_start`, i.e. they were produced before the per-process filter above. Remaining gaps closed here:
the launch time keeps millisecond precision (`date +%N`, one whole second before), so a stale line in the launch
second no longer passes the time check; a process that outlives `force-stop` is stopped again, waited for and
excluded (`stale_pids_after_stop`).
"""
import argparse
import hashlib
import itertools
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_tools import default_adb  # noqa: E402

PACKAGE = os.environ.get("MESHLIVE_APP_ID", "io.github.meshlive.app")
ACTIVITY = "com.example.bluetoothtest.MainActivity"
LINE = re.compile(r"BENCHMARK encoder_bench: (.*)$")
BRACKET = re.compile(r"^\[(.*)\]$")
THREADTIME = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s")
SUMMARY_WAIT_MARGIN_S = 60
DEVICE_PROPS = ("ro.product.manufacturer", "ro.product.model", "ro.build.version.sdk", "ro.build.fingerprint",
                "ro.board.platform", "ro.hardware", "ro.soc.model", "ro.soc.manufacturer")


def expand_grid(grid):
    """Cells of {"base": {...}, "axes": {name: [values]}} as extras dicts (null values dropped)."""
    base = dict(grid.get("base", {}))
    axes = grid.get("axes", {})
    names = list(axes)
    for name, values in axes.items():
        if not isinstance(values, list) or not values:
            raise ValueError(f"axis {name!r} must be a non-empty list")
    cells = []
    for combo in itertools.product(*(axes[name] for name in names)):
        extras = dict(base)
        extras.update(zip(names, combo))
        cells.append({key: value for key, value in extras.items() if value is not None})
    for extras in cells:
        for key in extras:
            if not key.startswith("test_"):
                raise ValueError(f"extra {key!r} is not a test_* launch extra")
    return cells


def am_extra_args(extras):
    """`--ez/--ei/--es key value` triples for `am start`."""
    args = []
    for key, value in extras.items():
        if isinstance(value, bool):
            args += ["--ez", key, "true" if value else "false"]
        elif isinstance(value, int):
            args += ["--ei", key, str(value)]
        else:
            args += ["--es", key, str(value)]
    return args


def launch_command(package, extras):
    return ["shell", "am", "start", "-W", "-n", f"{package}/{ACTIVITY}", "--ez", "test_encoder_bench", "true",
            *am_extra_args(extras)]


def parse_line(text):
    """One `BENCHMARK encoder_bench:` log line -> {field: value}; bracketed requested/effective -> dicts."""
    match = LINE.search(text)
    if not match:
        return None
    fields = {}
    for part in match[1].split(" | "):
        key, _, value = part.partition("=")
        key = key.strip()
        value = value.strip()
        inner = BRACKET.match(value)
        if inner:
            value = dict(item.partition("=")[::2] for item in inner[1].split(";") if item)
        fields[key] = value
    return fields


def parse_threadtime(line):
    """(device timestamp 'MM-DD HH:MM:SS.mmm', pid) of a `logcat -v threadtime` line, or None."""
    match = THREADTIME.match(line)
    return (match[1], int(match[2])) if match else None


def select_cell_lines(lines, pids=None, since=None):
    """(kept, dropped): bench lines of this cell's own process(es) at or after the launch time.

    `pids` is the set of app pids seen after the launch and `since` the device time just before it. With neither
    known (dry run, no `pidof`) nothing is filtered. With either known, a line must be a parsable threadtime line that
    satisfies both known conditions."""
    kept, dropped = [], []
    for line in lines:
        if not pids and not since:
            kept.append(line)
            continue
        meta = parse_threadtime(line)
        if meta is None or (pids and meta[1] not in pids) or (since and meta[0] < since):
            dropped.append(line)
        else:
            kept.append(line)
    return kept, dropped


def parse_log(text):
    """(samples, summary or None, violations) from the cell's log text."""
    samples, summary, violations = [], None, []
    for raw in text.splitlines():
        fields = parse_line(raw)
        if not fields:
            continue
        event = fields.get("event")
        if event == "sample":
            samples.append(fields)
        elif event == "summary":
            summary = fields
        elif event == "violation":
            violations.append(fields)
    return samples, summary, violations


def _number(text):
    """A logged figure as a float, None for 'unknown'/'n/a'/missing."""
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def power_record(samples, summary):
    """Battery-power view of a cell: the summary's `power=[...]` fields with numbers parsed ('unknown' -> None), a
    per-second series, and whether the figure may be used. A cell whose app build predates power logging has none.
    The estimate is whole-phone discharge power; it is invalid while the phone was plugged (power_valid=false)."""
    summary_power = (summary or {}).get("power")
    series = []
    for sample in samples:
        fields = sample.get("power")
        if isinstance(fields, dict):
            series.append({"t_s": _number(sample.get("t_s")), "power_mw": _number(fields.get("power_mw")),
                           "current_now_ma": _number(fields.get("current_now_ma")),
                           "voltage_mv": _number(fields.get("voltage_mv")),
                           "temp_c": _number(fields.get("temp_c")), "plugged": fields.get("plugged"),
                           "valid": fields.get("power_valid") == "true"})
    if not isinstance(summary_power, dict) and not series:
        return None
    record = {"sample_series": series}
    if isinstance(summary_power, dict):
        numeric = ("samples", "valid_samples", "plugged_samples", "power_mw_mean", "power_mw_p95", "energy_mj",
                   "mj_per_frame", "voltage_mv_mean", "temp_c_start", "temp_c_end", "temp_c_max")
        record.update({key: _number(summary_power.get(key)) for key in numeric})
        record.update({"power_valid": summary_power.get("power_valid") == "true",
                       "power_reason": summary_power.get("power_reason"), "sign": summary_power.get("sign"),
                       "current_now_units": summary_power.get("current_now_units")})
    else:
        record["power_valid"] = False
        record["power_reason"] = "no_summary"
    return record


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Phone:
    def __init__(self, adb, serial, dry_run=False, run=subprocess.run, sleep=time.sleep):
        self.adb_path, self.serial, self.dry_run, self.run, self.sleep = adb, serial, dry_run, run, sleep
        self.commands = []

    def adb(self, *args, timeout=30):
        command = [self.adb_path, "-s", self.serial, *map(str, args)]
        self.commands.append(command)
        if self.dry_run:
            print("DRY-RUN", " ".join(command))
            return ""
        result = self.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"adb {' '.join(map(str, args))} exited {result.returncode}: "
                               f"{(result.stderr or result.stdout).strip()}")
        return result.stdout


def require_fresh_dir(path):
    path = Path(path)
    if path.exists() and any(path.iterdir()):
        raise SystemExit(f"{path} exists and is not empty: pass a fresh output directory")
    (path / "cases").mkdir(parents=True, exist_ok=True)
    return path


def stage_raw(phone, local, package, width, height):
    digest = sha256_file(local)
    remote = f"/sdcard/Android/data/{package}/files/benchmark-raw-{digest}.yuv"
    existing = phone.adb("shell", f"if [ -f {remote} ]; then sha256sum {remote}; fi").split()
    if not (existing and existing[0].lower() == digest):
        phone.adb("push", local, remote, timeout=300)
        verified = phone.adb("shell", "sha256sum", remote).split()
        if not phone.dry_run and not (verified and verified[0].lower() == digest):
            raise RuntimeError("uploaded raw fixture hash mismatch")
    return {"remote": remote, "sha256": digest, "width": width, "height": height,
            "bytes": os.path.getsize(local)}


STAMP = re.compile(r"(\d\d-\d\d \d\d:\d\d:\d\d)(?:\.(\d{3})\d*)?")


def device_stamp(phone):
    """Device local time as 'MM-DD HH:MM:SS.mmm' (the logcat threadtime format), or None (dry run, no answer).

    Milliseconds come from `%N` when the phone's date supports it; otherwise the whole second is used with .000 (the
    pid filter then carries the cell)."""
    for fmt in ("date '+%m-%d %H:%M:%S.%N'", "date '+%m-%d %H:%M:%S'"):
        match = STAMP.fullmatch((phone.adb("shell", fmt) or "").strip())
        if match:
            return f"{match[1]}.{match[2] or '000'}"
    return None


def stop_app(phone, package, attempts=5):
    """Force-stops the app and returns the pids still listed afterwards (a process that outlives force-stop)."""
    phone.adb("shell", "am", "force-stop", package)
    leftover = set()
    for _ in range(attempts):
        if phone.dry_run:
            return set()
        leftover = app_pids(phone, package)
        if not leftover:
            return set()
        phone.sleep(1)
        phone.adb("shell", "am", "force-stop", package)
    return leftover


def app_pids(phone, package):
    # `pidof` exits 1 when no process matches; on a phone that is simply "no pids", not an adb failure.
    try:
        output = phone.adb("shell", "pidof", package) or ""
    except RuntimeError as error:
        if "pidof" in str(error) and "exited 1" in str(error):
            return set()
        raise
    return {int(token) for token in output.split() if token.isdigit()}


def run_cell(phone, package, extras, out_dir, index):
    duration = int(extras.get("test_bench_duration_s", 20))
    stale_pids = stop_app(phone, package)
    phone.adb("logcat", "-c")
    since = device_stamp(phone)
    phone.adb(*launch_command(package, extras), timeout=60)
    deadline = time.monotonic() + duration + SUMMARY_WAIT_MARGIN_S
    pids = set()
    kept, dropped = [], []
    while not phone.dry_run:
        phone.sleep(2)
        pids |= app_pids(phone, package) - stale_pids
        dump = phone.adb("logcat", "-d", "-v", "threadtime", "-s", "BluetoothVideo:I", timeout=60)
        kept, dropped = select_cell_lines(
            [line for line in dump.splitlines() if "BENCHMARK encoder_bench:" in line], pids, since)
        _, summary, _ = parse_log("\n".join(kept))
        if summary is not None or time.monotonic() > deadline:
            break
    phone.adb("shell", "am", "force-stop", package)
    text = "\n".join(kept)
    log_path = out_dir / "cases" / f"case-{index:04d}.log"
    log_path.write_text(text + ("\n" if text else ""), encoding="utf-8")
    samples, summary, violations = parse_log(text)
    record = {
        "case": index,
        "extras": extras,
        "log": log_path.name,
        "complete": summary is not None,
        "samples": len(samples),
        "summary": summary,
        "violations": violations,
        "power": power_record(samples, summary),
        "pids": sorted(pids),
        "launch_window_start": since,
        "stale_pids_after_stop": sorted(stale_pids),
        "foreign_lines_dropped": len(dropped),
    }
    if summary is None and not phone.dry_run:
        record["incomplete_reason"] = "no summary line from this cell's own process after its launch"
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="adb serial of the phone you own")
    parser.add_argument("--out", required=True, help="fresh output directory (new or empty)")
    parser.add_argument("--grid", required=True, help="JSON grid file")
    parser.add_argument("--raw-yuv", help="local raw I420 fixture to push (declared geometry required)")
    parser.add_argument("--raw-remote", help="fixture already on the phone (absolute path)")
    parser.add_argument("--raw-width", type=int, required=True)
    parser.add_argument("--raw-height", type=int, required=True)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--adb", default=default_adb())
    parser.add_argument("--dry-run", action="store_true", help="print the plan and adb commands; touch no phone")
    args = parser.parse_args(argv)
    if bool(args.raw_yuv) == bool(args.raw_remote):
        parser.error("pass exactly one of --raw-yuv and --raw-remote")

    cells = expand_grid(json.loads(Path(args.grid).read_text(encoding="utf-8")))
    out_dir = require_fresh_dir(args.out)
    phone = Phone(args.adb, args.serial, dry_run=args.dry_run)
    started = datetime.now(timezone.utc).isoformat()

    props = {}
    apk_sha = None
    if not args.dry_run:
        listing = phone.adb("shell", "getprop")
        found = dict(re.findall(r"\[([^]]+)\]: \[([^]]*)\]", listing))
        props = {key: found.get(key) for key in DEVICE_PROPS}
        apk = phone.adb("shell", "pm", "path", args.package).splitlines()[0].removeprefix("package:").strip()
        apk_sha = phone.adb("shell", "sha256sum", apk).split()[0]
    raw = (stage_raw(phone, args.raw_yuv, args.package, args.raw_width, args.raw_height)
           if args.raw_yuv else {"remote": args.raw_remote, "sha256": None,
                                 "width": args.raw_width, "height": args.raw_height})
    for extras in cells:
        extras.update({"test_raw_yuv": raw["remote"], "test_raw_width": raw["width"],
                       "test_raw_height": raw["height"]})
    manifest = {"serial": args.serial, "package": args.package, "apk_sha256": apk_sha, "properties": props,
                "raw_fixture": raw, "cells": len(cells), "grid": json.loads(Path(args.grid).read_text(encoding="utf-8")),
                "started_utc": started, "dry_run": args.dry_run}
    if args.dry_run:
        print(json.dumps({"cells": len(cells), "first_cell": cells[0] if cells else None}, indent=2))
    with open(out_dir / "results.jsonl", "w", encoding="utf-8") as results:
        for index, extras in enumerate(cells, start=1):
            record = run_cell(phone, args.package, extras, out_dir, index)
            results.write(json.dumps(record) + "\n")
            results.flush()
    manifest["finished_utc"] = datetime.now(timezone.utc).isoformat()
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"{len(cells)} cell(s) -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
