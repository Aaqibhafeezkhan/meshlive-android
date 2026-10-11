"""Passive-tracker analyzer for rotating discovery IDs (PLAN "Discovery privacy" test P5).

Acts as an adversary with one or more passive BLE scanners. Inputs are full-HCI btsnoop files from a
scanning phone (every LE advertising / scan-response report is extracted: legacy 0x3E/0x02 and extended
0x3E/0x0D) and/or scanner logcat files with `BENCHMARK adv_address_seen` lines. It decodes the MeshLive
announcements (22-byte v2/v5, compact UUIDs v1/v2, title pages v3, reach pages v4/v6; layouts ported from
docs/PROTOCOL.md and the Kotlin codecs), builds one "set" per (scanner, address, role identity), and links
sets across the declared UTC epoch boundaries by every field that repeats: address, RDI/prefix, route
token (except the documented logical stream token), recovery hint, reach-page identifiers, any opaque or
extra AD bytes. It also measures what it cannot call a field link: continuity (one set ends, one begins
in the silence gap), capability-bit fingerprints, advertising-interval/TX-power fingerprints, the boundary
offset and the silence gap.

Address reuse (hardening 2026-10-11): a legacy-only controller advertises a new set with the host's initiator
address, so a boundary restart changes the RDI but not the address. The tool counts "new ID under the same
address" per phone (BLOCKING for P5), treats a second address for one ID inside an epoch as expected (Android
rotates a set's address on its own 7 to 15 minute timer; INFO, reported as sequential or concurrent), compares each
phone with the capability it declared in its advertiser log (`rotid_address_reuse: source=capability`), and prints a
layout census (how many distinct version 5 layouts the fleet produces per kind; PLAN Design E).

Verdict "zero carry-over": FAIL when any blocking field link or stable-prefix (version 2 / reach v4)
exposure exists, UNMEASURED when fewer than --min-boundaries boundaries were observed on both sides, else
PASS. Documented allowed (default): logical_stream_token. Everything else that the tool measures but that
PROTOCOL lists as a stated residual (stream title, capability bits, continuity, timing) is reported as a
residual category and never silently counted as passed. Streaming: one pass over each btsnoop, memory is
bounded per MeshLive address. Nothing here talks to a phone.

Usage (the phone lane):
  python -B tools/rotid_tracker.py --snoop S22=<run>/physical/s22/hci/btsnoop_hci.log \\
      --advertiser-log S24=<run>/physical/s24/logcat.txt --output benchmarks/runs/<run>/analysis/rotid-tracker
Writes rotid-tracker.json and rotid-tracker.md into --output (a benchmarks/runs path or the OS temp dir).
"""
from __future__ import annotations

import argparse
import collections
import dataclasses
import datetime as dt
import gzip
import hashlib
import json
import re
import statistics
import struct
import sys
import uuid as uuidlib
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from hci_snoop import EPOCH_DELTA_US, HEADER  # noqa: E402
from rotid_phase0 import parse_fields  # noqa: E402

APP_SERVICE_UUID = "c8d5a101-9f88-4f65-a03d-7f5a2fb921fa"
APP_SERVICE_LE = uuidlib.UUID(APP_SERVICE_UUID).bytes[::-1]
MESH_COMPANY = 0xFFFF
COMPACT_MAGIC = 0xC8D6
EPOCH_SECONDS = 900
ROUTE_DOMAIN = b"bluetooth-video-route-v1"

FLAG_ACCEPTING, FLAG_RELAY, FLAG_STREAM, FLAG_GATT, FLAG_CLASSIC = 1, 2, 4, 8, 16
FLAG_PAUSED, FLAG_CALL, FLAG_RECOVERY = 32, 64, 128

BLOCKING, ALLOWED, RESIDUAL, INFO = "BLOCKING", "ALLOWED", "RESIDUAL", "INFO"
# Categories PROTOCOL documents as by-design carry-over. Only these are allowed unless --allow adds more.
DEFAULT_ALLOWED = {"logical_stream_token"}
CATEGORY_DOC = {
    "logical_stream_token": "PROTOCOL Phase 1b item 1 / RV14: a running public stream stays linkable by its logical token (by design)",
    "visible_window_prefix": "PROTOCOL Visible-by-handle window: every window is linkable to every other window (stated residual)",
    "stream_title": "PROTOCOL Public title pages: the advertised stream title is public stream content",
    "quasi_identifier": "PROTOCOL Threats that remain: stable capability bits are a few bits of quasi-identifier",
    "continuity": "PROTOCOL Threats that remain: a phone alone in an area is linkable by continuity",
    "timing_fingerprint": "Not documented: advertising interval/set count/TX power per advertiser stack (measured here)",
    "viewer_wait_token": "PROTOCOL Phase 1b items 2/3/6: a waiting viewer keeps the token it copied across a boundary (stated residual)",
}
NOT_OBSERVABLE = [
    "Classic permanent Bluetooth address (RV2; not an LE advertisement)",
    "Cleartext BMH2 hellos and identity revealed to anyone who connects (once per epoch)",
    "OS-paired devices resolving every address with the IRK",
    "The phone's own SCAN_REQ / CONNECT_IND addresses and the stack's RPA timer",
    "OS beacons (Find My Device, offline finding, Fast Pair) on their own schedule",
    "Version 2 interop downgrade of a viewer request (visible only while a request is on air; shown as stable_prefix when seen)",
]


# --------------------------------------------------------------------------------------------- decoders

def u16(data: bytes) -> int:
    return int.from_bytes(data, "big")


def route_mask(prefix: bytes) -> int:
    """StreamRouteToken.advertiserMask: FNV-1a 32 over the route domain and the six advertised bytes, bit 31 set."""
    value = 0x811C9DC5
    for byte in ROUTE_DOMAIN + prefix:
        value = ((value ^ byte) * 0x01000193) & 0xFFFFFFFF
    return value | 0x80000000


def announcement_kind(flags: int) -> str:
    if flags & FLAG_STREAM:
        return "stream_offer"
    if flags & FLAG_CALL:
        return "call_offer"
    if flags & FLAG_ACCEPTING:
        return "viewer_request"
    return "unknown"


def decode_announcement(data: bytes) -> dict:
    """The 22-byte manufacturer-data announcement, version 2 (fixed prefix) or 5 (RDI)."""
    if len(data) != 22:
        raise ValueError("Invalid discovery announcement length")
    version = data[0]
    if version not in (2, 5):
        raise ValueError("Unsupported announcement version")
    flags = data[7]
    result = {
        "layout": "announcement", "version": version, "id": data[1:7].hex(), "flags": flags,
        "caps": data[8], "token": int.from_bytes(data[9:13], "big"), "cost": data[13],
        "bitrate": u16(data[20:22]), "kind": announcement_kind(flags),
    }
    if flags & FLAG_RECOVERY:
        result.update(recovery_hint=data[14:20].hex(), seq=0, width=0, height=0, fps=0)
    else:
        result.update(seq=data[14], width=u16(data[15:17]), height=u16(data[17:19]), fps=data[19])
    return result


COMPACT_RESOLUTIONS = [(426, 240), (640, 360), (854, 480)]
COMPACT_FPS = [15, 24, 30]


def decode_compact(value) -> dict:
    """Compact iPhone UUID, version 1 (fixed prefix) and 2 (RDI); bit layout of PROTOCOL's compact table."""
    number = uuidlib.UUID(str(value)).int if not isinstance(value, uuidlib.UUID) else value.int
    msb, lsb = number >> 64, number & ((1 << 64) - 1)
    if msb >> 48 != COMPACT_MAGIC:
        raise ValueError("Not a compact discovery announcement")
    compact_version = (msb >> 46) & 3
    if compact_version not in (1, 2):
        raise ValueError("Unsupported compact announcement version")
    kind = (msb >> 44) & 3
    if kind == 3:
        raise ValueError("Reserved compact announcement kind")
    prefix_bits = ((msb & ((1 << 44) - 1)) << 4) | (lsb >> 60)
    prefix = prefix_bits.to_bytes(6, "big")
    paused = (lsb >> 57) & 1
    if paused and kind != 2:
        raise ValueError("Only stream offers can be paused")
    flags = (FLAG_GATT if (lsb >> 59) & 1 else 0) | (FLAG_RELAY if (lsb >> 58) & 1 else 0) | (FLAG_PAUSED if paused else 0)
    codecs = (lsb >> 54) & 7
    cost = (lsb >> 50) & 0xF
    token_field = (lsb >> 19) & 0x7FFFFFFF
    specific = lsb & 0x7FFFF
    result = {"layout": "compact", "version": 5 if compact_version == 2 else 2, "compact_version": compact_version,
              "id": prefix.hex(), "cost": cost, "recovery_hint": None}
    if kind == 0:
        if token_field != 0 or specific & ~(1 << 18):
            raise ValueError("Private-call offers carry no token or stream fields")
        result.update(kind="call_offer", flags=FLAG_ACCEPTING | FLAG_CALL | flags,
                      caps=codecs | (0x10 if specific else 0), token=0, seq=0, width=0, height=0, fps=0, bitrate=0)
    elif kind == 1:
        if specific & 0xFFF:
            raise ValueError("Reserved viewer-request bits are set")
        result.update(kind="viewer_request", flags=FLAG_ACCEPTING | flags, caps=codecs,
                      token=token_field | 0x80000000, seq=0, width=0, height=0, fps=0, bitrate=(specific >> 12) * 25)
    else:
        resolution = COMPACT_RESOLUTIONS[(specific >> 9) & 3] if ((specific >> 9) & 3) < 3 else (0, 0)
        fps_index = (specific >> 7) & 3
        result.update(kind="stream_offer", flags=FLAG_STREAM | flags, caps=codecs, token=token_field | 0x80000000,
                      seq=(specific >> 11) & 0xFF, width=resolution[0], height=resolution[1],
                      fps=COMPACT_FPS[fps_index] if fps_index < 3 else 0, bitrate=(specific & 0x7F) * 25)
    return result


def decode_title_page(data: bytes) -> dict:
    if not 9 <= len(data) <= 27 or data[0] != 3:
        raise ValueError("Title page length/version")
    page = data[7]
    index, count = page >> 4, page & 15
    if not (1 <= count <= 3 and index < count):
        raise ValueError("Title page index")
    return {"layout": "title", "version": 3, "kind": "title_page", "token": int.from_bytes(data[1:5], "big"),
            "digest": data[5:7].hex(), "index": index, "count": count, "fragment": data[8:].hex()}


def decode_reach_page(data: bytes) -> dict:
    if len(data) not in (18, 27) or data[0] not in (4, 6):
        raise ValueError("Reach page length/version")
    flags, page = data[7], data[8]
    if flags & ~0b1011:
        raise ValueError("Reserved relay flags")
    index, count = page >> 4, page & 15
    if not (1 <= count <= 4 and index < count):
        raise ValueError("Reach page index")
    relay = data[1:7].hex()
    entries, seen = [], set()
    for offset in range(9, len(data), 9):
        target = data[offset:offset + 6].hex()
        cost, age = data[offset + 6] >> 5, data[offset + 6] & 31
        if not 1 <= cost <= 4 or target == relay or target in seen:
            raise ValueError("Reach entry")
        seen.add(target)
        entries.append({"target": target, "cost": cost, "age": age, "via": data[offset + 7:offset + 9].hex()})
    return {"layout": "reach", "version": data[0], "kind": "reach_page", "id": relay, "relay_flags": flags,
            "index": index, "count": count, "entries": entries}


def decode_mesh_payload(payload: bytes) -> dict:
    """Manufacturer 0xFFFF payload -> decoded layout; ValueError when it matches no documented layout."""
    if not payload:
        raise ValueError("Empty payload")
    first = payload[0]
    if first in (2, 5):
        return decode_announcement(payload)
    if first == 3:
        return decode_title_page(payload)
    if first in (4, 6):
        return decode_reach_page(payload)
    raise ValueError("Unknown mesh payload")


# --------------------------------------------------------------------------------------------- AD and HCI

def parse_ad(data: bytes):
    items, malformed, i = [], False, 0
    while i < len(data):
        length = data[i]
        if length == 0:
            break
        if i + 1 + length > len(data):
            malformed = True
            break
        items.append((data[i + 1], bytes(data[i + 2:i + 1 + length])))
        i += 1 + length
    return items, malformed


def uuids_of(ad_type: int, body: bytes) -> list[str]:
    size = {0x02: 2, 0x03: 2, 0x04: 4, 0x05: 4, 0x06: 16, 0x07: 16}[ad_type]
    out = []
    for i in range(0, len(body) - size + 1, size):
        chunk = body[i:i + size][::-1]
        if size == 16:
            out.append(str(uuidlib.UUID(bytes=chunk)))
        else:
            out.append(chunk.hex())
    return out


def address_text(raw: bytes) -> str:
    return ":".join(f"{b:02x}" for b in reversed(raw))


def address_kind(address_type: int, address: str) -> str:
    if address_type in (0, 2):
        return "public" if address_type == 0 else "public_identity_resolved"
    if address_type == 0xFF:
        return "anonymous"
    top = int(address[:2], 16) >> 6
    base = {3: "static_random", 1: "resolvable_private", 0: "non_resolvable_private"}.get(top, "reserved_random")
    return base + ("_resolved" if address_type == 3 else "")


@dataclasses.dataclass
class Report:
    ts: float
    address_type: int
    address: str
    pdu: str
    rssi: int
    tx_power: int | None
    data: bytes


def parse_event(payload: bytes, ts: float):
    """Yield Reports from one H4 event packet (LE advertising report, extended advertising report)."""
    if len(payload) < 4 or payload[0] != 4 or payload[1] != 0x3E:
        return
    sub = payload[3]
    body = memoryview(payload)[4:]
    if sub == 0x02 and len(body):
        count, pos = body[0], 1
        for _ in range(count):
            if pos + 9 > len(body):
                return
            event_type, addr_type = body[pos], body[pos + 1]
            address = address_text(bytes(body[pos + 2:pos + 8]))
            length = body[pos + 8]
            if pos + 9 + length + 1 > len(body):
                return
            data = bytes(body[pos + 9:pos + 9 + length])
            rssi = struct.unpack("b", bytes(body[pos + 9 + length:pos + 10 + length]))[0]
            pos += 10 + length
            pdu = {0: "adv_ind", 1: "adv_direct", 2: "adv_scan", 3: "adv_nonconn", 4: "scan_rsp"}.get(event_type, f"type{event_type}")
            yield Report(ts, addr_type, address, pdu, rssi, None, data)
    elif sub == 0x0D and len(body):
        count, pos = body[0], 1
        for _ in range(count):
            if pos + 24 > len(body):
                return
            event_type = int.from_bytes(bytes(body[pos:pos + 2]), "little")
            addr_type = body[pos + 2]
            address = address_text(bytes(body[pos + 3:pos + 9]))
            tx_power = struct.unpack("b", bytes(body[pos + 12:pos + 13]))[0]
            rssi = struct.unpack("b", bytes(body[pos + 13:pos + 14]))[0]
            length = body[pos + 23]
            if pos + 24 + length > len(body):
                return
            data = bytes(body[pos + 24:pos + 24 + length])
            pos += 24 + length
            if (event_type >> 5) & 3:
                continue  # fragment or truncated extended data; the app advertises legacy sets only
            if event_type & 0x10:
                pdu = "scan_rsp" if event_type & 0x08 else ("adv_ind" if event_type & 1 else "adv_scan" if event_type & 2 else "adv_nonconn")
            else:
                pdu = "ext_scan_rsp" if event_type & 0x08 else "ext_adv"
            yield Report(ts, addr_type, address, pdu, rssi, None if tx_power == 127 else tx_power, data)


class HashingFile:
    def __init__(self, path: Path):
        self.handle = gzip.open(path, "rb") if str(path).endswith(".gz") else open(path, "rb", buffering=1 << 20)
        self.digest = hashlib.sha256()

    def read(self, size: int) -> bytes:
        chunk = self.handle.read(size)
        self.digest.update(chunk)
        return chunk

    def close(self):
        self.handle.close()


def iter_btsnoop(path: Path, summary: dict):
    """Stream (unix_seconds, payload) of H4 packets; the summary dict receives hash, counts and datalink."""
    handle = HashingFile(path)
    try:
        header = handle.read(16)
        if header[:8] != HEADER or len(header) < 16:
            raise ValueError(f"{path}: not a btsnoop file")
        datalink = struct.unpack(">I", header[12:16])[0]
        summary.update(datalink=datalink, records=0, skipped_truncated=0, unsupported_datalink=False)
        if datalink not in (1001, 1002):
            summary["unsupported_datalink"] = True
            return
        while True:
            head = handle.read(24)
            if len(head) < 24:
                break
            original, included, flags, _drops, timestamp = struct.unpack(">IIIIq", head)
            payload = handle.read(included)
            if len(payload) < included:
                summary["truncated_tail"] = True
                break
            summary["records"] += 1
            if included < original:
                summary["skipped_truncated"] += 1
                continue
            if datalink == 1001:
                if not (flags & 2 and flags & 1):
                    continue
                payload = b"\x04" + payload
            yield (timestamp - EPOCH_DELTA_US) / 1e6, payload
    finally:
        summary["sha256"] = None
        while handle.read(1 << 20):
            pass  # hash the remainder when the caller stopped early
        summary["sha256"] = handle.digest.hexdigest()
        handle.close()


# --------------------------------------------------------------------------------------------- collection

class PayloadStat:
    __slots__ = ("first", "last", "count", "rssi_sum", "rssi_n", "pdus", "tx")

    def __init__(self, ts):
        self.first = self.last = ts
        self.count = self.rssi_sum = self.rssi_n = 0
        self.pdus = set()
        self.tx = None


class Track:
    __slots__ = ("first", "last", "count", "payloads", "overflow", "gaps", "prev_adv", "tx_values")

    def __init__(self, ts):
        self.first = self.last = ts
        self.count = 0
        self.payloads: dict[bytes, PayloadStat] = {}
        self.overflow = 0
        self.gaps: list[float] = []
        self.prev_adv = None
        self.tx_values: set[int] = set()


MAX_PAYLOADS_PER_ADDRESS = 256
MAX_GAPS_PER_ADDRESS = 4096


def looks_meshlive(data: bytes) -> bool:
    """Cheap pre-filter, then the exact test: app service UUID, mesh manufacturer 0xFFFF or a compact UUID."""
    if not (APP_SERVICE_LE in data or b"\xff\xff\xff" in data or b"\xd6\xc8" in data):
        return False
    items, _ = parse_ad(data)
    for ad_type, body in items:
        if ad_type == 0xFF and body[:2] == b"\xff\xff":
            return True
        if ad_type in (0x06, 0x07):
            for text in uuids_of(ad_type, body):
                if text == APP_SERVICE_UUID or uuidlib.UUID(text).int >> 112 == COMPACT_MAGIC:
                    return True
    return False


class Collector:
    """One scanner's streaming aggregate of MeshLive addresses."""

    def __init__(self, label: str, offset_s: float, t_from: float | None, t_to: float | None):
        self.label, self.offset, self.t_from, self.t_to = label, offset_s, t_from, t_to
        self.tracks: dict[tuple, Track] = {}
        self.cache: dict[bytes, bool] = {}
        self.reports = self.meshlive_reports = self.other_reports = 0
        self.first_ts = self.last_ts = None

    def add(self, report: Report):
        ts = report.ts + self.offset
        if (self.t_from is not None and ts < self.t_from) or (self.t_to is not None and ts > self.t_to):
            return
        self.reports += 1
        self.first_ts = ts if self.first_ts is None else min(self.first_ts, ts)
        self.last_ts = ts if self.last_ts is None else max(self.last_ts, ts)
        key = (report.address_type, report.address)
        track = self.tracks.get(key)
        if track is None:
            verdict = self.cache.get(report.data)
            if verdict is None:
                if len(self.cache) > 20000:
                    self.cache.clear()
                verdict = self.cache[report.data] = looks_meshlive(report.data)
            if not verdict:
                self.other_reports += 1
                return
            track = self.tracks[key] = Track(ts)
        self.meshlive_reports += 1
        track.count += 1
        track.last = ts
        if report.tx_power is not None:
            track.tx_values.add(report.tx_power)
        stat = track.payloads.get(report.data)
        if stat is None:
            if len(track.payloads) >= MAX_PAYLOADS_PER_ADDRESS:
                track.overflow += 1
                return
            stat = track.payloads[report.data] = PayloadStat(ts)
        stat.last = ts
        stat.count += 1
        stat.pdus.add(report.pdu)
        stat.rssi_sum += report.rssi
        stat.rssi_n += 1
        if report.pdu in ("adv_ind", "adv_scan", "adv_nonconn", "ext_adv"):
            if track.prev_adv is not None and len(track.gaps) < MAX_GAPS_PER_ADDRESS:
                track.gaps.append(ts - track.prev_adv)
            track.prev_adv = ts


# --------------------------------------------------------------------------------------------- sets

@dataclasses.dataclass
class Seg:
    sid: str = ""
    source: str = ""
    source_kind: str = "snoop"
    address_type: int = 1
    address: str = ""
    kind: str = "unknown"
    layout: str = "opaque"
    version: int = 0
    ident: str = ""
    first: float = 0.0
    last: float = 0.0
    count: int = 0
    rssi_sum: int = 0
    rssi_n: int = 0
    gaps: list = dataclasses.field(default_factory=list)
    tx_power: list = dataclasses.field(default_factory=list)
    pdus: set = dataclasses.field(default_factory=set)
    values: dict = dataclasses.field(default_factory=lambda: collections.defaultdict(set))  # (category, field) -> {value}
    quasi: dict = dataclasses.field(default_factory=lambda: collections.defaultdict(set))
    quasi_fp: tuple = ()
    ngrams: set = dataclasses.field(default_factory=set)
    epochs: tuple = ()
    decode_errors: list = dataclasses.field(default_factory=list)
    aliases: set = dataclasses.field(default_factory=set)

    @property
    def rssi_mean(self):
        return self.rssi_sum / self.rssi_n if self.rssi_n else None

    @property
    def first_epoch(self):
        return self.epochs[0]

    @property
    def last_epoch(self):
        return self.epochs[-1]


def epoch_of(ts: float, epoch_s: int) -> int:
    return int(ts // epoch_s)


def segment_epochs(first: float, last: float, epoch_s: int, grace_s: float, early_s: float) -> tuple:
    """Epochs a set is on air in. The new ID may appear up to early_s before the boundary (clock skew) and the
    old one may linger up to grace_s after it (Doze-delayed check, silence): both are offsets, not carry-over."""
    begin = epoch_of(first + early_s, epoch_s)
    end = epoch_of(last - grace_s, epoch_s)
    return tuple(range(begin, max(begin, end) + 1))


def add_decoded(seg: Seg, decoded: dict, stat: PayloadStat):
    layout = decoded["layout"]
    seg.layout = layout
    seg.version = decoded["version"]
    if layout in ("announcement", "compact"):
        seg.kind = decoded["kind"]
        seg.ident = decoded["id"]
        seg.values[("identifier", "rdi" if decoded["version"] == 5 else "prefix")].add(decoded["id"])
        token = decoded["token"]
        if decoded["kind"] == "call_offer":
            if token:
                seg.values[("route_token", "route_token")].add(f"{token:08x}")
                seg.decode_errors.append("call offer carries a non-zero route token")
        elif token:
            if decoded["version"] == 5:
                seg.values[("route_token", "route_token")].add(f"{token:08x}")
            seg.values[("logical_stream_token", "logical_token")].add(
                f"{(token ^ route_mask(bytes.fromhex(decoded['id']))):08x}")
        hint = decoded.get("recovery_hint")
        if hint:
            seg.values[("recovery_hint" if decoded["version"] == 5 else "identifier",
                        "recovery_hint" if decoded["version"] == 5 else "recovery_failed_prefix")].add(hint)
        for name in ("flags", "caps", "cost", "width", "height", "fps", "bitrate"):
            seg.quasi[name].add(decoded[name])
    elif layout == "title":
        seg.kind = "title_page"
        seg.ident = f"{decoded['token']:08x}"
        seg.values[("route_token", "route_token")].add(seg.ident)
        seg.values[("stream_title", "title_digest")].add(decoded["digest"])
        seg.values[("stream_title", "title_fragment")].add(f"{decoded['index']}/{decoded['count']}:{decoded['fragment']}")
    elif layout == "reach":
        seg.kind = "reach_page"
        seg.ident = decoded["id"]
        field = "reach_relay" if decoded["version"] == 6 else "reach_relay_prefix"
        seg.values[("identifier", field)].add(decoded["id"])
        seg.quasi["relay_flags"].add(decoded["relay_flags"])
        for entry in decoded["entries"]:
            seg.values[("identifier", "reach_target" if decoded["version"] == 6 else "reach_target_prefix")].add(entry["target"])
            seg.quasi["reach_via"].add(entry["via"])
            seg.quasi["reach_cost"].add(entry["cost"])


KNOWN_AD = {0x01, 0x0A}  # flags and TX power are stack framing (reported as quasi/timing, never as links)


def tracks_to_segs(collector: Collector, ngram: int) -> list[Seg]:
    segs: list[Seg] = []
    for (address_type, address), track in collector.tracks.items():
        by_key: dict[tuple, Seg] = {}
        items = []  # (category, field, value, first, last)
        for data, stat in track.payloads.items():
            ads, malformed = parse_ad(data)
            for ad_type, body in ads:
                if ad_type == 0xFF and body[:2] == b"\xff\xff":
                    payload = body[2:]
                    try:
                        decoded = decode_mesh_payload(payload)
                        key = (decoded["layout"], decoded.get("kind"), decoded.get("id", f"{decoded.get('token', 0):08x}"), decoded["version"])
                        error = None
                    except ValueError as exc:
                        decoded, key, error = None, ("opaque", payload.hex()), str(exc)
                    seg = by_key.get(key)
                    if seg is None:
                        seg = by_key[key] = Seg(source=collector.label, address_type=address_type, address=address,
                                                first=stat.first, last=stat.last)
                    if decoded is not None:
                        add_decoded(seg, decoded, stat)
                    else:
                        seg.kind, seg.layout = "opaque", "opaque"
                        seg.values[("opaque_payload", "mfr_ffff_payload")].add(payload.hex())
                        seg.ngrams.update(gram.hex() for gram in (payload[i:i + ngram] for i in range(0, max(0, len(payload) - ngram + 1)))
                                          if len(set(gram)) > 1)
                        seg.decode_errors.append(f"undecodable mesh payload ({error}), {len(payload)} bytes")
                    seg.first, seg.last = min(seg.first, stat.first), max(seg.last, stat.last)
                    seg.count += stat.count
                    seg.rssi_sum += stat.rssi_sum
                    seg.rssi_n += stat.rssi_n
                    seg.pdus |= stat.pdus
                elif ad_type in (0x06, 0x07):
                    for text in uuids_of(ad_type, body):
                        number = uuidlib.UUID(text).int
                        if number >> 112 == COMPACT_MAGIC:
                            try:
                                decoded = decode_compact(text)
                                key = ("compact", decoded["kind"], decoded["id"], decoded["version"])
                                seg = by_key.get(key)
                                if seg is None:
                                    seg = by_key[key] = Seg(source=collector.label, address_type=address_type,
                                                            address=address, first=stat.first, last=stat.last)
                                add_decoded(seg, decoded, stat)
                                seg.first, seg.last = min(seg.first, stat.first), max(seg.last, stat.last)
                                seg.count += stat.count
                                seg.rssi_sum += stat.rssi_sum
                                seg.rssi_n += stat.rssi_n
                                seg.pdus |= stat.pdus
                            except ValueError:
                                items.append(("opaque_payload", "compact_uuid", text, stat.first, stat.last))
                        elif text != APP_SERVICE_UUID:
                            items.append(("ad_item", "service_uuid", text, stat.first, stat.last))
                elif ad_type in (0x02, 0x03, 0x04, 0x05):
                    for text in uuids_of(ad_type, body):
                        items.append(("ad_item", "service_uuid", text, stat.first, stat.last))
                elif ad_type in (0x08, 0x09):
                    name = body.decode("utf-8", "replace")
                    items.append(("local_name", "local_name", name, stat.first, stat.last))
                elif ad_type in (0x16, 0x20, 0x21):
                    items.append(("ad_item", f"service_data_{ad_type:02x}", body.hex(), stat.first, stat.last))
                elif ad_type == 0xFF:
                    company = int.from_bytes(body[:2], "little")
                    items.append(("ad_item", f"mfr_{company:04x}", body[2:].hex(), stat.first, stat.last))
                elif ad_type not in KNOWN_AD:
                    items.append(("ad_item", f"ad_{ad_type:02x}", body.hex(), stat.first, stat.last))
            if malformed:
                items.append(("ad_item", "malformed_ad", data.hex(), stat.first, stat.last))
        gaps = sorted(track.gaps)
        for seg in by_key.values():
            for category, field, value, first, last in items:
                if first <= seg.last + 5 and last >= seg.first - 5:
                    seg.values[(category, field)].add(value)
            seg.gaps = gaps
            seg.tx_power = sorted(track.tx_values)
            segs.append(seg)
    return segs


def logcat_segs(path: Path, label: str, offset_s: float, t_from, t_to, summary: dict) -> list[Seg]:
    pattern = re.compile(r"BENCHMARK adv_address_seen: (.*)$")
    segs: dict[tuple, Seg] = {}
    digest = hashlib.sha256()
    lines = 0
    with path.open("rb") as handle:
        for raw in handle:
            digest.update(raw)
            match = pattern.search(raw.decode("utf-8", "replace").rstrip("\r\n"))
            if not match:
                continue
            fields = parse_fields(match.group(1))
            try:
                ts = int(fields["wallMs"]) / 1000.0 + offset_s
                node, address = fields["node"].lower(), fields["address"].lower()
                version = int(fields.get("version", "0"))
            except (KeyError, ValueError):
                continue
            if (t_from is not None and ts < t_from) or (t_to is not None and ts > t_to):
                continue
            lines += 1
            seg = segs.get((address, node, version))
            if seg is None:
                seg = segs[(address, node, version)] = Seg(source=label, source_kind="logcat", address=address,
                                                           kind="unknown", layout="logcat", version=version, ident=node,
                                                           first=ts, last=ts)
                seg.values[("identifier", "rdi" if version == 5 else "prefix")].add(node)
            seg.first, seg.last = min(seg.first, ts), max(seg.last, ts)
            seg.count += 1
    summary.update(sha256=digest.hexdigest(), sightings=lines)
    return list(segs.values())


# --------------------------------------------------------------------------------------------- analysis

def utc(ts: float | None) -> str | None:
    return None if ts is None else dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def interval_stats(seg: Seg) -> dict:
    gaps = [g for g in seg.gaps if g > 0]
    if len(gaps) < 3:
        return {"samples": len(gaps)}
    return {"samples": len(gaps), "min_ms": round(gaps[0] * 1000, 1), "p10_ms": round(gaps[len(gaps) // 10] * 1000, 1),
            "median_ms": round(statistics.median(gaps) * 1000, 1)}


def assign_epochs(segs: list[Seg], epoch_s: int, grace_s: float, early_s: float):
    for index, seg in enumerate(sorted(segs, key=lambda s: (s.first, s.source, s.address)), start=1):
        seg.sid = f"S{index:03d}"
        seg.epochs = segment_epochs(seg.first, seg.last, epoch_s, grace_s, early_s)
        seg.quasi_fp = tuple(sorted((name, tuple(sorted(map(str, values)))) for name, values in seg.quasi.items()
                                    if name not in ("reach_via", "reach_cost")))


def in_windows(seg: Seg, windows, grace_s: float) -> bool:
    return any(seg.first >= start - grace_s and seg.last <= end + grace_s for start, end in windows)


def build_findings(segs: list[Seg], args, windows, allowed: set[str]) -> list[dict]:
    index: dict[tuple, list[Seg]] = collections.defaultdict(list)
    for seg in segs:
        index[("address", "address", f"{seg.address_type}:{seg.address}")].append(seg)
        for (category, field), values in seg.values.items():
            for value in values:
                index[(category, field, value)].append(seg)
        for gram in seg.ngrams:
            index[("opaque_payload", f"mfr_ffff_{args.ngram}gram", gram)].append(seg)
    segs_by_epoch: dict[int, int] = collections.Counter()
    for seg in segs:
        for epoch in seg.epochs:
            segs_by_epoch[epoch] += 1
    known = {value.lower() for value in args.known_constant}
    findings: list[dict] = []
    stable_done: set[str] = set()
    reuse_events, second_events = address_pairs(segs, args.overlap_s)
    reused_addresses = {event["address"] for event in reuse_events}

    def add(category, field, value, members, note, disposition=None, **extra):
        epochs = sorted({epoch for seg in members for epoch in seg.epochs})
        if disposition is None:
            disposition = ALLOWED if category in allowed else (RESIDUAL if category in CATEGORY_DOC and category not in ("viewer_wait_token",) else BLOCKING)
        findings.append({
            "category": category, "field": field, "value": value, "epochs": epochs,
            "sets": sorted({seg.sid for seg in members}), "kinds": sorted({seg.kind for seg in members}),
            "disposition": disposition, "note": note,
            "anonymity": {str(epoch): {"sets_with_value": len({seg.sid for seg in members if epoch in seg.epochs}),
                                       "sets_in_epoch": segs_by_epoch[epoch]} for epoch in epochs},
            **extra})

    # Stable-prefix exposure: any version 2 / compact v1 / reach v4 identifier on air.
    for seg in segs:
        if seg.version in (2, 4):
            stable = {value for (category, field), values in seg.values.items()
                      if category == "identifier" for value in values}
            for value in sorted(stable):
                if (seg.version, value) in stable_done:
                    continue
                stable_done.add((seg.version, value))
                members = [s for s in segs if value in {v for (c, _f), vals in s.values.items() if c == "identifier" for v in vals}
                           and s.version in (2, 4)]
                window = all(in_windows(s, windows, args.grace_s) for s in members)
                add("visible_window_prefix" if window else "stable_prefix", "fixed_prefix", value, members,
                    "version 2 / reach v4 fixed prefix inside a declared Visible-by-handle window" if window else
                    "stable-prefix exposure: a version 2 (fixed node prefix) advertisement is on air",
                    disposition=ALLOWED if window else BLOCKING)

    for (category, field, value), members in sorted(index.items(), key=lambda item: (item[0][0], item[0][1], item[0][2])):
        epochs = {epoch for seg in members for epoch in seg.epochs}
        if len(epochs) < 2 or value.lower() in known or (category == "local_name" and value.lower() == "phone"):
            continue  # the neutral adapter name "Phone" is the same on every phone
        if category == "identifier":
            members = [seg for seg in members if seg.version not in (2, 4)]
            epochs = {epoch for seg in members for epoch in seg.epochs}
            if len(epochs) < 2:
                continue
        if category == "address":
            address = value.split(":", 1)[1] if ":" in value else value
            if address in reused_addresses:
                continue  # reported below as address_reuse (the ID changed, the address did not)
            if all(seg.address_type in RESOLVED_ADDRESS_TYPES for seg in members):
                add("address_identity_resolved", "address", value, members,
                    "the scanner is bonded to this advertiser (or holds its IRK) and sees the resolved identity address for every set; "
                    "not an observation of the phone's advertising address", disposition=INFO)
                continue
            kinds = sorted({address_kind(seg.address_type, seg.address) for seg in members})
            add("address", "address", value, members, "advertising address repeated across a boundary", address_kind=kinds)
        elif category == "route_token":
            viewers = [seg for seg in members if seg.kind == "viewer_request"]
            others = [seg for seg in members if seg.kind != "viewer_request"]
            other_epochs = {epoch for seg in others for epoch in seg.epochs}
            if viewers and len({e for seg in viewers for e in seg.epochs} | other_epochs) >= 2 and len(other_epochs) <= 1:
                add("viewer_wait_token", field, value, members,
                    "a waiting viewer keeps the route token it copied from the offer across a boundary",
                    disposition=ALLOWED if "viewer_wait_token" in allowed else BLOCKING)
            else:
                add("route_token", field, value, members, "raw route token repeated across a boundary although the RDI rotated")
        elif category == "logical_stream_token":
            add(category, field, value, members, "logical stream token repeated across epochs (documented by design)")
        elif category == "stream_title":
            add(category, field, value, members, "public stream title content repeated; linkable only to the same public stream")
        elif category == "identifier":
            add("identifier", field, value, members, "rotating ID repeated across a boundary")
        else:
            add(category, field, value, members, f"{field} repeated across a boundary")
    by_sid = {seg.sid: seg for seg in segs}
    per_address: dict[str, list[dict]] = collections.defaultdict(list)
    for event in reuse_events:
        per_address[event["address"]].append(event)
    for address, events in sorted(per_address.items()):
        members = [by_sid[sid] for event in events for sid in (event["before"], event["after"]) if sid in by_sid]
        phones = sorted({name for event in events for name in event["phones"]})
        add("address_reuse", "address", address, members,
            f"new ID under the same advertising address at {len(events)} boundary/boundaries: a restart changed the ID but the "
            "address survived (legacy-only controller?), so a scanner links the epochs by address",
            address_kind=sorted({address_kind(members[0].address_type, address)}), boundaries=sorted({e["boundary_epoch"] for e in events}),
            ids=sorted({i for e in events for i in (e["before_id"], e["after_id"])}), phones=phones)
    for seg in segs:
        for error in seg.decode_errors:
            if "non-zero route token" in error:
                findings.append({"category": "call_offer_token", "field": "route_token", "value": None, "epochs": list(seg.epochs),
                                 "sets": [seg.sid], "kinds": [seg.kind], "disposition": INFO, "note": error})
    seconds: dict[tuple, list[dict]] = collections.defaultdict(list)
    for event in second_events:
        seconds[(event["kind"], event["id"])].append(event)
    for (kind, ident), events in sorted(seconds.items()):
        members = [by_sid[sid] for event in events for sid in event["sets"] if sid in by_sid]
        relations = sorted({event["relation"] for event in events})
        add("address_rotation_within_epoch", "address", ident, members,
            "a second advertising address for one ID inside an epoch (" + "/".join(relations) + "): expected, Android rotates a set's "
            "address on its own 7 to 15 minute timer; a concurrent pair is a second advertiser carrying the same ID",
            disposition=INFO, relations=relations, addresses=sorted({a for e in events for a in e["addresses"]}),
            phones=sorted({name for event in events for name in event["phones"]}))
    for number, finding in enumerate(findings, start=1):
        finding["id"] = f"F{number:03d}"
    return findings


RDI_VERSIONS = (5, 6)
RESOLVED_ADDRESS_TYPES = (2, 3)
RDI_KINDS = ("call_offer", "stream_offer", "viewer_request", "reach_page")


def address_pairs(segs: list[Seg], overlap_s: float) -> tuple[list[dict], list[dict]]:
    """(new ID under the same address, second address for one ID inside an epoch), from the RDI-bearing sets.

    The first is the legacy-controller privacy defect: the address survived a boundary while the ID changed.
    The second is expected (the stack's own 7 to 15 minute address timer) when the two addresses are on air one
    after the other ("sequential"); on air at the same time it is a second advertiser carrying the same ID
    (a relay that mirrors an offer, or a replay) and is reported as "concurrent". Neither is a field link by itself.
    """
    rdi_segs = [seg for seg in segs if seg.version in RDI_VERSIONS and (seg.kind in RDI_KINDS or seg.layout == "logcat") and seg.ident]
    # A scanner that is bonded to the advertiser (or holds its IRK) reports the resolved identity address (types 2 and
    # 3) for every set: the same address in every epoch whatever the phone does. Not evidence either way (RH7).
    rdi_segs = [seg for seg in rdi_segs if seg.address_type not in RESOLVED_ADDRESS_TYPES]
    by_address: dict[tuple, list[Seg]] = collections.defaultdict(list)
    for seg in rdi_segs:
        by_address[(seg.address_type, seg.address)].append(seg)
    reuse = []
    for (address_type, address), group in sorted(by_address.items(), key=lambda item: str(item[0])):
        group.sort(key=lambda s: (s.first, s.sid))
        for older, newer in zip(group, group[1:]):
            if older.ident == newer.ident or newer.first_epoch <= older.last_epoch:
                continue
            reuse.append({"address": address, "address_type": address_type, "before": older.sid, "after": newer.sid,
                          "before_id": older.ident, "after_id": newer.ident, "boundary_epoch": newer.first_epoch,
                          "gap_epochs": newer.first_epoch - older.last_epoch, "kinds": sorted({older.kind, newer.kind}),
                          "phones": sorted(older.aliases | newer.aliases), "sources": sorted({older.source, newer.source})})
    by_id: dict[tuple, list[Seg]] = collections.defaultdict(list)
    for seg in rdi_segs:
        by_id[(seg.kind, seg.ident)].append(seg)
    second = []
    for (kind, ident), group in sorted(by_id.items(), key=lambda item: str(item[0])):
        group.sort(key=lambda s: (s.first, s.sid))
        for index, older in enumerate(group):
            for newer in group[index + 1:]:
                if (older.address_type, older.address) == (newer.address_type, newer.address):
                    continue
                shared = sorted(set(older.epochs) & set(newer.epochs))
                if not shared:
                    continue
                relation = "sequential" if older.last <= newer.first + overlap_s else "concurrent"
                second.append({"kind": kind, "id": ident, "epochs": shared, "relation": relation,
                               "addresses": sorted({older.address, newer.address}), "sets": [older.sid, newer.sid],
                               "phones": sorted(older.aliases | newer.aliases)})
    return reuse, second


def address_reuse_report(reuse: list[dict], second: list[dict], advertisers: dict) -> dict:
    """Per-phone counts. A phone is a `--alias` name; events without one are listed under `unattributed`."""
    phones: dict[str, dict] = {}

    def row(name: str) -> dict:
        return phones.setdefault(name, {"new_id_same_address": 0, "addresses_reused": set(), "boundaries": set(),
                                        "second_address_same_id_sequential": 0, "second_address_same_id_concurrent": 0})

    for event in reuse:
        for name in event["phones"] or ["unattributed"]:
            entry = row(name)
            entry["new_id_same_address"] += 1
            entry["addresses_reused"].add(event["address"])
            entry["boundaries"].add(event["boundary_epoch"])
    for event in second:
        for name in event["phones"] or ["unattributed"]:
            row(name)[f"second_address_same_id_{event['relation']}"] += 1
    for name in advertisers:
        row(name)
    for name, entry in phones.items():
        declared = (advertisers.get(name) or {}).get("address_reuse_capability")
        entry["declared"] = declared["status"] if declared else None
        entry["addresses_reused"] = sorted(entry["addresses_reused"])
        entry["boundaries"] = sorted(entry["boundaries"])
        observed = entry["new_id_same_address"] > 0
        if declared is None:
            entry["consistency"] = "no capability line in an advertiser log for this phone"
        elif observed and declared["status"] == "fresh":
            entry["consistency"] = "REUSE OBSERVED BUT NOT DECLARED: the capability test missed this phone"
        elif not observed and declared["status"] == "suspected":
            entry["consistency"] = "declared suspected, no reuse observed (capability test may be a false positive)"
        elif declared["status"] == "unknown":
            entry["consistency"] = ("declared unknown (Android 12 runs the gd advertiser only behind a flag); "
                                    + ("reuse observed" if observed else "no reuse observed"))
        else:
            entry["consistency"] = "matches the declared capability"
    return {"events": reuse, "second_address_events": second, "phones": dict(sorted(phones.items())),
            "total_new_id_same_address": len(reuse),
            "note": "new ID under the same address is BLOCKING for P5 (a boundary restart that keeps the advertising address "
                    "links consecutive epochs); a second address for one ID inside an epoch is Android's own address timer and "
                    "only counted"}


def layout_census(segs: list[Seg]) -> dict:
    """How many distinct version 5 layouts (capability-bit fingerprints) the observed phones produce, per kind."""
    census: dict[str, dict] = {}
    for seg in segs:
        if seg.version not in RDI_VERSIONS or seg.kind not in RDI_KINDS or not seg.quasi_fp:
            continue
        entry = census.setdefault(seg.kind, {"sets": 0, "layouts": collections.Counter(), "fields": collections.defaultdict(collections.Counter)})
        entry["sets"] += 1
        entry["layouts"][seg.quasi_fp] += 1
        for name, values in seg.quasi_fp:
            entry["fields"][name][",".join(values)] += 1
    out = {}
    for kind, entry in sorted(census.items()):
        layouts = [{"fingerprint": {name: list(values) for name, values in fp}, "sets": count}
                   for fp, count in entry["layouts"].most_common()]
        out[kind] = {"sets": entry["sets"], "distinct_layouts": len(layouts), "largest_layout_share": round(layouts[0]["sets"] / entry["sets"], 3),
                     "layouts": layouts, "fields": {name: dict(counter.most_common()) for name, counter in sorted(entry["fields"].items())}}
    return out


def quasi_report(segs: list[Seg], boundaries: list[int]) -> list[dict]:
    rows = []
    for boundary in boundaries:
        old = [seg for seg in segs if boundary - 1 in seg.epochs and seg.kind != "opaque"]
        new = [seg for seg in segs if boundary in seg.epochs and seg.kind != "opaque"]
        count = lambda group: collections.Counter((seg.kind, seg.quasi_fp) for seg in group)
        before, after = count(old), count(new)
        shared = [key for key in before if key in after]
        unique = [key for key in shared if before[key] == 1 and after[key] == 1]
        rows.append({"boundary_epoch": boundary, "fingerprints_before": len(before), "fingerprints_after": len(after),
                     "shared_fingerprints": len(shared), "unique_shared_fingerprints": len(unique),
                     "linkable_by_capability_bits": bool(unique),
                     "unique_kinds": sorted({key[0] for key in unique})})
    return rows


def continuity(segs: list[Seg], boundaries: list[int], args) -> list[dict]:
    rows = []
    for boundary in boundaries:
        b_ts = boundary * args.epoch_seconds
        row = {"boundary_epoch": boundary, "boundary_utc": utc(b_ts), "per_source": {}}
        for source in sorted({seg.source for seg in segs}):
            mine = [seg for seg in segs if seg.source == source and seg.kind not in ("opaque", "unknown")]
            old = [seg for seg in mine if seg.last_epoch == boundary - 1 and seg.last >= b_ts - args.near_s and len(seg.epochs) == 1]
            new = [seg for seg in mine if seg.first_epoch == boundary and seg.first <= b_ts + args.near_s and len(seg.epochs) == 1]
            feasible = collections.defaultdict(list)
            for o in old:
                for n in new:
                    gap = n.first - o.last
                    if o.kind != n.kind or not -1.0 <= gap <= args.max_silence_s:
                        continue
                    if o.rssi_n >= 3 and n.rssi_n >= 3 and abs(o.rssi_mean - n.rssi_mean) > args.rssi_tol_db:
                        continue
                    feasible[o.sid].append(n)
            unique = []
            for o in old:
                candidates = feasible.get(o.sid, [])
                if len(candidates) == 1:
                    n = candidates[0]
                    if sum(1 for other in old if n in feasible.get(other.sid, [])) == 1:
                        unique.append((o, n))
            with_quasi = []
            for o in old:
                candidates = [n for n in feasible.get(o.sid, []) if n.quasi_fp == o.quasi_fp]
                if len(candidates) == 1 and sum(1 for other in old if candidates[0] in
                                                [n for n in feasible.get(other.sid, []) if n.quasi_fp == other.quasi_fp]) == 1:
                    with_quasi.append((o, candidates[0]))
            gaps = [round(n.first - o.last, 2) for o, n in unique]
            for o, n in unique:
                n.aliases |= o.aliases
                o.aliases |= n.aliases
            row["per_source"][source] = {
                "sets_before": len(old), "sets_after": len(new), "feasible_pairs": sum(len(v) for v in feasible.values()),
                "unique_pairs": [{"before": o.sid, "after": n.sid, "kind": o.kind, "silence_gap_s": round(n.first - o.last, 2),
                                  "old_last_offset_s": round(o.last - b_ts, 2), "new_first_offset_s": round(n.first - b_ts, 2)}
                                 for o, n in unique],
                "unique_pairs_with_capability_bits": len(with_quasi),
                "silence_gap_s": {"min": min(gaps), "median": statistics.median(gaps), "max": max(gaps)} if gaps else None,
                "anonymity_set_before": len(old), "anonymity_set_after": len(new),
                "linkable_by_continuity": bool(unique)}
        rows.append(row)
    return rows


def timing_report(segs: list[Seg]) -> dict:
    per_epoch = collections.defaultdict(list)
    for seg in segs:
        for epoch in seg.epochs:
            per_epoch[epoch].append(seg)
    epochs = {}
    for epoch, group in sorted(per_epoch.items()):
        by_source = collections.defaultdict(list)
        for seg in group:
            by_source[seg.source].append(seg)
        epochs[str(epoch)] = {
            "concurrent_sets_max_by_source": {src: _max_concurrent(items) for src, items in by_source.items()},
            "kinds": dict(collections.Counter(seg.kind for seg in group))}
    fingerprints = []
    for seg in segs:
        stats = interval_stats(seg)
        fingerprints.append({"set": seg.sid, "epochs": list(seg.epochs), "kind": seg.kind, "interval": stats,
                             "tx_power": seg.tx_power, "rssi_mean": None if seg.rssi_mean is None else round(seg.rssi_mean, 1)})
    return {"epochs": epochs, "sets": fingerprints,
            "note": "Intervals are scanner-limited estimates (small gaps approximate the advertising interval); "
                    "an adversary compares them across the boundary together with the set count and TX power."}


def _max_concurrent(items: list[Seg]) -> int:
    events = sorted([(seg.first, 1) for seg in items] + [(seg.last + 1e-6, -1) for seg in items])
    best = current = 0
    for _ts, delta in events:
        current += delta
        best = max(best, current)
    return best


def parse_advertiser_log(path: Path, epoch_s: int) -> dict:
    boundary_re = re.compile(r"BENCHMARK rotid_boundary: (.*)$")
    restart_re = re.compile(r"BENCHMARK rotid_restart: (.*)$")
    churn_re = re.compile(r"BENCHMARK adv_set_churn: (.*)$")
    reuse_re = re.compile(r"rotid_address_reuse: (.*)$")
    boundaries, restarts, churn, reuse = [], [], [], []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            for regex, bucket in ((boundary_re, boundaries), (restart_re, restarts), (churn_re, churn), (reuse_re, reuse)):
                match = regex.search(line)
                if match:
                    bucket.append(parse_fields(match.group(1)))
                    break
    out_boundaries = []
    for fields in boundaries:
        try:
            out_boundaries.append({"epoch": int(fields["epoch"]), "wall_offset_ms": int(fields["wallOffsetMs"])})
        except (KeyError, ValueError):
            continue
    silences = [{"role": f.get("role"), "silence_ms": int(f["silenceMs"])} for f in restarts if f.get("silenceMs", "").isdigit()]
    gaps = []
    stops = sorted((int(f["wallMs"]), f.get("label", ""), f.get("set", "")) for f in churn
                   if f.get("event") == "stop" and f.get("wallMs", "").isdigit())
    starts = sorted((int(f["wallMs"]), f.get("label", ""), f.get("set", "")) for f in churn
                    if f.get("event") == "start" and f.get("wallMs", "").isdigit())
    for start_ms, label, set_id in starts:
        before = [stop for stop in stops if stop[1] == label and stop[0] <= start_ms and start_ms - stop[0] < 60_000]
        if before:
            gaps.append({"label": label, "start_wall_ms": start_ms, "gap_ms": start_ms - before[-1][0],
                         "epoch": (start_ms // 1000) // epoch_s,
                         "offset_into_epoch_s": round(start_ms / 1000 - ((start_ms // 1000) // epoch_s) * epoch_s, 2)})
    offsets = [b["wall_offset_ms"] for b in out_boundaries]
    capability = None
    for fields in reuse:
        if fields.get("source") == "capability" and fields.get("status") in ("suspected", "fresh", "unknown"):
            capability = {"status": fields["status"], "extendedAdv": fields.get("extendedAdv"), "multiAdv": fields.get("multiAdv"),
                          "sdk": fields.get("sdk")}
    return {"path": str(path), "rotid_boundary": out_boundaries, "rotid_restart": silences, "set_restarts": gaps,
            "address_reuse_capability": capability,
            "boundary_offset_ms": {"min": min(offsets), "median": statistics.median(offsets), "max": max(offsets)} if offsets else None}


def parse_time(text: str) -> float:
    try:
        return float(text)
    except ValueError:
        value = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
        if value.tzinfo is None:
            value = value.replace(tzinfo=dt.timezone.utc)
        return value.timestamp()


def split_label(text: str, default: str):
    """`LABEL=path` (LABEL a simple name) or a bare path."""
    label, sep, path = text.partition("=")
    if sep and re.fullmatch(r"[A-Za-z0-9_.-]+", label):
        return label, Path(path)
    return default, Path(text)


def analyse(args) -> dict:
    t_from = parse_time(args.time_from) if args.time_from else None
    t_to = parse_time(args.time_to) if args.time_to else None
    offsets = {}
    for item in args.clock_offset:
        label, _, seconds = item.partition("=")
        offsets[label] = float(seconds)
    windows = []
    for item in args.visible_window:
        start, _, end = item.partition(",")
        windows.append((parse_time(start), parse_time(end)))
    allowed = DEFAULT_ALLOWED | set(args.allow)
    inputs, segs = [], []
    collectors: dict[str, Collector] = {}
    for number, text in enumerate(args.snoop, start=1):
        label, path = split_label(text, f"scanner{number}")
        summary = {"label": label, "path": str(path), "kind": "btsnoop"}
        # Files with the same label (btsnoop_hci.log and its rotated .last) are one scanner.
        collector = collectors.get(label)
        if collector is None:
            collector = collectors[label] = Collector(label, offsets.get(label, 0.0), t_from, t_to)
        before = (collector.reports, collector.meshlive_reports, collector.other_reports)
        for ts, payload in iter_btsnoop(path, summary):
            for report in parse_event(payload, ts):
                collector.add(report)
        summary.update(reports=collector.reports - before[0], meshlive_reports=collector.meshlive_reports - before[1],
                       other_reports=collector.other_reports - before[2],
                       first_utc=utc(collector.first_ts), last_utc=utc(collector.last_ts),
                       clock_offset_s=offsets.get(label, 0.0))
        inputs.append(summary)
    scanners = []
    for label, collector in collectors.items():
        found = tracks_to_segs(collector, args.ngram)
        scanners.append({"label": label, "meshlive_addresses": len(collector.tracks), "sets": len(found)})
        for item in inputs:
            if item["label"] == label and item["kind"] == "btsnoop":
                item["sets"] = len(found)
        segs += found
    for number, text in enumerate(args.logcat, start=1):
        label, path = split_label(text, f"logcat{number}")
        summary = {"label": label, "path": str(path), "kind": "logcat adv_address_seen"}
        found = logcat_segs(path, label, offsets.get(label, 0.0), t_from, t_to, summary)
        summary["sets"] = len(found)
        segs += found
        inputs.append(summary)
    assign_epochs(segs, args.epoch_seconds, args.grace_s, args.early_s)

    alias_values = {}
    for item in args.alias:
        name, _, values = item.partition("=")
        for value in values.split(","):
            if value.strip():
                alias_values[value.strip().lower()] = name
    for seg in segs:
        texts = {seg.address.lower()} | {value.lower() for values in seg.values.values() for value in values}
        for text in texts:
            if text in alias_values:
                seg.aliases.add(alias_values[text])

    findings = build_findings(segs, args, windows, allowed)
    epochs_all = sorted({epoch for seg in segs for epoch in seg.epochs})
    boundaries = list(range(epochs_all[0] + 1, epochs_all[-1] + 1)) if len(epochs_all) > 1 else []
    quasi = quasi_report(segs, boundaries)
    cont = continuity(segs, boundaries, args)
    timing = timing_report(segs)

    boundary_rows = []
    for boundary, row in zip(boundaries, cont):
        old = sum(1 for seg in segs if boundary - 1 in seg.epochs)
        new = sum(1 for seg in segs if boundary in seg.epochs)
        gaps = [pair["silence_gap_s"] for src in row["per_source"].values() for pair in src["unique_pairs"]]
        old_offsets = [pair["old_last_offset_s"] for src in row["per_source"].values() for pair in src["unique_pairs"]]
        new_offsets = [pair["new_first_offset_s"] for src in row["per_source"].values() for pair in src["unique_pairs"]]
        boundary_rows.append({
            "epoch": boundary, "utc": row["boundary_utc"], "sets_before": old, "sets_after": new, "measured": bool(old and new),
            "silence_gaps_s": gaps, "old_last_offset_s": old_offsets, "new_first_offset_s": new_offsets})
    measured = sum(1 for row in boundary_rows if row["measured"])

    per_alias = {}
    for name in sorted({alias for seg in segs for alias in seg.aliases}):
        mine = sorted((seg for seg in segs if name in seg.aliases), key=lambda s: s.first)
        offs = []
        for a, b in zip(mine, mine[1:]):
            if b.first_epoch == a.last_epoch + 1:
                boundary_ts = b.first_epoch * args.epoch_seconds
                offs.append({"boundary_epoch": b.first_epoch, "old_last_offset_s": round(a.last - boundary_ts, 2),
                             "new_first_offset_s": round(b.first - boundary_ts, 2), "silence_gap_s": round(b.first - a.last, 2)})
        per_alias[name] = {"sets": [seg.sid for seg in mine], "epochs": sorted({e for seg in mine for e in seg.epochs}),
                           "boundaries": offs}

    advertisers = {}
    for item in args.advertiser_log:
        label, path = split_label(item, "advertiser")
        advertisers[label] = parse_advertiser_log(path, args.epoch_seconds)

    reuse_events, second_events = address_pairs(segs, args.overlap_s)
    reuse_report = address_reuse_report(reuse_events, second_events, advertisers)
    census = layout_census(segs)
    blocking = [f for f in findings if f["disposition"] == BLOCKING]
    reasons = []
    if blocking:
        verdict = "FAIL"
        counts = collections.Counter(f["category"] for f in blocking)
        reasons.append("blocking carry-over: " + ", ".join(f"{c} x{n}" for c, n in sorted(counts.items())))
    elif measured < args.min_boundaries:
        verdict = "UNMEASURED"
        reasons.append(f"{measured} boundary/boundaries observed with MeshLive sets on both sides; need {args.min_boundaries}")
    else:
        verdict = "PASS"
        reasons.append(f"no field repeats across {measured} measured boundary/boundaries (documented allowed: {', '.join(sorted(allowed))})")
    residuals = []
    for category in sorted({f["category"] for f in findings if f["disposition"] in (ALLOWED, RESIDUAL)}):
        rows = [f for f in findings if f["category"] == category and f["disposition"] in (ALLOWED, RESIDUAL)]
        residuals.append({"category": category, "status": rows[0]["disposition"], "count": len(rows), "source": CATEGORY_DOC.get(category)})
    residuals.append({"category": "quasi_identifier", "status": RESIDUAL,
                      "count": sum(1 for row in quasi if row["linkable_by_capability_bits"]),
                      "source": CATEGORY_DOC["quasi_identifier"],
                      "note": "boundaries where a capability-bit fingerprint is unique on both sides"})
    residuals.append({"category": "continuity", "status": RESIDUAL,
                      "count": sum(1 for row in cont for src in row["per_source"].values() if src["linkable_by_continuity"]),
                      "source": CATEGORY_DOC["continuity"], "note": "boundaries (per scanner) with a unique silence-gap match"})
    residuals.append({"category": "timing_fingerprint", "status": RESIDUAL, "count": len(timing["sets"]),
                      "source": CATEGORY_DOC["timing_fingerprint"], "note": "see timing.sets; compare intervals across phones"})
    return {
        "schema_version": 1, "tool": "tools/rotid_tracker.py", "inputs": inputs,
        "parameters": {"epoch_seconds": args.epoch_seconds, "grace_s": args.grace_s, "early_s": args.early_s,
                       "min_boundaries": args.min_boundaries, "allowed_categories": sorted(allowed),
                       "visible_windows": [[utc(s), utc(e)] for s, e in windows], "max_silence_s": args.max_silence_s,
                       "rssi_tol_db": args.rssi_tol_db, "ngram": args.ngram,
                       "time_from": utc(t_from), "time_to": utc(t_to)},
        "verdict": {"zero_carry_over": verdict, "blocking_findings": len(blocking), "measured_boundaries": measured,
                    "boundaries_declared": len(boundary_rows), "reasons": reasons},
        "scanners": scanners, "boundaries": boundary_rows, "findings": findings, "residuals": residuals, "not_observable_here": NOT_OBSERVABLE,
        "continuity": cont, "capability_bits": quasi, "timing": timing, "per_alias": per_alias, "advertiser_logs": advertisers,
        "address_reuse": reuse_report, "layout_census": census,
        "sets": [set_json(seg, args) for seg in segs[:args.max_sets]], "sets_total": len(segs),
    }


def set_json(seg: Seg, args) -> dict:
    return {
        "id": seg.sid, "source": seg.source, "source_kind": seg.source_kind, "address": seg.address,
        "address_type": seg.address_type, "address_kind": address_kind(seg.address_type, seg.address) if seg.source_kind == "snoop" else None,
        "kind": seg.kind, "layout": seg.layout, "version": seg.version, "ident": seg.ident, "epochs": list(seg.epochs),
        "first_utc": utc(seg.first), "last_utc": utc(seg.last),
        "first_after_epoch_start_s": round(seg.first - seg.first_epoch * args.epoch_seconds, 2),
        "last_vs_epoch_end_s": round(seg.last - (seg.last_epoch + 1) * args.epoch_seconds, 2),
        "reports": seg.count, "rssi_mean": None if seg.rssi_mean is None else round(seg.rssi_mean, 1),
        "interval": interval_stats(seg), "tx_power": seg.tx_power, "aliases": sorted(seg.aliases),
        "capability_bits": {name: sorted(map(str, values)) for name, values in sorted(seg.quasi.items())},
        "decode_errors": seg.decode_errors[:3]}


def markdown(result: dict) -> str:
    verdict = result["verdict"]
    lines = [f"# Rotating-ID passive tracker: {verdict['zero_carry_over']}", ""]
    lines += [f"- Verdict (zero carry-over): **{verdict['zero_carry_over']}**; blocking findings {verdict['blocking_findings']}; "
              f"measured boundaries {verdict['measured_boundaries']} of {verdict['boundaries_declared']}.",
              f"- Reason: {'; '.join(verdict['reasons'])}", f"- Sets decoded: {result['sets_total']}", ""]
    lines.append("## Inputs")
    for item in result["inputs"]:
        lines.append(f"- {item['label']} ({item['kind']}) sha256 {str(item.get('sha256'))[:16]}: "
                     f"{item.get('meshlive_reports', item.get('sightings', 0))} MeshLive reports, {item['sets']} sets, "
                     f"{item.get('first_utc')} to {item.get('last_utc')}")
    lines += ["", "## Blocking findings"]
    blocking = [f for f in result["findings"] if f["disposition"] == BLOCKING]
    if not blocking:
        lines.append("- none")
    for f in blocking[:30]:
        lines.append(f"- {f['id']} {f['category']}/{f['field']} `{f['value']}` epochs {f['epochs']} sets {', '.join(f['sets'])}: {f['note']}")
    if len(blocking) > 30:
        lines.append(f"- ... {len(blocking) - 30} more in the JSON")
    lines += ["", "## Residual and allowed categories"]
    for r in result["residuals"]:
        lines.append(f"- {r['category']}: {r['status']} x{r['count']}" + (f" ({r['note']})" if r.get("note") else ""))
    lines += ["", "## Boundaries (offset and silence gap)", "| epoch | UTC | sets before | after | measured | silence gap s | old last offset s | new first offset s |",
              "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for row in result["boundaries"]:
        lines.append(f"| {row['epoch']} | {row['utc']} | {row['sets_before']} | {row['sets_after']} | {row['measured']} | "
                     f"{row['silence_gaps_s']} | {row['old_last_offset_s']} | {row['new_first_offset_s']} |")
    if result["per_alias"]:
        lines += ["", "## Per phone alias"]
        for name, info in result["per_alias"].items():
            lines.append(f"- {name}: epochs {info['epochs']}; boundaries {info['boundaries']}")
    reuse = result.get("address_reuse") or {}
    if reuse.get("phones") or reuse.get("events"):
        lines += ["", "## Address reuse per phone (new ID under the same address is BLOCKING for P5)",
                  "| phone | new ID, same address | addresses | boundaries | second address, sequential (expected) | second address, concurrent | declared | consistency |",
                  "| --- | --- | --- | --- | --- | --- | --- | --- |"]
        for name, info in reuse["phones"].items():
            lines.append(f"| {name} | {info['new_id_same_address']} | {len(info['addresses_reused'])} | {info['boundaries']} | "
                         f"{info['second_address_same_id_sequential']} | {info['second_address_same_id_concurrent']} | "
                         f"{info['declared']} | {info['consistency']} |")
    if result.get("layout_census"):
        lines += ["", "## Layout census (distinct version 5 capability layouts per kind; PLAN Design E)",
                  "| kind | sets | distinct layouts | largest layout share |", "| --- | --- | --- | --- |"]
        for kind, info in result["layout_census"].items():
            lines.append(f"| {kind} | {info['sets']} | {info['distinct_layouts']} | {info['largest_layout_share']} |")
    if result["advertiser_logs"]:
        lines += ["", "## Advertiser logs (ground truth)"]
        for name, info in result["advertiser_logs"].items():
            lines.append(f"- {name}: wallOffsetMs {info['boundary_offset_ms']}; silences {[s['silence_ms'] for s in info['rotid_restart']][:12]}")
    lines += ["", "## Not observable by this tool"] + [f"- {item}" for item in result["not_observable_here"]]
    return "\n".join(lines) + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--snoop", action="append", default=[], metavar="[LABEL=]PATH", help="btsnoop file of a scanning phone (.gz ok)")
    parser.add_argument("--logcat", action="append", default=[], metavar="[LABEL=]PATH", help="scanner logcat with adv_address_seen lines")
    parser.add_argument("--advertiser-log", action="append", default=[], metavar="NAME=PATH",
                        help="advertiser logcat: rotid_boundary/rotid_restart/adv_set_churn give the true boundary offset and silence")
    parser.add_argument("--output", type=Path, required=True, help="analysis directory (benchmarks/runs/<run>/analysis/... or OS temp)")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--epoch-seconds", "--epoch-s", dest="epoch_seconds", type=int, default=EPOCH_SECONDS,
                        help="rotating-ID epoch length; 900 unless the phones ran the DEBUG launch extra test_rotid_epoch_s=<60..900> "
                             "(give the same value; boundaries are then at multiples of it)")
    parser.add_argument("--from", dest="time_from", help="UTC start (ISO or unix seconds)")
    parser.add_argument("--to", dest="time_to", help="UTC end (ISO or unix seconds)")
    parser.add_argument("--clock-offset", action="append", default=[], metavar="LABEL=SECONDS",
                        help="seconds to ADD to that scanner's timestamps to get UTC (a phone clock 5 s fast needs -5)")
    parser.add_argument("--grace-s", type=float, default=None,
                        help="an old ID may stay on air this long after a boundary (offset, not carry-over); default 90, or a third of a short epoch")
    parser.add_argument("--early-s", type=float, default=None,
                        help="a new ID may appear this long before a boundary (clock skew); default 20, or a sixth of a short epoch")
    parser.add_argument("--min-boundaries", type=int, default=1)
    parser.add_argument("--visible-window", action="append", default=[], metavar="START,END",
                        help="declared Visible-by-handle window (UTC); version 2 inside it is allowed")
    parser.add_argument("--allow", action="append", default=[], metavar="CATEGORY", help="extra documented-allowed category (default: logical_stream_token)")
    parser.add_argument("--alias", action="append", default=[], metavar="NAME=VALUE[,VALUE]",
                        help="label sets that carry an address / RDI / token as a phone name")
    parser.add_argument("--known-constant", action="append", default=[], metavar="VALUE", help="value every phone shares; never a link")
    parser.add_argument("--overlap-s", type=float, default=5.0,
                        help="two addresses for one ID inside an epoch are sequential (Android's address timer) when they overlap by at most this")
    parser.add_argument("--max-silence-s", type=float, default=30.0)
    parser.add_argument("--near-s", type=float, default=120.0)
    parser.add_argument("--rssi-tol-db", type=float, default=12.0)
    parser.add_argument("--ngram", type=int, default=4)
    parser.add_argument("--max-sets", type=int, default=5000)
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if not 60 <= args.epoch_seconds <= 900:
        print("--epoch-seconds must be 60..900 (the range of the app's test_rotid_epoch_s)", file=sys.stderr)
        return 2
    if args.grace_s is None:
        args.grace_s = min(90.0, args.epoch_seconds / 3)
    if args.early_s is None:
        args.early_s = min(20.0, args.epoch_seconds / 6)
    if not (args.snoop or args.logcat):
        print("need at least one --snoop or --logcat", file=sys.stderr)
        return 2
    from benchmark_layout import output_path
    out = output_path(args.output, "runs")
    result = analyse(args)
    out.mkdir(parents=True, exist_ok=True)
    json_path, md_path = out / "rotid-tracker.json", out / "rotid-tracker.md"
    if (json_path.exists() or md_path.exists()) and not args.overwrite:
        print(f"{out} already holds a tracker report; pass --overwrite or a new --output", file=sys.stderr)
        return 2
    json_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    md_path.write_text(markdown(result), encoding="utf-8")
    verdict = result["verdict"]
    print(f"zero carry-over: {verdict['zero_carry_over']} ({'; '.join(verdict['reasons'])})")
    print(f"wrote {json_path} and {md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
