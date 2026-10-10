"""Offline regressions for per-case HCI snoop retrieval and the btsnoop summary parser."""
import base64
import gzip
import importlib.util
import io
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).parents[1]
sys.path.insert(0, str(TOOLS))
import btsnoop_summary  # noqa: E402
import hci_snoop  # noqa: E402

SPEC = importlib.util.spec_from_file_location("benchmark_suite", TOOLS / "run_benchmark_suite.py")
suite = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(suite)

T0 = 1_760_000_000.0  # Unix seconds of the synthetic case start


def stamp(seconds):
    return int(round(seconds * 1e6)) + hci_snoop.EPOCH_DELTA_US


def event(code, parameters):
    return bytes([4, code, len(parameters)]) + parameters


def le_meta(sub, body):
    return event(0x3E, bytes([sub]) + body)


def acl(handle, cid, payload, boundary=2):
    l2cap = struct.pack("<HH", len(payload), cid) + payload
    return bytes([2]) + struct.pack("<HH", handle | boundary << 12, len(l2cap)) + l2cap


def synthetic(path, offset=0.0):
    """One LE connection with every summarized fact plus a 2 s stall."""
    peer = bytes([0x66, 0x55, 0x44, 0x33, 0x22, 0x11])
    rows = [
        (0.0, 3, le_meta(0x01, bytes([0]) + struct.pack("<H", 0x41) + bytes([0, 1]) + peer +
                         struct.pack("<HHHB", 36, 0, 500, 0))),
        (0.1, 2, bytes([1]) + struct.pack("<HB", 0x2013, 14) + struct.pack("<HHHHHHH", 0x41, 6, 6, 0, 500, 0, 0)),
        (0.2, 3, le_meta(0x03, bytes([0]) + struct.pack("<HHHH", 0x41, 6, 0, 500))),
        (0.3, 3, le_meta(0x0C, bytes([0]) + struct.pack("<H", 0x41) + bytes([2, 2]))),
        (0.4, 3, le_meta(0x07, struct.pack("<HHHHH", 0x41, 251, 2120, 251, 2120))),
        (0.5, 0, acl(0x41, 5, bytes([0x14, 1]) + struct.pack("<H", 10) + struct.pack("<HHHHH", 0x80, 0x40, 512, 247, 5))),
        (0.6, 1, acl(0x41, 5, bytes([0x15, 1]) + struct.pack("<H", 10) + struct.pack("<HHHHH", 0x41, 512, 247, 10, 0))),
        (0.7, 1, acl(0x41, 5, bytes([0x16, 2]) + struct.pack("<H", 4) + struct.pack("<HH", 0x41, 7))),
        (0.8, 1, acl(0x41, 4, bytes([0x01, 0x0A]) + struct.pack("<H", 0x2A) + bytes([0x0A]))),
        (2.8, 0, acl(0x41, 0x40, b"media")),
        (3.0, 3, event(0x05, bytes([0]) + struct.pack("<H", 0x41) + bytes([0x08]))),
    ]
    hci_snoop.write_btsnoop(path, 1002, [(stamp(T0 + offset + when), flags, 0, len(data), data)
                                         for when, flags, data in rows])


def snooz_dump(records_ms):
    """BTSNOOP_LOG_SUMMARY v2 block for [(unix_ms or unix_us, snooz_type, packet_without_h4)]."""
    body, previous = b"", None
    for when, kind, packet in records_ms:
        delta = 0 if previous is None else when - previous
        body += struct.pack("<HHIb", len(packet) + 1, len(packet) + 1, delta, kind) + packet
        previous = when
    blob = struct.pack("<bQ", 2, records_ms[-1][0]) + zlib.compress(body)
    return ("--- BEGIN:BTSNOOP_LOG_SUMMARY (1 bytes in) ---\n" + base64.b64encode(blob).decode() +
            "\n--- END:BTSNOOP_LOG_SUMMARY ---\n")


class FakeAdb:
    """Records adb argv; responds per method. `bugreport` writes a zip with rotated snoops."""

    def __init__(self, folder, direct=False, dumpsys="", bugreport=True, phone_now=T0 + 20, tz="+0000",
                 members=None, local_offset=0.0):
        self.folder, self.direct, self.dump, self.bug, self.now = folder, direct, dumpsys, bugreport, phone_now
        self.tz, self.local_offset = tz, local_offset
        self.members = members or {"FS/data/misc/bluetooth/logs/btsnoop_hci.log.10_10_02_46_42": -1.0,
                                   "FS/data/misc/bluetooth/logs/btsnoop_hci.log": 0.0}
        self.calls, self.serials = [], []

    def __call__(self, argv, **_):
        operation = argv[3:]
        self.calls.append(tuple(operation))
        self.serials.append(argv[2])
        def done(out="", code=0, err=""):
            return subprocess.CompletedProcess(argv, code, out, err)
        if operation[:2] == ["shell", "date"]:
            return done(self.tz if operation[2] == "+%z" else str(int(self.now)))
        if operation[:2] == ["shell", "getprop"]:
            return done("[persist.bluetooth.btsnooplogmode]: [full]\n[ro.build.type]: [user]\n")
        if operation[0] == "shell" and operation[1].startswith("find "):
            found = "/data/misc/bluetooth/logs/btsnoop_hci.log\n" if self.direct else ""
            return done(found, 1, "find: /data/misc/bluetooth/logs: Permission denied")
        if operation[0] == "pull":
            synthetic(Path(operation[2]))
            return done("1 file pulled")
        if operation[:2] == ["shell", "dumpsys"]:
            return done(self.dump)
        if operation[0] == "bugreport":
            if not self.bug:
                return done("", 1, "bugreport failed")
            with zipfile.ZipFile(operation[1], "w") as bundle:
                bundle.writestr("bugreport-x.txt", "no summary here")
                for index, (name, offset) in enumerate(self.members.items()):
                    source = self.folder / f"member-{index}.log"
                    synthetic(source, offset=offset + self.local_offset)
                    data = source.read_bytes()
                    bundle.writestr(name, gzip.compress(data) if name.endswith(".gz") else data)
                bundle.writestr("FS/data/misc/bluetooth/logs/firmware_events.log", "")
                bundle.writestr("FS/data/log/bt/bt_stack_notes.txt", "not a snoop" * 4)
            return done("Bug report copied")
        raise AssertionError(f"Unexpected adb operation {operation}")


class SummaryTests(unittest.TestCase):
    def test_synthetic_connection_facts(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "btsnoop_hci.log"
            synthetic(log)
            target = btsnoop_summary.write_summary(log, gap_ms=500)
            self.assertEqual(Path(folder) / "btsnoop_hci.summary.json", target)
            summary = json.loads(target.read_text(encoding="utf-8"))
        [link] = summary["connections"]
        self.assertEqual(("0x041", "11:22:33:44:55:66", "central"), (link["handle"], link["peer"], link["role"]))
        events = {row["event"]: row for row in link["events"]}
        self.assertEqual(45.0, events["LE_Connection_Complete"]["interval_ms"])
        self.assertEqual(7.5, events["LE_Connection_Update_Complete"]["interval_ms"])
        self.assertEqual(("2M", "2M"), (events["LE_PHY_Update_Complete"]["tx_phy"], events["LE_PHY_Update_Complete"]["rx_phy"]))
        self.assertEqual(251, events["LE_Data_Length_Change"]["max_tx_octets"])
        self.assertEqual(("0x08", "Connection Timeout"), (events["Disconnection_Complete"]["reason"],
                                                          events["Disconnection_Complete"]["reason_name"]))
        self.assertEqual(5, link["l2cap_channels"][0]["initial_credits"])
        self.assertEqual(10, link["l2cap_channels"][1]["initial_credits"])
        self.assertEqual({"tx": 0, "rx": 7}, link["credits_granted"])
        self.assertEqual(("0x0a", "Attribute Not Found", "0x002a"), (link["att_errors"][0]["error"],
            link["att_errors"][0]["error_name"], link["att_errors"][0]["handle"]))
        self.assertEqual(1, link["long_gap_count"])
        self.assertAlmostEqual(2000.0, link["long_gaps"][0]["ms"], delta=1)

    def test_snooz_summary_decodes_to_btsnoop_records(self):
        ms = int(T0 * 1000)
        text = snooz_dump([(ms, 0x10, bytes([0x05, 4, 0, 0x41, 0, 0x13])), (ms + 250, 0x20, bytes([0x06, 0x04, 3, 0x41, 0, 0x13]))])
        records = hci_snoop.snooz_records("header\n" + text + "tail")
        self.assertEqual(2, len(records))
        self.assertAlmostEqual(T0, hci_snoop.unix_seconds(records[0][0]), places=3)
        self.assertAlmostEqual(T0 + 0.25, hci_snoop.unix_seconds(records[1][0]), places=3)
        self.assertEqual((3, 4), (records[0][1], records[0][4][0]))  # Received event, H4 event type
        self.assertEqual((2, 1), (records[1][1], records[1][4][0]))  # Sent command


    def test_snooz_microsecond_header_as_written_by_aosp(self):
        # btif_debug_btsnoop.cc stores microseconds in last_timestamp_ms/delta_time_ms.
        us = int(T0 * 1e6)
        text = snooz_dump([(us, 0x10, bytes([0x0E, 1, 1])), (us + 250_000, 0x10, bytes([0x0E, 1, 1]))])
        records = hci_snoop.snooz_records(text)
        self.assertAlmostEqual(T0, hci_snoop.unix_seconds(records[0][0]), places=3)
        self.assertAlmostEqual(T0 + 0.25, hci_snoop.unix_seconds(records[1][0]), places=3)


class CollectorTests(unittest.TestCase):
    def setUp(self):
        hci_snoop.LEARNED.clear()
        self.temporary = tempfile.TemporaryDirectory()
        self.folder = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()
        hci_snoop.LEARNED.clear()

    def collect(self, fake, start=T0, end=T0 + 4):
        directory = self.folder / "case" / "hci"
        directory.mkdir(parents=True, exist_ok=True)
        collector = hci_snoop.Collector("adb", "serial-a", run=fake, clock=lambda: T0 + 20)
        return collector.collect(int(start * 1e9), int(end * 1e9), directory, phone="a"), directory

    def test_partial_ring_buffer_falls_back_to_bugreport_and_deletes_zip(self):
        ms = int((T0 + 2) * 1000)  # btsnooz starts after the window start: prefix missing
        fake = FakeAdb(self.folder, dumpsys=snooz_dump([(ms, 0x10, bytes([0x0E, 1, 1]))]))
        receipt, directory = self.collect(fake)
        self.assertEqual(("MEASURED", "bugreport", "full"), (receipt["status"], receipt["method"], receipt["payload"]))
        self.assertEqual(["direct", "dumpsys", "bugreport"], [row["method"] for row in receipt["attempts"]])
        self.assertFalse(receipt["attempts"][1]["covers_window_start"])
        self.assertEqual(["btsnoop_hci.log", "btsnoop_hci.summary.json"], sorted(p.name for p in directory.iterdir()))
        _, records = hci_snoop.read_btsnoop((directory / "btsnoop_hci.log").read_bytes())
        self.assertEqual(len(records), receipt["records"])
        self.assertTrue(all(T0 - 6 <= hci_snoop.unix_seconds(row[0]) <= T0 + 10 for row in records))
        self.assertEqual(len({row[0] for row in records}), len(records))  # Rotated copies deduplicated
        # The next case on this phone goes straight to the learned method and skips the permission failure.
        fake.calls.clear()
        receipt, _ = self.collect(fake)
        self.assertEqual("bugreport", receipt["attempts"][0]["method"])
        self.assertFalse(any(call[1].startswith("find ") for call in fake.calls if call[0] == "shell"))

    def test_covering_snooz_is_kept_only_when_no_full_snoop_exists(self):
        us = int((T0 - 10) * 1e6)
        rows = [(us + step * 1_000_000, 0x10, bytes([0x0E, 1, 1])) for step in range(25)]
        receipt, _ = self.collect(FakeAdb(self.folder, dumpsys=snooz_dump(rows)))
        self.assertEqual(("MEASURED", "bugreport", "full"), (receipt["status"], receipt["method"], receipt["payload"]))
        hci_snoop.LEARNED.clear()
        receipt, _ = self.collect(FakeAdb(self.folder, dumpsys=snooz_dump(rows), bugreport=False))
        self.assertEqual(("MEASURED", "dumpsys"), (receipt["status"], receipt["method"]))
        self.assertIn("truncated", receipt["payload"])

    def test_renamed_and_gzipped_oem_members_are_found_by_content(self):
        members = {"FS/data/log/bt/btsnoop_hci_20261010.cfa": -1.0, "FS/data/log/bt/btsnoop_hci.log.last.gz": 0.0}
        receipt, _ = self.collect(FakeAdb(self.folder, members=members))
        self.assertEqual(("MEASURED", "bugreport"), (receipt["status"], receipt["method"]))
        attempt = receipt["attempts"][-1]
        self.assertTrue(any("btsnoop_hci_20261010.cfa" in name for name in attempt["sources"]))
        self.assertIn("source_first_unix_s", attempt)

    def test_local_time_stamped_snoop_is_shifted_by_phone_utc_offset(self):
        fake = FakeAdb(self.folder, tz="+0530", local_offset=19800.0)
        receipt, _ = self.collect(fake)
        self.assertEqual(("MEASURED", "bugreport"), (receipt["status"], receipt["method"]))
        self.assertEqual(-19800, receipt["attempts"][-1]["time_base_shift_s"])
        # A UTC snoop on the same +0530 phone is not shifted.
        hci_snoop.LEARNED.clear()
        receipt, _ = self.collect(FakeAdb(self.folder, tz="+0530"))
        self.assertNotIn("time_base_shift_s", receipt["attempts"][-1])

    def test_direct_pull_is_used_when_readable(self):
        receipt, directory = self.collect(FakeAdb(self.folder, direct=True))
        self.assertEqual(("MEASURED", "direct"), (receipt["status"], receipt["method"]))
        self.assertEqual(1, len(receipt["attempts"]))

    def test_window_trimming_drops_records_outside_case(self):
        receipt, directory = self.collect(FakeAdb(self.folder, direct=True), start=T0 + 12, end=T0 + 20)
        self.assertEqual("UNMEASURED", receipt["status"])
        self.assertIn("No retrieval method", receipt["reason"])
        self.assertFalse((directory / "btsnoop_hci.log").exists())

    def test_every_failure_is_unmeasured_never_raised(self):
        def broken(argv, **_):
            raise FileNotFoundError("adb missing")
        receipt, _ = self.collect(broken)
        self.assertEqual("UNMEASURED", receipt["status"])
        receipt, _ = self.collect(FakeAdb(self.folder, bugreport=False))
        self.assertEqual("UNMEASURED", receipt["status"])
        self.assertTrue(all(row["outcome"] in {"UNAVAILABLE", "SKIPPED", "ERROR"} for row in receipt["attempts"]))
        self.assertFalse(list(self.folder.glob("**/*.zip")))

    def test_bugreport_zip_uses_configured_scratch_and_is_deleted(self):
        scratch = self.folder / "other-drive"
        fake = FakeAdb(self.folder)
        with patch.object(hci_snoop, "SCRATCH_ROOT", str(scratch)):
            receipt, _ = self.collect(fake)
        self.assertEqual("bugreport", receipt["method"])
        zips = [call[1] for call in fake.calls if call[0] == "bugreport"]
        self.assertTrue(Path(zips[0]).parent == scratch)
        self.assertFalse(list(scratch.iterdir()))

    def test_logging_state_is_a_recorded_fact(self):
        state = hci_snoop.snoop_logging_state("[persist.bluetooth.btsnooplogmode]: [disabled]\n"
                                              "[persist.log.tag.bluetooth]: [VERBOSE]\n[ro.x]: [y]\n")
        self.assertEqual(("disabled", "DISABLED"), (state["snoop_mode"], state["snoop_enabled"]))
        self.assertEqual({"persist.bluetooth.btsnooplogmode", "persist.log.tag.bluetooth"}, set(state["properties"]))
        self.assertEqual("UNKNOWN", hci_snoop.snoop_logging_state("")["snoop_enabled"])
        self.assertEqual("ENABLED", hci_snoop.snoop_logging_state("[persist.bluetooth.btsnooplogmode]: [full]")["snoop_enabled"])


class SuiteIntegrationTests(unittest.TestCase):
    def setUp(self):
        hci_snoop.LEARNED.clear()
        self.temporary = tempfile.TemporaryDirectory()
        self.output = Path(self.temporary.name).resolve() / "0001-case"
        (self.output / "physical").mkdir(parents=True)
        suite.write(self.output / "physical/manifest.json", {"schema_version": 1})
        self.devices = {"a": {"serial": "serial-a"}, "b": {"serial": "serial-b"}}

    def tearDown(self):
        self.temporary.cleanup()

    def test_off_touches_nothing(self):
        with patch.object(hci_snoop, "collect_case", side_effect=AssertionError("phone touched")):
            self.assertIsNone(suite.collect_hci_snoop("adb", self.devices, {"phones": ["a"], "hci_snoop": "off"},
                                                      self.output, 0, {}))
        self.assertFalse((self.output / "hci-snoop.json").exists())

    def test_manifest_binds_hashes_and_disabled_phone_is_unmeasured(self):
        fake = FakeAdb(Path(self.temporary.name), direct=True)
        evidence = {"a": {"bluetooth_logging": {"snoop_enabled": "ENABLED", "snoop_mode": "full"}},
                    "b": {"bluetooth_logging": {"snoop_enabled": "DISABLED", "snoop_mode": "disabled"}}}
        with patch.object(hci_snoop.subprocess, "run", side_effect=fake), \
             patch.object(hci_snoop.time, "time", return_value=T0 + 20), \
             patch.object(suite.time, "time_ns", return_value=int((T0 + 4) * 1e9)):
            suite.collect_hci_snoop("adb", self.devices, {"phones": ["a", "b"], "hci_snoop": "auto"},
                                    self.output, int(T0 * 1e9), evidence)
        manifest = suite.read(self.output / "physical/manifest.json")["hci_snoop"]
        self.assertEqual(suite.digest(self.output / "hci-snoop.json"), manifest["sha256"])
        self.assertEqual("a/hci/btsnoop_hci.log", manifest["phones"]["a"]["path"])
        self.assertEqual(suite.digest(self.output / "physical/a/hci/btsnoop_hci.log"), manifest["phones"]["a"]["sha256"])
        self.assertEqual("UNMEASURED", manifest["phones"]["b"]["status"])
        self.assertNotIn("serial-b", fake.serials)
        # Retention: the receipt and log are reachable from the protected manifest.
        files = suite.artifact_inventory(self.output)
        required = suite.artifact_dependencies(self.output, files, {self.output / "physical/manifest.json"})
        self.assertIn((self.output / "physical/a/hci/btsnoop_hci.log").resolve(), {path.resolve() for path in required})

    def test_cancelled_suite_skips_retrieval(self):
        import threading
        cancel = threading.Event()
        cancel.set()
        with patch.object(hci_snoop, "collect_case", side_effect=AssertionError("phone touched")):
            suite.collect_hci_snoop("adb", self.devices, {"phones": ["a"], "hci_snoop": "auto", "_cancel_event": cancel},
                                    self.output, 0, {})
        self.assertIn("Suite stopped", suite.read(self.output / "hci-snoop.json")["phones"]["a"]["reason"])


if __name__ == "__main__":
    unittest.main()
