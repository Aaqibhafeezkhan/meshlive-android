"""Synthetic btsnoop checks for tools/relay_dual_role_snoop.py (no phone data)."""
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import hci_snoop  # noqa: E402
import relay_dual_role_snoop as snoop  # noqa: E402

T0 = 1_800_000_000.0
PARENT, CHILD = 3, 4


def stamp(seconds):
    return hci_snoop.EPOCH_DELTA_US + int(round(seconds * 1e6))


def event(seconds, code, params, received=True):
    payload = bytes([4, code, len(params)]) + params
    return (stamp(seconds), 1 if received else 0, 0, len(payload), payload)


def le_meta(seconds, sub, params):
    return event(seconds, 0x3E, bytes([sub]) + params)


def conn_complete(seconds, handle, role, interval_ms):
    params = struct.pack("<BHBB6sHHHB", 0, handle, role, 0, bytes(6), int(interval_ms / 1.25), 0, 500, 0)
    return le_meta(seconds, 0x01, params)


def conn_update(seconds, handle, interval_ms):
    return le_meta(seconds, 0x03, struct.pack("<BHHHH", 0, handle, int(interval_ms / 1.25), 0, 500))


def data_length(seconds, handle):
    return le_meta(seconds, 0x07, struct.pack("<HHHHH", handle, 251, 2120, 251, 2120))


def completed(seconds, handle):
    return event(seconds, 0x13, struct.pack("<BHH", 1, handle, 1))


def disconnect(seconds, handle, reason):
    return event(seconds, 0x05, struct.pack("<BHB", 0, handle, reason))


def acl(seconds, handle, length, received):
    payload = bytes([2]) + struct.pack("<HH", handle, length) + bytes(length)
    return (stamp(seconds), 1 if received else 0, 0, len(payload), payload)


def parent_events(first, last, anchor_phase_s):
    """A 251-byte burst per 15 ms event; the host sees it one air time after the anchor (2M PHY)."""
    air = snoop.air_ms(251) / 1000
    rows, k = [], 0
    while True:
        anchor = anchor_phase_s + 0.015 * k
        k += 1
        if anchor < first:
            continue
        if anchor > last:
            return rows
        rows += [acl(anchor + air, PARENT, 251, True), acl(anchor + air + 0.0012, PARENT, 251, True)]


def write_log(records):
    handle = tempfile.NamedTemporaryFile(suffix=".log", delete=False)
    handle.close()
    hci_snoop.write_btsnoop(handle.name, 1002, sorted(records, key=lambda r: r[0]))
    return handle.name


def scenario(delta_s, child_events, end_reason, silent_s=None, last_s=None):
    parent_anchor = 0.0042
    child_final = 1.0
    rows = [conn_complete(T0, PARENT, 1, 15.0), conn_complete(T0 + 0.5, CHILD, 0, 45.0),
            conn_update(T0 + child_final, CHILD, 15.0), data_length(T0 + child_final + 0.016, CHILD)]
    rows += parent_events(T0 + 0.1, T0 + (last_s if last_s else 3.0), T0 + parent_anchor)
    first_child_anchor = T0 + parent_anchor + 0.015 * 70 + delta_s  # after the update at 1.0 s
    for k in range(child_events):
        rows.append(completed(first_child_anchor + 0.015 * k + 0.00045, CHILD))
        rows.append(acl(first_child_anchor + 0.015 * k + 0.0003, CHILD, 251, False))
    end = T0 + (last_s if last_s else 3.0)
    if silent_s is not None:
        end = first_child_anchor + 0.015 * (child_events - 1) + silent_s
    rows.append(disconnect(end, CHILD, end_reason))
    return write_log(rows)


class RelayDualRoleSnoopTest(unittest.TestCase):
    def analyze_one(self, path):
        try:
            reports = snoop.analyze(path)
        finally:
            Path(path).unlink(missing_ok=True)
        self.assertEqual(1, len(reports))
        return reports[0]

    def test_a_child_that_goes_silent_until_the_supervision_timeout_is_a_silent_timeout(self):
        report = self.analyze_one(scenario(delta_s=0.0115, child_events=5, end_reason=0x08, silent_s=5.04))
        self.assertEqual("silent_timeout", report["verdict"])
        self.assertEqual("timeout", report["end_reason"])
        self.assertAlmostEqual(5.04, report["silence_before_end_s"], places=2)
        self.assertAlmostEqual(0.016, report["dle_after_final_interval_s"], places=3)
        stage = report["stages"][-1]
        self.assertEqual(15.0, stage["interval_ms"])
        self.assertAlmostEqual(11.5, stage["delta_ms"], delta=0.3)
        self.assertAlmostEqual(3.5, stage["gap_after_ms"], delta=0.3)

    def test_a_child_that_keeps_exchanging_is_carried_at_a_different_phase(self):
        report = self.analyze_one(scenario(delta_s=0.0100, child_events=190, end_reason=0x16, last_s=3.9))
        self.assertEqual("carried", report["verdict"])
        stage = report["stages"][-1]
        self.assertAlmostEqual(10.0, stage["delta_ms"], delta=0.3)
        self.assertGreater(stage["r_child"], 0.95)
        self.assertGreater(stage["tx_kbps"], 50)

    def test_a_busy_little_link_that_sends_under_the_floor_is_low_rate(self):
        report = self.analyze_one(scenario(delta_s=0.0100, child_events=5, end_reason=0x16, last_s=3.9))
        self.assertEqual("low_rate", report["verdict"])

    def test_an_establishment_failure_is_reported_as_such(self):
        rows = [conn_complete(T0, PARENT, 1, 15.0), conn_complete(T0 + 0.5, CHILD, 0, 45.0),
                disconnect(T0 + 0.77, CHILD, 0x3E)] + parent_events(T0 + 0.1, T0 + 1.0, T0 + 0.0042)
        report = self.analyze_one(write_log(rows))
        self.assertEqual("establish_failed", report["verdict"])
        self.assertEqual("failed_to_establish", report["end_reason"])
        self.assertAlmostEqual(0.27, report["life_s"], places=2)

    def test_a_central_link_without_a_parent_is_not_a_relay_child(self):
        rows = [conn_complete(T0, CHILD, 0, 15.0), disconnect(T0 + 1.0, CHILD, 0x16)]
        path = write_log(rows)
        try:
            self.assertEqual([], snoop.analyze(path))
        finally:
            Path(path).unlink(missing_ok=True)

    def test_the_circular_mean_wraps_around_the_period(self):
        mean, r = snoop.circular_mean([14.8, 0.2, 14.9, 0.1], 15.0)
        self.assertLess(min(mean, 15.0 - mean), 0.2)
        self.assertGreater(r, 0.99)


if __name__ == "__main__":
    unittest.main()
