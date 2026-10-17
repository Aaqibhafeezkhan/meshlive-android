"""Dual-role (relay) child-link scheduling from a phone's btsnoop HCI log (diagnostic, never a gate).

A relay is LE peripheral to its parent and LE central to its child. For every child connection that
overlaps a parent connection this reports the interval history, the child's anchor phase relative to the
parent's, the data-length / PHY events around the final interval, how long the child link was silent
before it ended, and a verdict:

  establish_failed  HCI 0x3E (Connection Failed to be Established)
  silent_timeout    HCI 0x08 after the child link had been silent for about the supervision timeout
  low_rate          alive, but under LOW_RATE_KBPS of ACL payload sent over its last stage (>= 1 s)
  carried           anything else

Anchors are inferred from host timestamps (the snoop has no on-air times): the parent anchor is the start of
each received burst minus the air time of its first PDU, the child event is the first Number-of-Completed-
Packets of each burst minus 0.45 ms. Expect +-0.5-1 ms; the phase is only meaningful when the link has
enough events (n_child / n_parent in the stage) and the circular concentration r is near 1.

With --swapped the roles are mirrored (measurement 7 of DECISIONS "Design RR"): the phone is the LE CENTRAL on the
parent link (it dials its parent) and the LE PERIPHERAL on the child link (the child dials it). The child's anchor is
then the start of each received burst and the parent's the first completed packet, and the report adds the parent
link's longest completed-packet gap while the child lived (parent_max_ncp_gap_s, parent_stalled at >= 1 s), because
the question there is whether a bad child phase starves the PARENT link.

usage: python -B tools/relay_dual_role_snoop.py LOG [LOG ...] [--json OUT] [--gap-ms 3.3] [--swapped]
Evidence: docs/DECISIONS.md "Relay dual-role scheduling (2026-10-11)"."""
import argparse
import datetime as dt
import json
import math
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hci_snoop import read_btsnoop, unix_seconds

LOW_RATE_KBPS = 50.0
PARENT_STALL_S = 1.0
REASONS = {0x08: "timeout", 0x13: "remote_terminated", 0x16: "local_terminated", 0x22: "ll_response_timeout",
           0x3D: "mic_failure", 0x3E: "failed_to_establish"}
CHILD_EVENT_OFFSET_MS = 0.45
MIN_STAGE_S = 0.04
MIN_CHILD_MARKS = 2  # a link that dies within 80 ms of the update leaves only two


def iso(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def events(path):
    """Decoded HCI records: dicts with t (unix s) and k (kind), plus kind-specific fields."""
    datalink, records = read_btsnoop(Path(path).read_bytes())
    out = []
    for timestamp, flags, _drops, _original, payload in records:
        if not payload or datalink != 1002:
            continue
        when, received, kind, body = unix_seconds(timestamp), bool(flags & 1), payload[0], payload[1:]
        if kind == 2 and len(body) >= 4:
            word, length = struct.unpack_from("<HH", body)
            out.append(dict(t=when, k="acl", h=word & 0xFFF, rx=received, ln=length))
        elif kind == 4 and len(body) >= 2:
            code, params = body[0], body[2:]
            if code == 0x13 and params:
                count = params[0]
                if len(params) >= 1 + 4 * count:
                    handles = struct.unpack_from("<%dH" % count, params, 1)
                    counts = struct.unpack_from("<%dH" % count, params, 1 + 2 * count)
                    out.extend(dict(t=when, k="ncp", h=h & 0xFFF, n=c) for h, c in zip(handles, counts))
            elif code == 0x05 and len(params) >= 4:
                out.append(dict(t=when, k="disc", h=struct.unpack_from("<H", params, 1)[0] & 0xFFF, reason=params[3]))
            elif code == 0x3E and params:
                _decode_le_meta(out, when, params)
        elif kind == 1 and len(body) >= 3:
            opcode, params = struct.unpack_from("<H", body)[0], body[3:]
            if opcode == 0x2032 and len(params) >= 5:
                out.append(dict(t=when, k="setphy", h=(params[0] | params[1] << 8) & 0xFFF, tx=params[3], rx=params[4]))
    return out


def _decode_le_meta(out, when, params):
    sub = params[0]
    if sub in (0x01, 0x0A) and len(params) >= 14:
        offset = 12 if sub == 0x01 else 24
        if len(params) >= offset + 6:
            interval, latency, timeout = struct.unpack_from("<HHH", params, offset)
            out.append(dict(t=when, k="conn", h=struct.unpack_from("<H", params, 2)[0] & 0xFFF, status=params[1],
                            role=params[4], itv=interval * 1.25, to=timeout * 10))
    elif sub == 0x03 and len(params) >= 10:
        interval, latency, timeout = struct.unpack_from("<HHH", params, 4)
        out.append(dict(t=when, k="upd", h=struct.unpack_from("<H", params, 2)[0] & 0xFFF, status=params[1],
                        itv=interval * 1.25))
    elif sub == 0x0C and len(params) >= 6:
        out.append(dict(t=when, k="phy", h=struct.unpack_from("<H", params, 2)[0] & 0xFFF, status=params[1], tx=params[4]))
    elif sub == 0x07 and len(params) >= 11:
        out.append(dict(t=when, k="dle", h=struct.unpack_from("<H", params, 1)[0] & 0xFFF,
                        values=struct.unpack_from("<HHHH", params, 3)))


def episodes(ev):
    conns = [e for e in ev if e["k"] == "conn" and e["status"] == 0]
    discs = [e for e in ev if e["k"] == "disc"]
    result = []
    for c in conns:
        end = next((d for d in discs if d["h"] == c["h"] and d["t"] > c["t"]), None)
        later = next((x for x in conns if x["h"] == c["h"] and x["t"] > c["t"]), None)
        t1, reason = (end["t"], end["reason"]) if end else (None, None)
        if later and (t1 is None or later["t"] < t1):
            t1, reason = later["t"], None
        result.append(dict(h=c["h"], role=c["role"], t0=c["t"], t1=t1, reason=reason, itv0=c["itv"], timeout_ms=c["to"]))
    return result


def circular_mean(values, period):
    if not values:
        return None, 0.0
    s = sum(math.sin(2 * math.pi * v / period) for v in values)
    c = sum(math.cos(2 * math.pi * v / period) for v in values)
    return (math.atan2(s, c) / (2 * math.pi) * period) % period, math.hypot(s, c) / len(values)


def air_ms(length, two_m=True):
    return (length + 15) * 8 / (2000.0 if two_m else 1000.0)


def clusters(items, gap_s, key):
    out = []
    for item in items:
        if out and key(item) - key(out[-1][-1]) < gap_s:
            out[-1].append(item)
        else:
            out.append([item])
    return out


def interval_timeline(ev, episode):
    timeline = [(episode["t0"], episode["itv0"])]
    end = episode["t1"] if episode["t1"] is not None else math.inf
    for e in ev:
        if e["k"] == "upd" and e["h"] == episode["h"] and e["status"] == 0 and episode["t0"] <= e["t"] <= end:
            timeline.append((e["t"], e["itv"]))
    return timeline


def interval_at(timeline, when):
    current = timeline[0][1]
    for stamp, interval in timeline:
        if stamp <= when:
            current = interval
    return current


def central_marks(ev, handle, window_start, window_stop, gap_s):
    """Event starts of a link this phone is central on: the first completed packet of each burst minus 0.45 ms."""
    completed = [e for e in ev if e.get("h") == handle and e["k"] == "ncp" and window_start <= e["t"] <= window_stop]
    return [c[0]["t"] - CHILD_EVENT_OFFSET_MS / 1000 for c in clusters(completed, gap_s, lambda e: e["t"])]


def peripheral_marks(ev, handle, window_start, window_stop, gap_s):
    """Event starts of a link this phone is peripheral on: each received burst minus the air time of its first PDU."""
    received = [e for e in ev if e.get("h") == handle and e["k"] == "acl" and e["rx"] and window_start <= e["t"] <= window_stop]
    return [c[0]["t"] - air_ms(c[0]["ln"]) / 1000 for c in clusters(received, gap_s, lambda e: e["t"])]


def stage_report(ev, child, parent, start, stop, interval, parent_interval, gap_s, swapped=False):
    window_start, window_stop = start + 0.02, min(stop, start + 3.0)
    acl = [e for e in ev if e.get("h") == child["h"] and e["k"] == "acl" and start <= e["t"] <= stop]
    span = max(stop - start, 1e-3)
    row = dict(start_s=round(start - child["t0"], 3), end_s=round(stop - child["t0"], 3), interval_ms=interval,
               parent_interval_ms=parent_interval,
               tx_kbps=round(sum(e["ln"] for e in acl if not e["rx"]) * 8 / span / 1000, 1))
    if swapped:
        row["rx_kbps"] = round(sum(e["ln"] for e in acl if e["rx"]) * 8 / span / 1000, 1)
    if interval != parent_interval or stop - start < 0.04:
        return row
    if swapped:
        child_marks = peripheral_marks(ev, child["h"], window_start, window_stop, gap_s)
        parent_marks = central_marks(ev, parent["h"], window_start, window_stop, gap_s)
    else:
        child_marks = central_marks(ev, child["h"], window_start, window_stop, gap_s)
        parent_marks = peripheral_marks(ev, parent["h"], window_start, window_stop, gap_s)
    if len(child_marks) < MIN_CHILD_MARKS or len(parent_marks) < 3:
        row.update(n_child=len(child_marks), n_parent=len(parent_marks))
        return row
    parent_phase, parent_r = circular_mean([(t * 1000) % parent_interval for t in parent_marks], parent_interval)
    child_phase, child_r = circular_mean([(t * 1000) % parent_interval for t in child_marks], parent_interval)
    delta = (child_phase - parent_phase) % parent_interval
    row.update(n_child=len(child_marks), n_parent=len(parent_marks), delta_ms=round(delta, 2),
               gap_after_ms=round(parent_interval - delta, 2), r_child=round(child_r, 2), r_parent=round(parent_r, 2))
    return row


def parent_gap(ev, parent, start, end):
    """Longest stretch without a completed packet on the parent link inside [start, end] (None without any)."""
    stamps = sorted(e["t"] for e in ev if e.get("h") == parent["h"] and e["k"] == "ncp" and start <= e["t"] <= end)
    if not stamps:
        return None
    points = [start] + stamps + [end]
    return max(b - a for a, b in zip(points, points[1:]))


def analyze(path, gap_ms=3.3, swapped=False):
    ev = events(path)
    eps = episodes(ev)
    parent_role, child_role = (0, 1) if swapped else (1, 0)
    parents = [e for e in eps if e["role"] == parent_role]
    reports = []
    for child in (e for e in eps if e["role"] == child_role):
        end = child["t1"] if child["t1"] is not None else (ev[-1]["t"] if ev else child["t0"])
        overlapping = [p for p in parents if p["t0"] < end and child["t0"] < (p["t1"] if p["t1"] is not None else math.inf)]
        if not overlapping:
            continue
        parent = overlapping[0]
        timeline, parent_timeline = interval_timeline(ev, child), interval_timeline(ev, parent)
        stages = []
        for index, (stamp, interval) in enumerate(timeline):
            stop = timeline[index + 1][0] if index + 1 < len(timeline) else end
            if stop - stamp >= MIN_STAGE_S:
                stages.append(stage_report(ev, child, parent, stamp, stop, interval,
                                           interval_at(parent_timeline, stamp), gap_ms / 1000, swapped))
        link = [e for e in ev if e.get("h") == child["h"] and e["k"] in ("acl", "ncp") and child["t0"] <= e["t"] <= end]
        last = max((e["t"] for e in link), default=None)
        final = timeline[-1][0]
        after = lambda kind: next((round(e["t"] - final, 3) for e in ev if e.get("h") == child["h"] and e["k"] == kind
                                   and final - 0.2 <= e["t"] <= end and e.get("status", 0) == 0), None)
        silence = round(end - last, 2) if child["t1"] is not None and last is not None else None
        reason = child["reason"]
        if reason == 0x3E:
            verdict = "establish_failed"
        elif reason == 0x08 and silence is not None and silence >= 0.9 * child["timeout_ms"] / 1000:
            verdict = "silent_timeout"
        elif (stages and stages[-1]["end_s"] - stages[-1]["start_s"] >= 1.0
              and stages[-1]["rx_kbps" if swapped else "tx_kbps"] < LOW_RATE_KBPS):
            verdict = "low_rate"
        else:
            verdict = "carried"
        report = dict(
            handle=child["h"], parent_handle=parent["h"], connected=iso(child["t0"]),
            ended=iso(child["t1"]) if child["t1"] is not None else None, life_s=round(end - child["t0"], 2),
            end_reason=REASONS.get(reason, reason), intervals=[[round(t - child["t0"], 3), i] for t, i in timeline],
            dle_after_final_interval_s=after("dle"), phy_after_final_interval_s=after("phy"),
            set_phy_after_final_interval_s=after("setphy"),
            last_completed_s_after_connect=round(last - child["t0"], 3) if last is not None else None,
            silence_before_end_s=silence, stages=stages, verdict=verdict)
        if swapped:
            gap = parent_gap(ev, parent, child["t0"], end)
            report.update(swapped=True, parent_max_ncp_gap_s=round(gap, 3) if gap is not None else None,
                          parent_stalled=gap is None or gap >= PARENT_STALL_S)
        reports.append(report)
    return reports


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("logs", nargs="+")
    parser.add_argument("--json", help="write the full report here")
    parser.add_argument("--gap-ms", type=float, default=3.3, help="idle gap that separates two events (default 3.3)")
    parser.add_argument("--swapped", action="store_true",
                        help="phone is central on the parent link and peripheral on the child link (Design RR measurement 7)")
    args = parser.parse_args(argv)
    full = {}
    for log in args.logs:
        reports = analyze(log, args.gap_ms, args.swapped)
        full[log] = reports
        print(f"== {log}: {len(reports)} child connection(s)")
        for r in reports:
            last = next((s for s in reversed(r["stages"]) if "delta_ms" in s), None)
            phase = f" child-parent {last['delta_ms']} ms (gap to next parent anchor {last['gap_after_ms']} ms)" if last else ""
            print(f"  h{r['handle']} {r['connected']} life {r['life_s']} s end {r['end_reason']} "
                  f"intervals {[i for _, i in r['intervals']]} dle+{r['dle_after_final_interval_s']} "
                  f"silence {r['silence_before_end_s']} -> {r['verdict']}{phase}"
                  + (f" parent_max_gap {r['parent_max_ncp_gap_s']} s{' STALLED' if r['parent_stalled'] else ''}"
                     if r.get("swapped") else ""))
    if args.json:
        Path(args.json).write_text(json.dumps(full, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
