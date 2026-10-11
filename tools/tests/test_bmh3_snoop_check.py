"""bmh3_snoop_check: synthetic btsnoop captures with an identity split across ACL fragments and ATT writes."""
from pathlib import Path
import io
import json
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bmh3_snoop_check as check
import hci_snoop

NEEDLE = bytes(range(0x30, 0x30 + 40))
OTHER = bytes(range(0xA0, 0xA0 + 40))
HANDLE = 0x0040


def acl(handle, pb, data):
    body = struct.pack("<HH", handle | (pb << 12), len(data)) + data
    return b"\x02" + body


def l2cap(cid, payload):
    return struct.pack("<HH", len(payload), cid) + payload


def record(packet, received, index):
    return (index, 1 if received else 0, 0, len(packet), packet)


def write_capture(packets):
    handle, path = tempfile.mkstemp(suffix=".btsnoop")
    import os
    os.close(handle)
    hci_snoop.write_btsnoop(path, 1002, [record(p, r, i) for i, (p, r) in enumerate(packets)])
    return Path(path)


def run(path, *args):
    out = io.StringIO()
    with redirect_stdout(out):
        status = check.main([str(path), *args])
    return status, json.loads(out.getvalue())


class Bmh3SnoopCheckTest(unittest.TestCase):
    def test_a_needle_split_across_acl_fragments_of_one_credit_based_pdu_is_found(self):
        sdu = struct.pack("<H", 60) + b"\x00" * 10 + NEEDLE + b"\x00" * 10
        pdu = l2cap(0x0041, sdu)
        first, second = pdu[:30], pdu[30:]
        path = write_capture([(acl(HANDLE, 2, first), True), (acl(HANDLE, 1, second), True)])
        status, report = run(path, "--needle", "spki=" + NEEDLE.hex(), "--expect", "visible")
        self.assertEqual(status, 0, report)
        self.assertTrue(report["needles"]["spki"]["visible"])
        path.unlink()

    def test_a_needle_split_across_two_credit_based_pdus_of_one_sdu_is_found(self):
        pdu1 = l2cap(0x0041, struct.pack("<H", 80) + b"\x01" * 6 + NEEDLE[:20])
        pdu2 = l2cap(0x0041, NEEDLE[20:] + b"\x02" * 14)
        path = write_capture([(acl(HANDLE, 2, pdu1), False), (acl(HANDLE, 2, pdu2), False)])
        status, report = run(path, "--needle", "spki=" + NEEDLE.hex(), "--expect", "visible")
        self.assertEqual(status, 0, report)
        path.unlink()

    def test_a_needle_split_across_gatt_writes_is_found_without_the_att_headers(self):
        write1 = l2cap(check.ATT_CID, b"\x52" + struct.pack("<H", 0x0011) + NEEDLE[:25])
        write2 = l2cap(check.ATT_CID, b"\x52" + struct.pack("<H", 0x0011) + NEEDLE[25:])
        path = write_capture([(acl(HANDLE, 2, write1), False), (acl(HANDLE, 2, write2), False)])
        status, report = run(path, "--needle", "spki=" + NEEDLE.hex(), "--expect", "visible")
        self.assertEqual(status, 0, report)
        self.assertIn("att_values", report["needles"]["spki"]["occurrences"])
        path.unlink()

    def test_a_capture_without_the_needle_is_hidden_and_the_expectation_decides_the_exit_status(self):
        pdu = l2cap(0x0041, struct.pack("<H", 40) + OTHER)
        path = write_capture([(acl(HANDLE, 2, pdu), True)])
        status, report = run(path, "--needle", "spki=" + NEEDLE.hex())
        self.assertEqual(status, 0, report)
        self.assertFalse(report["needles"]["spki"]["visible"])
        status, _ = run(path, "--needle", "spki=" + NEEDLE.hex(), "--expect", "visible")
        self.assertEqual(status, 1)
        path.unlink()

    def test_directions_and_connections_are_not_mixed(self):
        # The first half is received on one connection, the second half sent on another: no needle.
        pdu1 = l2cap(0x0041, struct.pack("<H", 40) + NEEDLE[:20])
        pdu2 = l2cap(0x0041, NEEDLE[20:])
        path = write_capture([(acl(HANDLE, 2, pdu1), True), (acl(HANDLE + 1, 2, pdu2), False)])
        status, report = run(path, "--needle", "spki=" + NEEDLE.hex())
        self.assertEqual(status, 0, report)
        path.unlink()

    def test_an_unreadable_capture_is_exit_status_2(self):
        handle, name = tempfile.mkstemp()
        import os
        os.write(handle, b"not a capture")
        os.close(handle)
        status, report = run(Path(name), "--needle", "x=00")
        self.assertEqual(status, 2)
        self.assertIn("error", report)
        os.unlink(name)


if __name__ == "__main__":
    unittest.main()
