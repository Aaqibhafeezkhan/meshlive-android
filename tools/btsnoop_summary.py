"""Summarize a btsnoop HCI log per LE/ACL connection (diagnostic only).

Per connection handle: peer/role, connection parameters and updates, PHY updates, data length changes,
disconnect reason, L2CAP LE credit-based channels and credit grants, ATT error responses, ACL bytes and
long gaps between ACL packets while connected. Writes <log stem>.summary.json next to the log.

usage: python -B tools/btsnoop_summary.py LOG [--gap-ms 500]"""
import argparse
import datetime as dt
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hci_snoop import read_btsnoop, unix_seconds

PHY = {1: "1M", 2: "2M", 3: "Coded"}
# Controller vendor diagnostic stream on an ACL handle (observed 0x0edc on the ASUS/Qualcomm snoop);
# its bytes are counted but it has no link lifecycle, so gaps are not link stalls.
VENDOR_HANDLES = {0x0EDC: "vendor diagnostic (Qualcomm controller log)"}
ATT_ERRORS = {0x01: "Invalid Handle", 0x02: "Read Not Permitted", 0x03: "Write Not Permitted", 0x04: "Invalid PDU",
              0x05: "Insufficient Authentication", 0x06: "Request Not Supported", 0x07: "Invalid Offset",
              0x08: "Insufficient Authorization", 0x09: "Prepare Queue Full", 0x0A: "Attribute Not Found",
              0x0B: "Attribute Not Long", 0x0D: "Invalid Attribute Value Length", 0x0E: "Unlikely Error",
              0x0F: "Insufficient Encryption", 0x11: "Insufficient Resources", 0x13: "Value Not Allowed"}
REASONS = {0x05: "Authentication Failure", 0x08: "Connection Timeout", 0x13: "Remote User Terminated",
           0x14: "Remote Low Resources", 0x15: "Remote Power Off", 0x16: "Local Host Terminated",
           0x1A: "Unsupported Remote Feature", 0x22: "LL Response Timeout", 0x28: "Instant Passed",
           0x3B: "Unacceptable Connection Parameters", 0x3D: "MIC Failure", 0x3E: "Failed To Establish"}


def iso(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def packets(datalink, records):
    """Yield (unix_s, received, h4_type, payload)."""
    for timestamp, flags, _drops, _original, payload in records:
        if datalink == 1002:
            if not payload:
                continue
            kind, body = payload[0], payload[1:]
        else:  # 1001: type from direction/command flags
            kind = 4 if flags & 3 == 3 else 1 if flags & 2 else 2
            body = payload
        yield unix_seconds(timestamp), bool(flags & 1), kind, body


def summarize(path, gap_ms=500.0):
    datalink, records = read_btsnoop(Path(path).read_bytes())
    connections, unassigned = {}, []

    def connection(handle, when):
        return connections.setdefault(handle, {"handle": f"0x{handle:03x}", "first_seen": iso(when),
            "events": [], "acl": {"tx_packets": 0, "rx_packets": 0, "tx_bytes": 0, "rx_bytes": 0},
            "l2cap_channels": [], "credits_granted": {"tx": 0, "rx": 0}, "att_errors": [], "long_gaps": [],
            "_last": None})

    def event(handle, when, kind, **values):
        target = connection(handle, when)["events"] if handle is not None else unassigned
        target.append({"time": iso(when), "event": kind, **values})

    first = last = None
    for when, received, kind, body in packets(datalink, records):
        first = when if first is None else first
        last = when
        if kind == 1 and len(body) >= 3:  # Command
            opcode, parameters = struct.unpack_from("<H", body)[0], body[3:]
            if opcode == 0x2013 and len(parameters) >= 10:
                handle, low, high, latency, timeout = struct.unpack_from("<HHHHH", parameters)
                event(handle & 0xFFF, when, "LE_Connection_Update_Command", interval_ms=[low * 1.25, high * 1.25],
                      latency=latency, supervision_timeout_ms=timeout * 10)
            elif opcode == 0x2032 and len(parameters) >= 5:
                event((parameters[0] | parameters[1] << 8) & 0xFFF, when, "LE_Set_PHY_Command",
                      all_phys=parameters[2], tx_phys=parameters[3], rx_phys=parameters[4])
            elif opcode == 0x0406 and len(parameters) >= 3:
                event((parameters[0] | parameters[1] << 8) & 0xFFF, when, "Disconnect_Command",
                      reason=f"0x{parameters[2]:02x}", reason_name=REASONS.get(parameters[2]))
        elif kind == 4 and len(body) >= 2:  # Event
            code, parameters = body[0], body[2:]
            if code == 0x05 and len(parameters) >= 4:
                handle = (parameters[1] | parameters[2] << 8) & 0xFFF
                event(handle, when, "Disconnection_Complete", status=parameters[0],
                      reason=f"0x{parameters[3]:02x}", reason_name=REASONS.get(parameters[3]))
                connection(handle, when)["disconnected"] = iso(when)
                connection(handle, when)["_last"] = None
            elif code == 0x3E and parameters:
                sub, q = parameters[0], parameters[1:]
                if sub in (0x01, 0x0A, 0x29) and len(q) >= 17:
                    handle = (q[1] | q[2] << 8) & 0xFFF
                    peer = ":".join(f"{byte:02X}" for byte in reversed(q[5:11]))
                    offset = 11 + (12 if sub in (0x0A, 0x29) else 0)
                    if len(q) >= offset + 6:
                        interval, latency, timeout = struct.unpack_from("<HHH", q, offset)
                        if q[0] == 0:
                            item = connection(handle, when)
                            item.update(peer=peer, role="central" if q[3] == 0 else "peripheral", connected=iso(when), _last=None)
                            item.pop("disconnected", None)
                        event(handle if q[0] == 0 else None, when, "LE_Connection_Complete", status=q[0], peer=peer,
                              interval_ms=interval * 1.25, latency=latency, supervision_timeout_ms=timeout * 10)
                elif sub == 0x03 and len(q) >= 9:
                    handle = (q[1] | q[2] << 8) & 0xFFF
                    interval, latency, timeout = struct.unpack_from("<HHH", q, 3)
                    event(handle, when, "LE_Connection_Update_Complete", status=q[0], interval_ms=interval * 1.25,
                          latency=latency, supervision_timeout_ms=timeout * 10)
                elif sub == 0x07 and len(q) >= 10:
                    handle, tx_octets, tx_time, rx_octets, rx_time = struct.unpack_from("<HHHHH", q)
                    event(handle & 0xFFF, when, "LE_Data_Length_Change", max_tx_octets=tx_octets, max_tx_us=tx_time,
                          max_rx_octets=rx_octets, max_rx_us=rx_time)
                elif sub == 0x0C and len(q) >= 5:
                    event((q[1] | q[2] << 8) & 0xFFF, when, "LE_PHY_Update_Complete", status=q[0],
                          tx_phy=PHY.get(q[3], q[3]), rx_phy=PHY.get(q[4], q[4]))
        elif kind == 2 and len(body) >= 4:  # ACL
            word, length = struct.unpack_from("<HH", body)
            handle, boundary = word & 0xFFF, (word >> 12) & 3
            item = connection(handle, when)
            direction = "rx" if received else "tx"
            item["acl"][direction + "_packets"] += 1
            item["acl"][direction + "_bytes"] += length
            if handle in VENDOR_HANDLES:
                item["vendor"] = VENDOR_HANDLES[handle]
            elif item["_last"] is not None and (when - item["_last"]) * 1000 >= gap_ms:
                item["long_gaps"].append({"from": iso(item["_last"]), "to": iso(when),
                                          "ms": round((when - item["_last"]) * 1000, 1)})
            item["_last"] = when
            data = body[4:]
            if boundary in (0, 2) and len(data) >= 4:  # First fragment carries the basic L2CAP header.
                cid, payload = struct.unpack_from("<H", data, 2)[0], data[4:]
                if cid == 0x0005 and len(payload) >= 4:  # LE signaling
                    code, identifier = payload[0], payload[1]
                    signal = payload[4:]
                    if code == 0x14 and len(signal) >= 10:
                        psm, scid, mtu, mps, credits = struct.unpack_from("<HHHHH", signal)
                        item["l2cap_channels"].append({"time": iso(when), "request": direction, "psm": f"0x{psm:04x}",
                            "source_cid": f"0x{scid:04x}", "mtu": mtu, "mps": mps, "initial_credits": credits})
                    elif code == 0x15 and len(signal) >= 10:
                        dcid, mtu, mps, credits, result = struct.unpack_from("<HHHHH", signal)
                        item["l2cap_channels"].append({"time": iso(when), "response": direction,
                            "destination_cid": f"0x{dcid:04x}", "mtu": mtu, "mps": mps, "initial_credits": credits,
                            "result": result})
                    elif code == 0x16 and len(signal) >= 4:
                        _cid, credits = struct.unpack_from("<HH", signal)
                        item["credits_granted"][direction] += credits
                    elif code == 0x06 and len(signal) >= 4:
                        event(handle, when, "L2CAP_Disconnection_Request", direction=direction)
                    elif code == 0x12 and len(signal) >= 8:
                        low, high, latency, timeout = struct.unpack_from("<HHHH", signal)
                        event(handle, when, "L2CAP_Connection_Parameter_Update_Request", direction=direction,
                              interval_ms=[low * 1.25, high * 1.25], latency=latency, supervision_timeout_ms=timeout * 10)
                elif cid == 0x0004 and len(payload) >= 5 and payload[0] == 0x01:  # ATT Error Response
                    request, attribute, error = payload[1], struct.unpack_from("<H", payload, 2)[0], payload[4]
                    item["att_errors"].append({"time": iso(when), "direction": direction,
                        "request_opcode": f"0x{request:02x}", "handle": f"0x{attribute:04x}",
                        "error": f"0x{error:02x}", "error_name": ATT_ERRORS.get(error)})
                elif cid == 0x0004 and len(payload) >= 3 and payload[0] in (0x02, 0x03):
                    event(handle, when, "ATT_Exchange_MTU_" + ("Request" if payload[0] == 0x02 else "Response"),
                          direction=direction, mtu=struct.unpack_from("<H", payload, 1)[0])
    rows = []
    for handle in sorted(connections):
        item = connections[handle]
        item.pop("_last")
        item["long_gap_count"] = len(item["long_gaps"])
        item["long_gaps"] = sorted(item["long_gaps"], key=lambda gap: -gap["ms"])[:50]  # Bound the summary.
        rows.append(item)
    return {"schema_version": 1, "source": Path(path).name, "datalink": datalink, "records": len(records),
            "first": iso(first) if first is not None else None, "last": iso(last) if last is not None else None,
            "gap_threshold_ms": gap_ms, "connections": rows, "unassigned_events": unassigned}


def write_summary(path, gap_ms=500.0):
    path = Path(path)
    target = path.with_name(path.name.removesuffix(".log") + ".summary.json")
    target.write_text(json.dumps(summarize(path, gap_ms), indent=1) + "\n", encoding="utf-8")
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("log", type=Path)
    parser.add_argument("--gap-ms", type=float, default=500.0)
    args = parser.parse_args(argv)
    target = write_summary(args.log, args.gap_ms)
    summary = json.loads(target.read_text(encoding="utf-8"))
    print(json.dumps({"summary": str(target), "records": summary["records"], "connections": len(summary["connections"])}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
