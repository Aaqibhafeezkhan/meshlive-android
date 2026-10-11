"""rotid_tracker: decoders against the PROTOCOL vectors and the passive-tracker adversary on synthetic btsnoop files."""
import json
import struct
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(TOOLS))
import hci_snoop  # noqa: E402
import rotid_tracker as tracker  # noqa: E402

BASE = 1_791_676_800  # 2026-10-11T00:00:00Z, a multiple of 900 (epoch 1990752)
EPOCH = BASE // 900
APP = uuid.UUID(tracker.APP_SERVICE_UUID)


def ad(ad_type, body):
    return bytes([len(body) + 1, ad_type]) + body


def primary():
    return ad(0x01, b"\x06") + ad(0x07, APP.bytes[::-1])


def mfr(payload):
    return ad(0xFF, b"\xff\xff" + payload)


def announcement(version, rdi, flags, caps, token=0, cost=0, seq=0, width=0, height=0, fps=0, bitrate=0):
    return (bytes([version]) + bytes.fromhex(rdi) + bytes([flags, caps]) + struct.pack(">I", token) + bytes([cost, seq])
            + struct.pack(">HHBH", width, height, fps, bitrate))


def call_offer(rdi, version=5):
    return announcement(version, rdi, 0x41, 3)


def stream_offer(rdi, logical, version=5):
    token = logical ^ tracker.route_mask(bytes.fromhex(rdi))
    return announcement(version, rdi, 0x0C, 1, token=token, seq=1, width=640, height=360, fps=15, bitrate=300)


def title_page(token, title=b"Hi"):
    digest = tracker.hashlib.sha256(title).digest()[:2]
    return bytes([3]) + struct.pack(">I", token) + digest + bytes([0x01]) + title


def legacy_event(address, data, pdu=0, rssi=-60, address_type=1):
    raw = bytes.fromhex(address.replace(":", ""))[::-1]
    params = bytes([0x02, 1, pdu, address_type]) + raw + bytes([len(data)]) + data + struct.pack("b", rssi)
    return bytes([4, 0x3E, len(params)]) + params


def extended_event(address, data, event_type=0x13, tx_power=127, rssi=-60):
    raw = bytes.fromhex(address.replace(":", ""))[::-1]
    params = (bytes([0x0D, 1]) + struct.pack("<H", event_type) + bytes([1]) + raw + bytes([1, 0, 0xFF])
              + struct.pack("bb", tx_power, rssi) + struct.pack("<H", 0) + bytes([0]) + bytes(6) + bytes([len(data)]) + data)
    return bytes([4, 0x3E, len(params)]) + params


class Snoop:
    def __init__(self):
        self.records = []

    def add(self, unix, payload):
        stamp = int(unix * 1e6) + hci_snoop.EPOCH_DELTA_US
        self.records.append((stamp, 3, 0, len(payload), payload))

    def advertiser(self, address, start, stop, payloads, step=7.0, pdu_primary=True, rssi=-60, address_type=1):
        """Report an advertiser on a fixed cadence: ADV_IND with the service UUID, SCAN_RSP with each payload."""
        t = start
        while t < stop:
            if pdu_primary:
                self.add(t, legacy_event(address, primary(), 0, rssi, address_type))
            for payload in payloads:
                self.add(t + 0.01, legacy_event(address, payload, 4, rssi, address_type))
            t += step

    def write(self, path):
        hci_snoop.write_btsnoop(path, 1002, sorted(self.records))
        return path


def run(files, extra=(), snoop_labels=None):
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        argv = []
        for index, snoop in enumerate(files):
            path = snoop.write(folder / f"s{index}.log")
            argv += ["--snoop", f"scan{index}={path}"]
        out = folder / "out"
        argv += ["--output", str(out), *extra]
        code = tracker.main(argv)
        assert code == 0, code
        return json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8")), (out / "rotid-tracker.md").read_text(encoding="utf-8")


def rotation(rdis, addresses, builder, epochs=3, gap=(3.0, 5.0, 2.0), extra=None, epoch_s=900):
    """Three epochs of one advertiser; the set restarts at each boundary after a short silence."""
    snoop = Snoop()
    first = BASE // epoch_s
    for k in range(epochs):
        start = (first + k) * epoch_s + (0 if k == 0 else gap[k - 1 if k - 1 < len(gap) else -1])
        stop = (first + k + 1) * epoch_s - 1 if k < epochs - 1 else start + min(600, epoch_s - 10)
        payloads = [mfr(builder(rdis[k]))] + ([extra(k)] if extra else [])
        snoop.advertiser(addresses[k], start, stop, payloads, step=2.0)
    return snoop


class DecoderVectors(unittest.TestCase):
    def test_protocol_vectors(self):
        call = tracker.decode_announcement(bytes.fromhex("0544e69c0a7a7d410300000000000000000000000000"))
        self.assertEqual((call["version"], call["id"], call["flags"], call["caps"], call["kind"], call["token"]),
                         (5, "44e69c0a7a7d", 0x41, 3, "call_offer", 0))
        stream = tracker.decode_announcement(bytes.fromhex("05bb2d061f64800c0192529fc40001028001680f012c"))
        self.assertEqual((stream["kind"], stream["token"], stream["seq"], stream["width"], stream["height"], stream["fps"],
                          stream["bitrate"]), ("stream_offer", 0x92529FC4, 1, 640, 360, 15, 300))
        self.assertEqual(tracker.route_mask(bytes.fromhex("bb2d061f6480")), 0x8066C9BC)
        self.assertEqual(stream["token"] ^ tracker.route_mask(bytes.fromhex(stream["id"])), 0x12345678)
        viewer = tracker.decode_announcement(bytes.fromhex("05974ae410ea91090392529fc400000000000000012c"))
        self.assertEqual((viewer["kind"], viewer["id"], viewer["bitrate"]), ("viewer_request", "974ae410ea91", 300))
        recovery = tracker.decode_announcement(bytes.fromhex("05974ae410ea91890392529fc4004d832e9d4dc7012c"))
        self.assertEqual(recovery["recovery_hint"], "4d832e9d4dc7")

    def test_compact_vectors(self):
        call = tracker.decode_compact("C8D6844E-69C0-A7A7-D0C0-000000000000")
        self.assertEqual((call["version"], call["id"], call["kind"], call["caps"]), (5, "44e69c0a7a7d", "call_offer", 3))
        v1 = tracker.decode_compact("C8D64010-2030-4050-60C0-000000000000")
        self.assertEqual((v1["version"], v1["id"]), (2, "010203040506"))
        relay = tracker.decode_compact("C8D64010-2030-4050-60C0-000000040000")
        self.assertEqual(relay["caps"], 3 | 0x10)
        stream = tracker.decode_compact("C8D66A1B-2C3D-4E5F-6E46-F56DF77C2A0C")
        self.assertEqual((stream["id"], stream["token"], stream["seq"], stream["width"], stream["height"], stream["fps"],
                          stream["bitrate"], stream["cost"]), ("a1b2c3d4e5f6", 0xDEADBEEF, 0x85, 640, 360, 15, 300, 1))
        self.assertTrue(stream["flags"] & tracker.FLAG_GATT and stream["flags"] & tracker.FLAG_PAUSED)
        viewer = tracker.decode_compact("C8D65010-2030-4050-60C0-D5E6F780C000")
        self.assertEqual((viewer["kind"], viewer["token"], viewer["bitrate"]), ("viewer_request", 0x9ABCDEF0, 300))
        with self.assertRaises(ValueError):
            tracker.decode_compact("C8D6C010-2030-4050-60C0-000000000000")  # reserved kind 3

    def test_reach_and_title_vectors(self):
        page = tracker.decode_reach_page(bytes.fromhex("0644e69c0a7a7d0901fecba64983fd23fecb"))
        self.assertEqual((page["version"], page["id"], page["relay_flags"]), (6, "44e69c0a7a7d", 9))
        self.assertEqual(page["entries"], [{"target": "fecba64983fd", "cost": 1, "age": 3, "via": "fecb"}])
        two = tracker.decode_reach_page(bytes.fromhex("0644e69c0a7a7d0912fecba64983fd5fa1b2974ae410ea9120974a"))
        self.assertEqual([(e["target"], e["cost"], e["age"], e["via"]) for e in two["entries"]],
                         [("fecba64983fd", 2, 31, "a1b2"), ("974ae410ea91", 1, 0, "974a")])
        self.assertEqual((two["index"], two["count"]), (1, 2))
        title = tracker.decode_title_page(bytes.fromhex("038123456736390148 69".replace(" ", "")))
        self.assertEqual((title["token"], title["digest"], title["fragment"]), (0x81234567, "3639", "4869"))
        with self.assertRaises(ValueError):
            tracker.decode_reach_page(bytes.fromhex("0644e69c0a7a7d0901fecba64983fd23fecb")[:-1] + b"\x00\x00")

    def test_version_and_layout_rejects(self):
        with self.assertRaises(ValueError):
            tracker.decode_mesh_payload(bytes([7]) + bytes(21))
        with self.assertRaises(ValueError):
            tracker.decode_announcement(bytes([5]) + bytes(20))


class HciParsing(unittest.TestCase):
    def test_legacy_and_extended_reports(self):
        reports = list(tracker.parse_event(legacy_event("aa:bb:cc:dd:ee:ff", primary(), 0, -42), 10.0))
        self.assertEqual((reports[0].address, reports[0].pdu, reports[0].rssi), ("aa:bb:cc:dd:ee:ff", "adv_ind", -42))
        extended = list(tracker.parse_event(extended_event("11:22:33:44:55:66", mfr(call_offer("44e69c0a7a7d")), 0x1B, tx_power=-4), 11.0))
        self.assertEqual((extended[0].address, extended[0].pdu, extended[0].tx_power), ("11:22:33:44:55:66", "scan_rsp", -4))
        self.assertEqual(list(tracker.parse_event(extended_event("11:22:33:44:55:66", b"\x02\x01\x06", 0x33), 1.0)), [])

    def test_truncated_file_keeps_complete_records(self):
        snoop = Snoop()
        snoop.advertiser("aa:bb:cc:dd:ee:01", BASE + 5, BASE + 30, [mfr(call_offer("44e69c0a7a7d"))])
        with tempfile.TemporaryDirectory() as directory:
            path = snoop.write(Path(directory) / "x.log")
            data = path.read_bytes()
            path.write_bytes(data[:-7])
            summary = {}
            count = sum(1 for _ in tracker.iter_btsnoop(path, summary))
            self.assertEqual(count, summary["records"])
            self.assertTrue(summary["truncated_tail"])
            self.assertEqual(len(summary["sha256"]), 64)


class Adversary(unittest.TestCase):
    RDI = ["44e69c0a7a7d", "67aba670e65a", "fecba64983fd"]
    ADDR = ["5a:11:22:33:44:01", "6b:11:22:33:44:02", "7c:11:22:33:44:03"]

    def test_clean_rotation_passes(self):
        result, md = run([rotation(self.RDI, self.ADDR, call_offer)], ["--min-boundaries", "2"])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertEqual(result["verdict"]["measured_boundaries"], 2)
        self.assertEqual(result["sets_total"], 3)
        self.assertIn("PASS", md)
        gaps = [g for row in result["boundaries"] for g in row["silence_gaps_s"]]
        self.assertEqual(len(gaps), 2)
        self.assertTrue(all(1.5 <= g <= 8 for g in gaps), gaps)
        first = result["sets"][0]
        self.assertEqual((first["kind"], first["version"], first["address_kind"]), ("call_offer", 5, "resolvable_private"))
        self.assertTrue(any(r["category"] == "continuity" and r["count"] == 2 for r in result["residuals"]))

    def test_short_epoch_schedule_from_the_debug_extra(self):
        # Phones launched with test_rotid_epoch_s=60: boundaries every minute, `--epoch-s 60` places them.
        result, _ = run([rotation(self.RDI, self.ADDR, call_offer, epochs=3, epoch_s=60)], extra=["--epoch-s", "60"])
        self.assertEqual(result["parameters"]["epoch_seconds"], 60)
        self.assertEqual(result["parameters"]["grace_s"], 20.0)
        self.assertEqual(result["parameters"]["early_s"], 10.0)
        self.assertEqual(len(result["boundaries"]), 2)
        self.assertEqual([b["epoch"] for b in result["boundaries"]], [BASE // 60 + k for k in (1, 2)])
        self.assertNotEqual(result["verdict"]["zero_carry_over"], "FAIL")
        # The default length reads the same capture as one epoch with the ID changing inside it: not a clean rotation.
        default, _ = run([rotation(self.RDI, self.ADDR, call_offer, epochs=3, epoch_s=60)])
        self.assertLess(len(default["boundaries"]), 3)

    def test_single_epoch_is_unmeasured(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 600, [mfr(call_offer(self.RDI[0]))])
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "UNMEASURED")

    def test_address_carry_over_fails(self):
        result, _ = run([rotation(self.RDI, [self.ADDR[0]] * 3, call_offer)])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        # The ID changed at each boundary while the address did not: the legacy-controller defect, its own category.
        cats = {f["category"] for f in result["findings"] if f["disposition"] == "BLOCKING"}
        self.assertEqual(cats, {"address_reuse"})
        finding = next(f for f in result["findings"] if f["category"] == "address_reuse")
        self.assertEqual(len(finding["epochs"]), 3)
        self.assertEqual(finding["boundaries"], [EPOCH + 1, EPOCH + 2])
        self.assertEqual(result["address_reuse"]["total_new_id_same_address"], 2)

    def test_rdi_carry_over_fails(self):
        result, _ = run([rotation([self.RDI[0]] * 3, self.ADDR, call_offer)])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        finding = next(f for f in result["findings"] if f["category"] == "identifier")
        self.assertEqual(finding["value"], self.RDI[0])
        self.assertEqual(finding["disposition"], "BLOCKING")

    def test_rdi_persisting_across_boundary_fails(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 900 + 400, [mfr(call_offer(self.RDI[0]))])
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        self.assertTrue(any(f["category"] == "identifier" for f in result["findings"]))

    def test_late_rotation_within_grace_is_an_offset_not_a_link(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 900 + 40, [mfr(call_offer(self.RDI[0]))])
        snoop.advertiser(self.ADDR[1], BASE + 900 + 47, BASE + 1700, [mfr(call_offer(self.RDI[1]))])
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertGreater(result["sets"][0]["last_vs_epoch_end_s"], 20)
        pair = result["continuity"][0]["per_source"]["scan0"]["unique_pairs"][0]
        self.assertGreater(pair["old_last_offset_s"], 30)

    def test_fixed_manufacturer_bytes_fail(self):
        tag = bytes.fromhex("a1b2c3d4e5f60718")

        def builder(rdi):
            return call_offer(rdi) + tag  # 30 bytes: no documented layout, fixed tail
        result, _ = run([rotation(self.RDI, self.ADDR, builder)])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        finding = next(f for f in result["findings"] if f["category"] == "opaque_payload")
        self.assertEqual(finding["disposition"], "BLOCKING")
        self.assertGreaterEqual(len(finding["epochs"]), 3)

    def test_extra_fixed_ad_structure_fails(self):
        extra = lambda k: ad(0x16, bytes.fromhex("aabb11223344"))  # service data, fixed
        result, _ = run([rotation(self.RDI, self.ADDR, call_offer, extra=extra)])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        self.assertTrue(any(f["category"] == "ad_item" for f in result["findings"]))

    def test_local_name_carry_over_fails(self):
        extra = lambda k: ad(0x09, b"Alex S22")
        result, _ = run([rotation(self.RDI, self.ADDR, call_offer, extra=extra)])
        self.assertTrue(any(f["category"] == "local_name" and f["disposition"] == "BLOCKING" for f in result["findings"]))

    def test_logical_stream_token_is_allowed(self):
        logical = 0x12345678
        builder_pair = lambda k: stream_offer(self.RDI[k], logical)
        snoop = Snoop()
        for k in range(3):
            start = (EPOCH + k) * 900 + (0 if k == 0 else 4)
            stop = (EPOCH + k + 1) * 900 - 1 if k < 2 else start + 600
            offer = stream_offer(self.RDI[k], logical)
            token = int.from_bytes(offer[9:13], "big")
            snoop.advertiser(self.ADDR[k], start, stop, [mfr(offer)])
            snoop.advertiser("4e:00:00:00:00:%02x" % k, start, stop, [mfr(title_page(token))], pdu_primary=False)
            _ = builder_pair
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", [f for f in result["findings"] if f["disposition"] == "BLOCKING"])
        cats = {(f["category"], f["disposition"]) for f in result["findings"]}
        self.assertIn(("logical_stream_token", "ALLOWED"), cats)
        self.assertEqual(next(f for f in result["findings"] if f["category"] == "logical_stream_token")["value"], "12345678")

    def test_unmasked_route_token_fails_even_when_logical_differs(self):
        fixed = 0x92529FC4
        snoop = Snoop()
        for k in range(3):
            start = (EPOCH + k) * 900 + (0 if k == 0 else 4)
            stop = (EPOCH + k + 1) * 900 - 1 if k < 2 else start + 600
            offer = announcement(5, self.RDI[k], 0x0C, 1, token=fixed, seq=1, width=640, height=360, fps=15, bitrate=300)
            snoop.advertiser(self.ADDR[k], start, stop, [mfr(offer)])
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        self.assertTrue(any(f["category"] == "route_token" and f["disposition"] == "BLOCKING" for f in result["findings"]))

    def test_waiting_viewer_token_is_its_own_blocking_category_until_allowed(self):
        logical = 0x12345678
        offer0 = stream_offer(self.RDI[0], logical)
        token0 = int.from_bytes(offer0[9:13], "big")
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 899, [mfr(offer0)])
        snoop.advertiser(self.ADDR[1], BASE + 903, BASE + 1700, [mfr(stream_offer(self.RDI[1], logical))])
        viewer = lambda rdi: announcement(5, rdi, 0x09, 3, token=token0, bitrate=300)
        snoop.advertiser("5d:99:00:00:00:01", BASE + 100, BASE + 899, [mfr(viewer("974ae410ea91"))])
        snoop.advertiser("5d:99:00:00:00:02", BASE + 905, BASE + 1100, [mfr(viewer("0123456789ab"))])
        result, _ = run([snoop])
        categories = {f["category"]: f["disposition"] for f in result["findings"]}
        self.assertEqual(categories.get("viewer_wait_token"), "BLOCKING")
        self.assertNotIn("route_token", categories)
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        again, _ = run([snoop], ["--allow", "viewer_wait_token"])
        self.assertEqual(again["verdict"]["zero_carry_over"], "PASS")

    def test_v2_offers_flagged_as_stable_prefix_exposure(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 300, [mfr(call_offer("a1b2c3d4e5f6", version=2))])
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        finding = next(f for f in result["findings"] if f["category"] == "stable_prefix")
        self.assertEqual((finding["value"], finding["disposition"]), ("a1b2c3d4e5f6", "BLOCKING"))

    def test_v2_inside_declared_visible_window_is_allowed(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 100, BASE + 700, [mfr(call_offer("a1b2c3d4e5f6", version=2))])
        snoop.advertiser(self.ADDR[1], BASE + 905, BASE + 1700, [mfr(call_offer(self.RDI[1]))])
        window = f"{BASE + 60},{BASE + 800}"
        result, _ = run([snoop], ["--visible-window", window])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertIn(("visible_window_prefix", "ALLOWED"), {(f["category"], f["disposition"]) for f in result["findings"]})

    def test_reach_page_v6_identifiers_must_rotate(self):
        def page(relay, target):
            return bytes.fromhex("06" + relay + "0901" + target + "23fecb")
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 899, [mfr(page(self.RDI[0], "fecba64983fd"))], pdu_primary=False)
        snoop.advertiser(self.ADDR[1], BASE + 903, BASE + 1700, [mfr(page(self.RDI[1], "fecba64983fd"))], pdu_primary=False)
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        self.assertTrue(any(f["category"] == "identifier" and f["value"] == "fecba64983fd" for f in result["findings"]))

    def test_v4_reach_page_is_stable_prefix(self):
        snoop = Snoop()
        snoop.advertiser(self.ADDR[0], BASE + 5, BASE + 300, [mfr(bytes.fromhex("0444e69c0a7a7d0901fecba64983fd23fecb"))], pdu_primary=False)
        result, _ = run([snoop])
        self.assertTrue(any(f["category"] == "stable_prefix" for f in result["findings"]))

    def test_compact_uuid_rotation(self):
        def compact(rdi_hex):
            number = (0xC8D6 << 112) | (2 << 110) | (int(rdi_hex, 16) << 60) | (3 << 54)
            return ad(0x07, uuid.UUID(int=number).bytes[::-1])
        snoop = Snoop()
        for k in range(2):
            start = (EPOCH + k) * 900 + (0 if k == 0 else 4)
            stop = (EPOCH + k + 1) * 900 - 1 if k < 1 else start + 600
            snoop.advertiser(self.ADDR[k], start, stop, [compact(self.RDI[k])], pdu_primary=False)
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertEqual({s["layout"] for s in result["sets"]}, {"compact"})
        self.assertEqual(result["sets"][0]["ident"], self.RDI[0])

    def test_capability_bits_unique_fingerprint_is_residual_not_blocking(self):
        result, _ = run([rotation(self.RDI, self.ADDR, call_offer)])
        self.assertTrue(result["capability_bits"][0]["linkable_by_capability_bits"])
        residual = {r["category"]: r for r in result["residuals"]}
        self.assertEqual(residual["quasi_identifier"]["status"], "RESIDUAL")
        self.assertEqual(result["verdict"]["blocking_findings"], 0)

    def test_two_phones_make_continuity_ambiguous(self):
        snoop = Snoop()
        rdis = [["44e69c0a7a7d", "67aba670e65a"], ["fecba64983fd", "974ae410ea91"]]
        for phone in range(2):
            for k in range(2):
                start = (EPOCH + k) * 900 + (0 if k == 0 else 3 + phone)
                stop = (EPOCH + k + 1) * 900 - 1 if k < 1 else start + 600
                snoop.advertiser("5%d:aa:00:00:00:%02x" % (phone, k), start, stop, [mfr(call_offer(rdis[phone][k]))], rssi=-60)
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS")
        row = result["continuity"][0]["per_source"]["scan0"]
        self.assertEqual((row["sets_before"], row["sets_after"], row["feasible_pairs"]), (2, 2, 4))
        self.assertFalse(row["linkable_by_continuity"])

    def test_alias_and_advertiser_log(self):
        snoop = rotation(self.RDI, self.ADDR, call_offer)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            log = folder / "adv.txt"
            lines = [f"10-11 00:15:00.100 1 2 I BluetoothVideo: BENCHMARK rotid_boundary: epoch={EPOCH + 1} | previous={EPOCH} | wallOffsetMs=412 | listeners=1",
                     "10-11 00:15:00.100 1 2 I BluetoothVideo: BENCHMARK rotid_restart: role=call | epoch=1 | visible=false | silenceMs=3000 | transport=CoC",
                     f"BENCHMARK adv_set_churn: label=Discovery | event=stop | set=a | replacement=false | elapsedMs=1 | wallMs={(BASE + 900) * 1000 + 400}",
                     f"BENCHMARK adv_set_churn: label=Discovery | event=start | set=b | replacement=false | elapsedMs=1 | wallMs={(BASE + 900) * 1000 + 3400}"]
            log.write_text("\n".join(lines) + "\n", encoding="utf-8")
            path = snoop.write(folder / "s.log")
            out = folder / "o"
            code = tracker.main(["--snoop", f"scanner={path}", "--advertiser-log", f"S24={log}", "--alias", f"S24={self.RDI[0]},{self.ADDR[1]}",
                                 "--output", str(out)])
            self.assertEqual(code, 0)
            result = json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8"))
            self.assertEqual(result["advertiser_logs"]["S24"]["boundary_offset_ms"]["median"], 412)
            self.assertEqual(result["advertiser_logs"]["S24"]["set_restarts"][0]["gap_ms"], 3000)
            self.assertEqual(result["advertiser_logs"]["S24"]["rotid_restart"][0]["silence_ms"], 3000)
            self.assertEqual(len(result["per_alias"]["S24"]["sets"]), 3)
            self.assertEqual(len(result["per_alias"]["S24"]["boundaries"]), 2)

    def test_logcat_adv_address_seen_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            log = folder / "scan.txt"
            rows = [(self.RDI[0], "5A:11:22:33:44:01", BASE + 100), (self.RDI[1], "6B:11:22:33:44:02", BASE + 1000),
                    (self.RDI[2], "5A:11:22:33:44:01", BASE + 1900)]
            log.write_text("\n".join(
                f"I BluetoothVideo: BENCHMARK adv_address_seen: scanner=ab | node={n} | version=5 | address={a} | previous=none | elapsedMs=1 | wallMs={w * 1000}"
                for n, a, w in rows) + "\n", encoding="utf-8")
            out = folder / "o"
            self.assertEqual(tracker.main(["--logcat", f"phoneA={log}", "--output", str(out)]), 0)
            result = json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8"))
            self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")  # the address repeats in epochs 0 and 2
            self.assertEqual([f["category"] for f in result["findings"] if f["disposition"] == "BLOCKING"], ["address_reuse"])

    # ----- hardening 2026-10-11: address reuse per phone, second address inside an epoch, layout census -----

    def test_new_id_under_the_same_address_is_counted_per_phone_and_blocks(self):
        result, md = run([rotation(self.RDI, [self.ADDR[0]] * 3, call_offer)], ["--alias", f"S24={self.ADDR[0]}"])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        row = result["address_reuse"]["phones"]["S24"]
        self.assertEqual((row["new_id_same_address"], row["boundaries"], len(row["addresses_reused"])), (2, [EPOCH + 1, EPOCH + 2], 1))
        self.assertEqual(result["address_reuse"]["events"][0]["gap_epochs"], 1)
        self.assertIn("Address reuse per phone", md)
        self.assertIn("| S24 | 2 |", md)
        finding = next(f for f in result["findings"] if f["category"] == "address_reuse")
        self.assertEqual(finding["phones"], ["S24"])
        self.assertEqual(finding["disposition"], "BLOCKING")

    def test_a_second_address_for_one_id_inside_an_epoch_is_expected_not_a_failure(self):
        # Android rotates a set's address on its own 7 to 15 minute timer: the same ID under two addresses, one after
        # the other, then a fresh address and ID after the boundary.
        snoop = Snoop()
        first = EPOCH * 900
        snoop.advertiser(self.ADDR[0], first + 3, first + 440, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser("5b:11:22:33:44:09", first + 441, first + 899, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser(self.ADDR[1], first + 903, first + 1500, [mfr(call_offer(self.RDI[1]))], step=2.0)
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertEqual(result["verdict"]["blocking_findings"], 0)
        info = [f for f in result["findings"] if f["category"] == "address_rotation_within_epoch"]
        self.assertEqual(len(info), 1)
        self.assertEqual((info[0]["disposition"], info[0]["relations"], info[0]["value"]), ("INFO", ["sequential"], self.RDI[0]))
        row = result["address_reuse"]["phones"]["unattributed"]
        self.assertEqual((row["new_id_same_address"], row["second_address_same_id_sequential"], row["second_address_same_id_concurrent"]),
                         (0, 1, 0))
        self.assertEqual(result["address_reuse"]["total_new_id_same_address"], 0)
        self.assertFalse(any(r["category"] == "address_rotation_within_epoch" for r in result["residuals"]))

    def test_two_addresses_for_one_id_at_once_are_a_second_advertiser_not_a_rotation(self):
        snoop = Snoop()
        first = EPOCH * 900
        snoop.advertiser(self.ADDR[0], first + 3, first + 800, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser("5b:11:22:33:44:09", first + 10, first + 790, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser(self.ADDR[1], first + 903, first + 1500, [mfr(call_offer(self.RDI[1]))], step=2.0)
        result, _ = run([snoop])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        row = result["address_reuse"]["phones"]["unattributed"]
        self.assertEqual((row["second_address_same_id_sequential"], row["second_address_same_id_concurrent"]), (0, 1))

    def test_reusing_the_rotated_address_after_the_boundary_blocks(self):
        # The ID's second address (after Android's own rotation) is the one the next epoch's ID then uses.
        snoop = Snoop()
        first = EPOCH * 900
        snoop.advertiser(self.ADDR[0], first + 3, first + 440, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser(self.ADDR[1], first + 441, first + 899, [mfr(call_offer(self.RDI[0]))], step=2.0)
        snoop.advertiser(self.ADDR[1], first + 903, first + 1500, [mfr(call_offer(self.RDI[1]))], step=2.0)
        result, _ = run([snoop], ["--alias", f"M31={self.ADDR[1]}"])
        self.assertEqual(result["verdict"]["zero_carry_over"], "FAIL")
        self.assertEqual(result["address_reuse"]["phones"]["M31"]["new_id_same_address"], 1)
        self.assertEqual(result["address_reuse"]["phones"]["M31"]["second_address_same_id_sequential"], 1)

    def test_a_phones_declared_capability_is_compared_with_what_the_scanner_saw(self):
        snoop = rotation(self.RDI, [self.ADDR[0]] * 3, call_offer)  # A15s: one address for every epoch
        snoop.records += rotation(["974ae410ea91", "bb2d061f6480", "05aabbccddee"], ["6f:00:00:00:00:01", "6f:00:00:00:00:02", "6f:00:00:00:00:03"],
                                  call_offer).records  # S22: a new address per epoch
        snoop.records += rotation(["0a1b2c3d4e5f", "1a2b3c4d5e6f", "2a3b4c5d6e7f"], ["7d:00:00:00:00:09"] * 3, call_offer).records  # M31: reuse
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            suspected = folder / "s24.txt"
            suspected.write_text("I BluetoothVideo: BENCHMARK rotid_address_reuse: source=capability | extendedAdv=false | multiAdv=false | status=suspected\n",
                                 encoding="utf-8")
            fresh = folder / "s22.txt"
            fresh.write_text("I BluetoothVideo: BENCHMARK rotid_address_reuse: source=capability | extendedAdv=true | multiAdv=true | status=fresh\n",
                             encoding="utf-8")
            wrong = folder / "m31.txt"
            wrong.write_text("I BluetoothVideo: BENCHMARK rotid_address_reuse: source=capability | extendedAdv=true | multiAdv=false | status=fresh\n",
                             encoding="utf-8")
            path = snoop.write(folder / "s.log")
            out = folder / "o"
            code = tracker.main(["--snoop", f"scanner={path}", "--advertiser-log", f"A15s={suspected}", "--advertiser-log", f"S22={fresh}",
                                 "--advertiser-log", f"M31={wrong}", "--alias", f"A15s={self.ADDR[0]}",
                                 "--alias", "S22=6f:00:00:00:00:01,6f:00:00:00:00:02,6f:00:00:00:00:03",
                                 "--alias", "M31=7d:00:00:00:00:09", "--output", str(out)])
            self.assertEqual(code, 0)
            result = json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8"))
        phones = result["address_reuse"]["phones"]
        self.assertEqual(phones["A15s"]["declared"], "suspected")
        self.assertEqual(phones["A15s"]["new_id_same_address"], 2)
        self.assertEqual(phones["A15s"]["consistency"], "matches the declared capability")
        self.assertEqual((phones["S22"]["declared"], phones["S22"]["new_id_same_address"]), ("fresh", 0))
        self.assertEqual(phones["S22"]["consistency"], "matches the declared capability")
        self.assertTrue(phones["M31"]["consistency"].startswith("REUSE OBSERVED BUT NOT DECLARED"))
        self.assertEqual(result["advertiser_logs"]["A15s"]["address_reuse_capability"]["status"], "suspected")

    def test_a_bonded_scanner_sees_one_resolved_identity_address_which_is_not_counted_as_reuse(self):
        # Address types 2 and 3 are the identity address the controller resolves for a bonded phone: the same for every set.
        snoop = Snoop()
        for k in range(3):
            start = (EPOCH + k) * 900 + (0 if k == 0 else 3)
            stop = (EPOCH + k + 1) * 900 - 1 if k < 2 else start + 600
            snoop.advertiser("5a:11:22:33:44:01", start, stop, [mfr(call_offer(self.RDI[k]))], step=2.0, address_type=3)
        result, _ = run([snoop], ["--min-boundaries", "2"])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])
        self.assertEqual(result["address_reuse"]["total_new_id_same_address"], 0)
        info = [f for f in result["findings"] if f["category"] == "address_identity_resolved"]
        self.assertEqual(len(info), 1)
        self.assertEqual(info[0]["disposition"], "INFO")

    def test_an_unknown_declared_status_is_reported_without_a_verdict(self):
        snoop = rotation(self.RDI, ["6f:00:00:00:00:01", "6f:00:00:00:00:02", "6f:00:00:00:00:03"], call_offer)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            log = folder / "a.txt"
            log.write_text("I BluetoothVideo: BENCHMARK rotid_address_reuse: source=capability | extendedAdv=false | multiAdv=false | sdk=32 | status=unknown\n",
                           encoding="utf-8")
            path = snoop.write(folder / "s.log")
            out = folder / "o"
            code = tracker.main(["--snoop", f"scanner={path}", "--advertiser-log", f"S21={log}", "--alias", "S21=6f:00:00:00:00:01", "--output", str(out)])
            self.assertEqual(code, 0)
            result = json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8"))
        row = result["address_reuse"]["phones"]["S21"]
        self.assertEqual(row["declared"], "unknown")
        self.assertTrue(row["consistency"].startswith("declared unknown"))
        self.assertEqual(result["advertiser_logs"]["S21"]["address_reuse_capability"]["sdk"], "32")

    def test_layout_census_counts_the_distinct_capability_layouts_per_kind(self):
        same = Snoop()
        for phone in range(3):
            for k in range(2):
                start = (EPOCH + k) * 900 + (0 if k == 0 else 3 + phone)
                stop = (EPOCH + k + 1) * 900 - 1 if k < 1 else start + 600
                caps = 3 if phone < 2 else 1  # two phones with VP9+AV1, one with VP9 only
                payload = announcement(5, ["44e69c0a7a7d", "67aba670e65a", "fecba64983fd", "974ae410ea91", "bb2d061f6480", "05aabbccddee"][phone * 2 + k],
                                       0x41, caps)
                same.advertiser("5%d:aa:00:00:00:%02x" % (phone, k), start, stop, [mfr(payload)], rssi=-60)
        result, md = run([same])
        census = result["layout_census"]["call_offer"]
        self.assertEqual(census["sets"], 6)
        self.assertEqual(census["distinct_layouts"], 2)
        self.assertEqual(census["layouts"][0]["sets"], 4)
        self.assertEqual(census["largest_layout_share"], round(4 / 6, 3))
        self.assertIn("Layout census", md)

    def test_neutral_phone_name_is_not_a_link(self):
        extra = lambda k: ad(0x09, b"Phone")
        result, _ = run([rotation(self.RDI, self.ADDR, call_offer, extra=extra)])
        self.assertEqual(result["verdict"]["zero_carry_over"], "PASS", result["findings"])

    def test_rotated_snoop_files_with_one_label_are_one_scanner(self):
        first, second = Snoop(), Snoop()
        first.advertiser(self.ADDR[0], BASE + 5, BASE + 899, [mfr(call_offer(self.RDI[0]))])
        second.advertiser(self.ADDR[1], BASE + 903, BASE + 1700, [mfr(call_offer(self.RDI[1]))])
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            one, two = first.write(folder / "btsnoop_hci.log.last"), second.write(folder / "btsnoop_hci.log")
            out = folder / "o"
            self.assertEqual(tracker.main(["--snoop", f"s22={one}", "--snoop", f"s22={two}", "--output", str(out)]), 0)
            result = json.loads((out / "rotid-tracker.json").read_text(encoding="utf-8"))
            self.assertEqual([s["label"] for s in result["scanners"]], ["s22"])
            self.assertEqual(result["verdict"]["measured_boundaries"], 1)
            self.assertEqual(len(result["continuity"][0]["per_source"]), 1)
            self.assertEqual(len(result["inputs"]), 2)

    def test_output_refuses_overwrite_and_unsafe_locations(self):
        snoop = rotation(self.RDI, self.ADDR, call_offer)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            path = snoop.write(folder / "s.log")
            out = folder / "o"
            self.assertEqual(tracker.main(["--snoop", str(path), "--output", str(out)]), 0)
            self.assertEqual(tracker.main(["--snoop", str(path), "--output", str(out)]), 2)
            self.assertEqual(tracker.main(["--snoop", str(path), "--output", str(out), "--overwrite"]), 0)
            with self.assertRaises(ValueError):
                tracker.main(["--snoop", str(path), "--output", str(TOOLS.parent / "docs" / "x")])

    def test_non_meshlive_traffic_is_ignored_and_counted(self):
        snoop = rotation(self.RDI, self.ADDR, call_offer)
        for i in range(50):
            snoop.add(BASE + 10 + i, legacy_event("40:00:00:00:00:%02x" % (i % 5), ad(0x01, b"\x06") + ad(0x09, b"Watch"), 0))
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            path = snoop.write(folder / "s.log")
            self.assertEqual(tracker.main(["--snoop", f"x={path}", "--output", str(folder / "o")]), 0)
            result = json.loads((folder / "o" / "rotid-tracker.json").read_text(encoding="utf-8"))
            self.assertEqual(result["inputs"][0]["other_reports"], 50)
            self.assertEqual(result["verdict"]["zero_carry_over"], "PASS")


if __name__ == "__main__":
    unittest.main()
