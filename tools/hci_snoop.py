"""Per-case Bluetooth HCI snoop retrieval for benchmark phones (diagnostic evidence, never a gate).

Methods, cheapest first (each phone remembers what worked and what cannot work in this process):
  direct    `adb pull` of btsnoop_hci.log* from a readable snoop directory (userdebug/OEM builds);
  dumpsys   BTSNOOP_LOG_SUMMARY (btsnooz ring buffer, truncated ACL payloads) in `dumpsys bluetooth_manager`;
  bugreport `adb bugreport` only when nothing cheaper covers the case window; only the snoop is
            extracted and the zip is deleted immediately.
The result is trimmed to the case window (phone clock, margin) and written as one btsnoop file.
Retrieval failure is recorded as UNMEASURED with its reason; it never fails or blocks a case."""
import base64
import gzip
import concurrent.futures as futures
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import time
import zipfile
import zlib
from pathlib import Path

EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # btsnoop timestamps: microseconds since 0000-01-01
HEADER = b"btsnoop\0"
H4 = 1002
METHODS = ("direct", "dumpsys", "bugreport")
POLICIES = ("off", "auto", "on-failure", "required")
SNOOP_DIRECTORIES = ("/data/misc/bluetooth/logs", "/data/log/bt", "/sdcard/")
SNOOP_NAME = re.compile(r"(?:^|/)btsnoop_hci\.log(?:\.[^/]*)?$")
# Any archive member or readable file that may hold a snoop; its content must still start with the
# btsnoop header (OEMs rename/rotate: Samsung /data/log/bt, ColorOS logkit folders, .last/.cfa/.gz).
SNOOP_CANDIDATE = re.compile(r"snoop|(?:^|/)(?:bt|bluetooth)(?:/|_)|\.cfa$", re.I)
SNOOZ_US_THRESHOLD = 10 ** 14  # btsnooz header value: microseconds (~1.8e15) vs milliseconds (~1.8e12)
PROPERTY = re.compile(r"^\[([^\]]+)\]: \[([^\]]*)\]$")
LOGGING_PROPERTY = re.compile(r"snoop|bluetooth.*(?:log|verbose)|log\.tag\.(?:bt|bluetooth)", re.I)
MARGIN_S = 5.0
SNOOZ_TYPES = {0x10: (4, 3), 0x11: (2, 1), 0x12: (3, 1), 0x17: (5, 1),
               0x20: (1, 2), 0x21: (2, 0), 0x22: (3, 0), 0x2D: (5, 0)}  # AOSP btsnooz.py -> (H4 type, flags)
# Bugreport zips (15-30 MB) are written here, never kept: set by --hci-snoop-scratch or HCI_SNOOP_SCRATCH
# so a nearly full system drive is avoided; default is the case's own hci directory.
SCRATCH_ROOT = os.environ.get("HCI_SNOOP_SCRATCH") or None
LEARNED = {}  # serial -> {"works": method, "fails": {method: reason}}; cross-case cost saving only


class SnoopUnavailable(RuntimeError):
    pass


def read_btsnoop(data):
    """Return (datalink, [(timestamp_us, flags, drops, original_length, payload)])."""
    if data[:8] != HEADER or len(data) < 16:
        raise ValueError("Not a btsnoop file")
    version, datalink = struct.unpack(">II", data[8:16])
    records, offset = [], 16
    while offset + 24 <= len(data):
        original, included, flags, drops, timestamp = struct.unpack_from(">IIIIq", data, offset)
        payload = data[offset + 24: offset + 24 + included]
        if len(payload) < included:
            break  # File still being written when copied; keep complete records only.
        records.append((timestamp, flags, drops, original, payload))
        offset += 24 + included
    return datalink, records


def write_btsnoop(path, datalink, records):
    with open(path, "wb") as handle:
        handle.write(HEADER + struct.pack(">II", 1, datalink))
        for timestamp, flags, drops, original, payload in records:
            handle.write(struct.pack(">IIIIq", original, len(payload), flags, drops, timestamp))
            handle.write(payload)


def unix_seconds(timestamp_us):
    return (timestamp_us - EPOCH_DELTA_US) / 1e6


def snooz_records(text):
    """Decode every BTSNOOP_LOG_SUMMARY block (btsnooz v1/v2) into H4 btsnoop records."""
    records = []
    for match in re.finditer(r"BEGIN:BTSNOOP_LOG_SUMMARY[^\n]*\n(.*?)\n[^\n]*END:BTSNOOP_LOG_SUMMARY", text, re.S):
        blob = base64.b64decode("".join(match.group(1).split()))
        if len(blob) < 10:
            continue
        version, last_ms = struct.unpack_from("<bQ", blob)
        data = zlib.decompress(blob[9:])
        layout = "<HIb" if version == 1 else "<HHIb"
        size = struct.calcsize(layout)
        rows, offset = [], 0
        while offset + size <= len(data):
            if version == 1:
                length, delta, kind = struct.unpack_from(layout, data, offset)
                original = length
            else:
                length, original, delta, kind = struct.unpack_from(layout, data, offset)
            rows.append((delta, kind, original, data[offset + size: offset + size + length - 1]))
            offset += size + length - 1
        # AOSP btif_debug_btsnoop.cc names these fields *_ms but stores microseconds
        # (delta_time_ms = timestamp_us - last_timestamp_ms); older dumps used milliseconds. Decide by magnitude.
        scale = 1 if last_ms > SNOOZ_US_THRESHOLD else 1000
        stamp = last_ms  # Deltas are from the previous packet; the last packet is at last_ms.
        for delta, kind, original, packet in reversed(rows):
            h4, flags = SNOOZ_TYPES.get(kind, (0, 0))
            records.append((stamp * scale + EPOCH_DELTA_US, flags, 0, original + 1, bytes([h4]) + packet))
            stamp -= delta
    return sorted(set(records))


def snoop_bytes(name, data):
    """Return btsnoop bytes from a candidate file (plain or gzip), else None."""
    if name.lower().endswith(".gz"):
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError, zlib.error):
            return None
    return data if data[:8] == HEADER and len(data) >= 16 else None


def time_base_shift(records, window, phone_now, tz_offset_s):
    """Seconds to add to snoop times. Some OEM stacks may stamp local time instead of UTC: use the phone's
    UTC offset only when UTC times miss [window start, phone now] and the shifted last record lands in it."""
    if not records or not tz_offset_s:
        return 0
    last = unix_seconds(records[-1][0])
    inside = lambda value: window[0] <= value <= phone_now + 120
    if inside(last) or not inside(last - tz_offset_s):
        return 0
    return -tz_offset_s


def merge(sources):
    """Merge rotated/duplicated btsnoop sources by timestamp; mixed datalinks keep the first type."""
    datalink, merged, skipped = None, set(), 0
    for data in sources:
        kind, records = data if isinstance(data, tuple) else read_btsnoop(data)
        if datalink is None:
            datalink = kind
        if kind != datalink:
            skipped += 1
            continue
        merged.update(records)
    return datalink or H4, sorted(merged), skipped


def snoop_logging_state(getprop_output):
    """Recorded fact from `getprop`: snoop/verbose-logging properties and the interpreted snoop mode."""
    properties = {}
    for line in getprop_output.splitlines():
        match = PROPERTY.match(line.strip())
        if match and LOGGING_PROPERTY.search(match[1]):
            properties[match[1]] = match[2]
    mode = properties.get("persist.bluetooth.btsnooplogmode", "")
    legacy = properties.get("persist.bluetooth.btsnoopenable", "").lower()
    if mode and mode != "disabled" or legacy in ("true", "1"):
        enabled = "ENABLED"
    elif mode == "disabled" or legacy in ("false", "0"):
        enabled = "DISABLED"
    else:
        enabled = "UNKNOWN"  # Absent property: some OEM builds control the snoop elsewhere.
    return {"snoop_mode": mode or None, "snoop_enabled": enabled, "properties": properties}


class Collector:
    def __init__(self, adb, serial, run=None, clock=None):
        self.adb, self.serial = adb, serial
        self.run, self.clock = run or subprocess.run, clock or time.time
        self.last_sources = None

    def shell(self, *args, timeout=30):
        result = self.run([self.adb, "-s", self.serial, *args], capture_output=True, encoding="utf-8",
                          errors="replace", timeout=timeout)
        return result.returncode, result.stdout or "", result.stderr or ""

    def logging_state(self):
        code, out, err = self.shell("shell", "getprop", timeout=15)
        if code:
            return {"snoop_enabled": "UNKNOWN", "error": (err or out).strip()[:300]}
        return snoop_logging_state(out)

    def clock_offset_s(self):
        """Phone wall clock minus host clock (1 s resolution: `date +%s` exists on every toybox)."""
        before = self.clock()
        code, out, _ = self.shell("shell", "date", "+%s", timeout=10)
        after = self.clock()
        if code or not out.strip().isdigit():
            return 0.0, "UNKNOWN"
        return int(out.strip()) + 0.5 - (before + after) / 2, "1s"

    def tz_offset_s(self):
        """Phone UTC offset in seconds from `date +%z` (e.g. +0530), or 0 when unknown."""
        code, out, _ = self.shell("shell", "date", "+%z", timeout=10)
        match = re.fullmatch(r"([+-])(\d\d)(\d\d)", out.strip()) if not code else None
        return (1 if match[1] == "+" else -1) * (int(match[2]) * 3600 + int(match[3]) * 60) if match else 0

    def direct(self, scratch):
        """Pull snoop-named files from readable locations (`find` prints what it can read; permission
        errors on protected folders are expected on user builds)."""
        sources, members = [], []
        code, out, err = self.shell("shell", f"find {' '.join(SNOOP_DIRECTORIES)} -maxdepth 6 -type f "
                                    "-iname '*snoop*' 2>/dev/null", timeout=30)
        for remote in [line.strip() for line in out.splitlines() if line.strip().startswith("/")][:20]:
            target = scratch / f"direct-{len(members)}.bin"
            members.append(remote)
            code, _, _ = self.shell("pull", remote, str(target), timeout=120)
            data = snoop_bytes(remote, target.read_bytes()) if not code and target.is_file() else None
            target.unlink(missing_ok=True)
            if data:
                sources.append(data)
        self.last_sources = members
        if not sources:
            raise SnoopUnavailable("No readable btsnoop file in " + ", ".join(SNOOP_DIRECTORIES) +
                                   (f" (unreadable/non-btsnoop: {members[:5]})" if members else ""))
        return sources, "full"

    def dumpsys(self, scratch):
        code, out, err = self.shell("shell", "dumpsys", "bluetooth_manager", timeout=60)
        records = snooz_records(out) if not code else []
        if not records:
            raise SnoopUnavailable("dumpsys bluetooth_manager has no BTSNOOP_LOG_SUMMARY")
        return [(H4, records)], "truncated ACL payloads (btsnooz ring buffer)"

    def bugreport(self, scratch):
        root = Path(SCRATCH_ROOT) if SCRATCH_ROOT else scratch
        root.mkdir(parents=True, exist_ok=True)
        archive = root / f"bugreport-{self.serial}-{os.getpid()}-{time.monotonic_ns()}.zip"
        try:
            code, out, err = self.shell("bugreport", str(archive), timeout=600)
            if not archive.is_file():
                raise SnoopUnavailable(f"adb bugreport produced no zip (exit {code}): {(err or out).strip()[-200:]}")
            with zipfile.ZipFile(archive) as bundle:
                candidates = [entry for entry in bundle.infolist() if not entry.is_dir() and entry.file_size >= 16
                              and SNOOP_CANDIDATE.search(entry.filename)]
                self.last_sources = [f"{entry.filename} ({entry.file_size} B)" for entry in candidates][:40]
                sources = [data for data in (snoop_bytes(entry.filename, bundle.read(entry)) for entry in candidates
                                             if entry.file_size <= 256 << 20) if data]
                if sources:
                    return sources, "full"
                text = next((name for name in bundle.namelist() if re.match(r"bugreport-.*\.txt$", name)), None)
                records = snooz_records(bundle.read(text).decode("utf-8", "replace")) if text else []
                if records:
                    return [(H4, records)], "truncated ACL payloads (btsnooz ring buffer)"
            raise SnoopUnavailable("Bugreport has neither btsnoop_hci.log nor BTSNOOP_LOG_SUMMARY")
        finally:
            archive.unlink(missing_ok=True)  # Never retain the full bugreport (size, privacy).

    def collect(self, start_ns, end_ns, directory, logging_state=None, phone=None, cancel=None):
        """Write <directory>/btsnoop_hci.log trimmed to the case window. Never raises."""
        began = time.monotonic()
        receipt = {"phone": phone, "status": "UNMEASURED", "attempts": []}
        scratch = directory / ".hci-scratch"
        try:
            offset, resolution = self.clock_offset_s()
            tz_offset = self.tz_offset_s()
            window = (start_ns / 1e9 + offset - MARGIN_S, end_ns / 1e9 + offset + MARGIN_S)
            receipt["window"] = {"phone_start_unix_s": round(window[0], 3), "phone_end_unix_s": round(window[1], 3),
                                 "clock_offset_s": round(offset, 3), "clock_resolution": resolution,
                                 "margin_s": MARGIN_S}
            learned = LEARNED.setdefault(self.serial, {"works": None, "fails": {}})
            order = sorted(METHODS, key=lambda method: method != learned["works"])
            scratch.mkdir(parents=True, exist_ok=True)
            best = None
            for method in order:
                if cancel is not None and cancel.is_set():
                    receipt["attempts"].append({"method": method, "outcome": "SKIPPED", "reason": "Suite stopped"})
                    break
                if method in learned["fails"]:
                    receipt["attempts"].append({"method": method, "outcome": "SKIPPED",
                                                "reason": "Failed earlier in this suite: " + learned["fails"][method]})
                    continue
                attempt = {"method": method}
                receipt["attempts"].append(attempt)
                tick = time.monotonic()
                try:
                    self.last_sources = None
                    try:
                        sources, payload = getattr(self, method)(scratch)
                    finally:
                        if self.last_sources:
                            attempt["sources"] = self.last_sources
                    datalink, records, skipped = merge(sources)
                    phone_now = self.clock() + offset
                    shift = time_base_shift(records, window, phone_now, tz_offset)
                    if shift:
                        delta = int(shift * 1e6)
                        records = [(row[0] + delta, *row[1:]) for row in records]
                    kept = [row for row in records if window[0] <= unix_seconds(row[0]) <= window[1]]
                    covers = bool(records) and unix_seconds(records[0][0]) <= window[0] + MARGIN_S
                    attempt.update(outcome="RETRIEVED", source_records=len(records), window_records=len(kept),
                                   covers_window_start=covers,
                                   source_first_unix_s=round(unix_seconds(records[0][0]), 3) if records else None,
                                   source_last_unix_s=round(unix_seconds(records[-1][0]), 3) if records else None,
                                   phone_now_unix_s=round(phone_now, 3),
                                   **({"time_base_shift_s": shift, "time_base": "phone local time"} if shift else {}),
                                   **({"skipped_mixed_datalinks": skipped} if skipped else {}))
                    if not kept:
                        raise SnoopUnavailable("Snoop retrieved but holds no record inside the case window")
                    # Rank: covers the window start first, then full payloads over the btsnooz ring buffer
                    # (Samsung's btsnooz spans hours but keeps only truncated, filtered packets).
                    full = payload == "full"
                    candidate = (covers, full, datalink, kept, payload, method, records)
                    if best is None or (covers, full) > (best[0], best[1]):
                        best = candidate
                    if covers and full:
                        learned["works"] = method
                        break
                except SnoopUnavailable as error:
                    attempt.update(outcome="UNAVAILABLE", reason=str(error))
                    if method == "direct" or "no zip" in str(error):
                        learned["fails"][method] = str(error)  # Permission/build fact; stable per phone.
                except Exception as error:  # Timeouts, adb loss, corrupt archives: record and continue.
                    attempt.update(outcome="ERROR", reason=f"{type(error).__name__}: {error}"[:300])
                finally:
                    attempt["seconds"] = round(time.monotonic() - tick, 2)
            if best is None:
                receipt["reason"] = "No retrieval method returned snoop records for the case window"
                return receipt
            covers, _full, datalink, kept, payload, method, records = best
            path = directory / "btsnoop_hci.log"
            write_btsnoop(path, datalink, kept)
            receipt.update(status="MEASURED" if covers else "PARTIAL", method=method, payload=payload,
                           datalink=datalink, records=len(kept), bytes=path.stat().st_size,
                           sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                           coverage={"first_source_unix_s": round(unix_seconds(records[0][0]), 3),
                                     "last_source_unix_s": round(unix_seconds(records[-1][0]), 3)})
            if not covers:
                receipt["reason"] = "Snoop starts after the case window start (rotated or ring buffer); prefix missing"
            try:
                import btsnoop_summary
                summary = btsnoop_summary.write_summary(path)
                receipt["summary_sha256"] = hashlib.sha256(summary.read_bytes()).hexdigest()
            except Exception as error:
                receipt["summary_error"] = f"{type(error).__name__}: {error}"[:300]
            return receipt
        except Exception as error:
            receipt["reason"] = f"{type(error).__name__}: {error}"[:300]
            return receipt
        finally:
            shutil.rmtree(scratch, ignore_errors=True)
            receipt["seconds"] = round(time.monotonic() - began, 2)
            if logging_state is not None:
                receipt["logging_state"] = {key: logging_state.get(key) for key in ("snoop_mode", "snoop_enabled")}


def collect_case(adb, devices, phones, output, start_ns, end_ns, policy, logging_states=None, run=None, cancel=None):
    """Collect every participant's snoop into output/physical/<phone>/hci/ and write output/hci-snoop.json.
    Returns the receipt; never raises for retrieval problems."""
    output = Path(output)
    logging_states = logging_states or {}
    phones_receipt = {}

    def one(phone):
        state = logging_states.get(phone) or {}
        # auto collects only from phones whose snoop is confirmed on: an UNKNOWN state fell through to
        # a 2-3 min bugreport per phone per case and idled every phone (mesh4-79ad5fc5-loop11-1).
        # HCI_SNOOP_PHONES lists phones whose snoop was enabled in Developer options but whose
        # property is unreadable from the shell (Samsung, ColorOS), so auto still collects them.
        forced = phone in {p.strip() for p in os.environ.get("HCI_SNOOP_PHONES", "").split(",") if p.strip()}
        if policy == "auto" and state.get("snoop_enabled") != "ENABLED" and not forced:
            return {"phone": phone, "status": "UNMEASURED", "reason": "HCI snoop not confirmed enabled on the phone (auto mode)",
                    "logging_state": {key: state.get(key) for key in ("snoop_mode", "snoop_enabled")}}
        directory = output / "physical" / phone / "hci"
        directory.mkdir(parents=True, exist_ok=True)
        receipt = Collector(adb, devices[phone]["serial"], run=run).collect(start_ns, end_ns, directory, state, phone, cancel)
        if (directory / "btsnoop_hci.log").is_file():
            receipt["path"] = f"physical/{phone}/hci/btsnoop_hci.log"
            if (directory / "btsnoop_hci.summary.json").is_file():
                receipt["summary"] = f"physical/{phone}/hci/btsnoop_hci.summary.json"
        elif not any(directory.iterdir()):
            directory.rmdir()
        return receipt

    with futures.ThreadPoolExecutor(max_workers=max(1, len(phones))) as pool:
        for phone, receipt in zip(phones, pool.map(one, phones)):
            phones_receipt[phone] = receipt
    receipt = {"schema_version": 1, "policy": policy, "host_start_ns": start_ns, "host_end_ns": end_ns,
               "purpose": "Packet-level root cause of Bluetooth link failures; diagnostic, never a gate",
               "phones": phones_receipt}
    (output / "hci-snoop.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt
