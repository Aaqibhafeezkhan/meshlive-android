#!/usr/bin/env python3
"""Passive-sniffer check for phone test T14: does a Bluetooth HCI snoop contain an identity in the clear?

A btsnoop capture (tools/hci_snoop.py writes one per case) is parsed down to what a link sniffer sees after the
connection exists: ACL data is reassembled into L2CAP PDUs per connection and direction, the PDU payloads of each
channel are concatenated with their L2CAP framing removed (LE credit-based SDU lengths included), and ATT value
writes and notifications are concatenated with their opcode and handle removed. A needle (an identity SPKI, a node
ID, a 6-byte prefix, a capability block) is "visible" when it occurs in any of those streams or in the raw ACL bytes.

    python -B tools/bmh3_snoop_check.py CAPTURE.btsnoop --needle spki=<hex> --needle node=<hex> [--expect hidden|visible]

`--expect visible` is the control: a capture of a BMH2 call must show the SPKI (the check can see it), otherwise a
"hidden" verdict on a BMH3 capture proves nothing. Advertising reports (HCI events) are not scanned: the 48-bit
prefix or RDI in an advertisement is public by design and is the rotating-ID tracker's subject (rotid_tracker.py).
Exit status 0 when every needle matches the expectation, 1 otherwise, 2 for an unreadable capture.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from hci_snoop import read_btsnoop  # noqa: E402

H4_ACL = 2
ATT_CID = 4
ATT_VALUE_OPCODES = {0x12, 0x52, 0x1B, 0x1D, 0x16}  # write request/command, notification, indication, prepare write


def acl_packets(datalink: int, records) -> list[tuple[int, int, bytes]]:
    """(direction, handle, acl_payload_with_flags) for every ACL data packet; direction 0 = sent, 1 = received."""
    out = []
    for _timestamp, flags, _drops, _original, payload in records:
        if datalink == 1002:                       # H4: first byte is the packet type
            if not payload or payload[0] != H4_ACL:
                continue
            body = payload[1:]
        else:                                      # HCI UART/monitor variants are not supported
            continue
        if len(body) < 4:
            continue
        handle_flags, length = struct.unpack("<HH", body[:4])
        out.append((flags & 1, handle_flags, body[4:4 + length]))
    return out


def reassemble(packets):
    """Yield (direction, handle, cid, l2cap_payload) for each reassembled L2CAP PDU."""
    pending: dict[tuple[int, int], bytearray] = {}
    expected: dict[tuple[int, int], int] = {}
    for direction, handle_flags, data in packets:
        handle, pb = handle_flags & 0x0FFF, (handle_flags >> 12) & 3
        key = (direction, handle)
        if pb in (0, 2):                           # start of an L2CAP PDU
            pending[key] = bytearray(data)
            if len(data) >= 4:
                expected[key] = struct.unpack("<HH", data[:4])[0] + 4
        elif pb == 1 and key in pending:           # continuation
            pending[key].extend(data)
        else:
            continue
        buffer = pending.get(key)
        if buffer is not None and key in expected and len(buffer) >= expected[key]:
            pdu = bytes(buffer[:expected[key]])
            del pending[key], expected[key]
            length, cid = struct.unpack("<HH", pdu[:4])
            yield direction, handle, cid, pdu[4:4 + length]


def streams(path: Path) -> list[tuple[str, bytes]]:
    """Every byte stream a sniffer can follow, one per connection, direction and channel (never joined across them)."""
    datalink, records = read_btsnoop(path.read_bytes())
    packets = acl_packets(datalink, records)
    raw: dict[tuple[int, int], bytearray] = {}
    for direction, handle_flags, data in packets:
        raw.setdefault((direction, handle_flags & 0x0FFF), bytearray()).extend(data)
    result = [("acl_raw", bytes(v)) for v in raw.values()]
    sdu: dict[tuple[int, int, int], bytearray] = {}
    att: dict[tuple[int, int], bytearray] = {}
    for direction, handle, cid, payload in reassemble(packets):
        if cid == ATT_CID:
            if payload and payload[0] in ATT_VALUE_OPCODES and len(payload) > 3:
                att.setdefault((direction, handle), bytearray()).extend(payload[3:])
        elif cid >= 0x40 and len(payload) >= 2:    # LE credit-based or dynamic channel
            sdu.setdefault((direction, handle, cid), bytearray()).extend(payload)
    result += [("l2cap_payload", bytes(v)) for v in sdu.values()]
    result += [("att_values", bytes(v)) for v in att.values()]
    return result


def scan(path: Path, needles: dict[str, bytes]) -> dict[str, dict]:
    data = streams(path)
    report = {}
    for name, needle in needles.items():
        where: dict[str, int] = {}
        for kind, content in data:
            count = content.count(needle)
            if count:
                where[kind] = where.get(kind, 0) + count
        report[name] = {"visible": bool(where), "occurrences": where, "length": len(needle)}
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("capture", type=Path)
    parser.add_argument("--needle", action="append", default=[], metavar="NAME=HEX")
    parser.add_argument("--expect", choices=("hidden", "visible"), default="hidden")
    args = parser.parse_args(argv)
    try:
        needles = {}
        for item in args.needle:
            name, _, value = item.partition("=")
            needles[name] = bytes.fromhex(value)
        if not needles:
            parser.error("at least one --needle NAME=HEX")
        report = scan(args.capture, needles)
    except (OSError, ValueError, struct.error) as error:
        print(json.dumps({"error": str(error)}))
        return 2
    wanted = args.expect == "visible"
    ok = all(entry["visible"] == wanted for entry in report.values())
    print(json.dumps({"capture": str(args.capture), "expect": args.expect, "ok": ok, "needles": report}, indent=1))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
