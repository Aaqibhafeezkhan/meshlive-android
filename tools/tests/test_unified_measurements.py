"""Offline regressions for complete, correctly scoped measurement mapping."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import run_benchmark_suite as suite
import run_private_call_benchmark as calls
import score_device_run as device_score

PASS = {"status": "PASS", "value": 0, "limit": 0}
UNKNOWN = {"status": "UNMEASURED", "value": None}


class UnifiedMeasurementsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="regression-")
        self.folder = Path(self.temporary.name).resolve()
        self.addCleanup(self.temporary.cleanup)
        self.profile = {"codec": "encoder-a", "width": 320, "height": 240, "fps": 15,
                        "bitrate_kbps": 500, "rate_control": "cbr", "playback": "balanced",
                        "thermal_reduction": False}
        self.registry = {"a": {"node_prefix": "000000000001"},
                         "b": {"node_prefix": "000000000002"}}
        self.broadcast = {"run_id": "offline-broadcast", "source_kind": "raw_fixture_live_encoder",
            "devices": self.registry, "scenario": {"id": "offline-broadcast", "source": "a",
                "transport": "gatt", "profile": self.profile, "receivers": [{"phone": "b", "parent": "a"}]}}
        self.call = {"run_id": "offline-call", "mode": "private_call", "source_kind": "recorded_motion_bidirectional",
            "source_kinds": {"a": "raw_fixture_live_encoder", "b": "raw_fixture_live_encoder"},
            "devices": self.registry, "scenario": {"id": "offline-call", "caller": "a", "callee": "b",
                "transport": "gatt", "duration_s": 20,
                "profiles": {"a": self.profile, "b": {**self.profile, "codec": "encoder-b"}}}}

    def analyze(self, manifest, score, required=("transport",), quality_result=None):
        path = self.folder / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        adapter = "score_call_manifest" if manifest.get("mode") == "private_call" else "score_manifest"
        quality = {"status": "ok", "measurement_status": "MEASURED", "planned_frames": 300,
                   "encoded_frames": 300, "received_frames": 300,
                   "quality": {"vmaf": 95, "ssim": 0.99, "psnr_db": 40},
                   "phases": [{"phase": "source-1", "planned_frames": 300}],
                   "whole_wall_session_quality": "UNMEASURED"}
        if quality_result is not None:
            quality = quality_result
        with patch.object(suite, adapter, return_value=score), \
             patch.object(suite, "score_phase_delivery", return_value=quality), \
             patch.object(suite, "score_motion", return_value={"timing_state": "UNMEASURED", "capture_state": "COMPLETE"}), \
             patch.object(suite, "preflight", side_effect=AssertionError("No phone preflight in an offline test")), \
             patch.object(suite, "Runner", side_effect=AssertionError("No phone runner in an offline test")), \
             patch.object(suite, "CallRunner", side_effect=AssertionError("No phone runner in an offline test")):
            return suite.analyze(path, self.folder / "analysis", required)

    @staticmethod
    def load(kbps=500, state="PASS"):
        return {"status": state, "median_kbps": kbps, "requested_kbps": 500,
                "target_fraction": kbps / 500, "required_fraction": 0.9}

    def call_score(self):
        return {"overall_status": "PASS", "log_status": "PASS", "devices": [
            {"device": phone, "setup_role": role, "peer": peer, "gates": {"authenticated_peer": PASS},
             "requested_profile_coverage": "MEASURED",
             "outgoing": {"gates": {"encoder_fps": PASS, "sender_drops": PASS},
                          "offered_load": self.load()},
             "incoming": {"gates": {"visible_fps": PASS, "unexpected_freeze_events": PASS}}}
            for phone, role, peer in (("a", "caller", "b"), ("b", "callee", "a"))], "lifecycle": []}

    def test_private_call_both_media_directions_reach_the_correct_families(self):
        score = self.call_score()
        score["devices"][0]["incoming"]["gates"]["unexpected_freeze_events"] = {"status": "FAIL", "value": 1, "limit": 0}
        score["devices"][1]["outgoing"]["gates"]["sender_drops"] = {"status": "FAIL", "value": 2, "limit": 0}
        result = self.analyze(self.call, score)
        self.assertEqual("FAIL", result["families"]["smoothness"]["status"])
        self.assertEqual("FAIL", result["families"]["transport"]["status"])
        smooth = result["families"]["smoothness"]["measurements"]
        self.assertTrue(any(m["device"] == "a" and m["status"] == "FAIL" for m in smooth))
        self.assertTrue(any(m["device"] == "b" and m["status"] == "FAIL"
                            for m in result["families"]["transport"]["measurements"]))
        for phone in ("a", "b"):
            self.assertGreaterEqual(sum(m["device"] == phone for m in smooth), 3)

    def test_private_call_top_level_controls_reach_lifecycle(self):
        score = self.call_score()
        score["lifecycle"] = [{"device": "a", "action": "mute", "gate": PASS},
                               {"device": "b", "action": "video_on", "gate": {"status": "FAIL"},
                                "evidence": {"surface_recovery_ms": {"status": "FAIL", "value": 6000, "limit": 5000}}}]
        result = self.analyze(self.call, score)
        self.assertEqual("FAIL", result["families"]["lifecycle"]["status"])
        self.assertGreaterEqual(len(result["families"]["lifecycle"]["measurements"]), 2)

    def test_broadcast_controls_reach_lifecycle_and_keep_failed_recovery(self):
        score = {"overall_status": "FAIL", "devices": [
            {"device": "a", "role": "sender", "gates": {}, "offered_load": self.load(),
             "actions": [{"action": "pause", "first_surface_ms": UNKNOWN}]},
            {"device": "b", "role": "receiver", "gates": {}, "actions": [
                {"action": "restart_receiver", "first_surface_ms": {"status": "FAIL", "value": 7000, "limit": 5000}}]}]}
        result = self.analyze(self.broadcast, score)
        self.assertEqual("FAIL", result["families"]["lifecycle"]["status"])
        self.assertTrue(any(m["device"] == "b" and m["status"] == "FAIL"
                            for m in result["families"]["lifecycle"]["measurements"]))

    def test_unrecognized_or_capture_states_cannot_become_verdict_pass(self):
        for state in ("BLOCKED", "INTERRUPTED", "NOT RUN", "MEASURED", "MEASURED_WITH_GAPS", "unknown"):
            with self.subTest(state=state):
                self.assertEqual("UNMEASURED", suite.status([{"status": state}]))
        self.assertEqual("FAIL", suite.status([{"status": "PASS"}, {"status": "FAIL"}]))
        self.assertEqual("UNMEASURED", suite.status([]))

    def test_optical_component_success_cannot_approve_unmeasured_speaker_and_acoustic_quality(self):
        observed = {"adapters": [{"id": "physical", "kind": "physical_av"}], "errors": [], "measurements": [
            {"name": "optical_delay_max_ms", "family": "physical_latency", "status": "PASS", "value": 80},
            {"name": "source_speaker_latency", "family": "physical_latency", "status": "UNMEASURED", "value": None},
            {"name": "acoustic_quality", "family": "acoustic", "status": "UNMEASURED", "value": None}]}
        with patch.object(suite.benchmark_measurements, "analyze", return_value=observed):
            result = self.analyze(self.call, self.call_score(), ("physical_latency", "acoustic"))
        self.assertEqual("UNMEASURED", result["families"]["physical_latency"]["status"])
        self.assertEqual("UNMEASURED", result["families"]["acoustic"]["status"])
        self.assertNotEqual("PASS", result["full_experience_status"])
        self.assertNotEqual("PASS", result["status"])

    def test_insufficient_real_source_load_prevents_transport_capacity_pass(self):
        score = {"overall_status": "PASS", "devices": [
            {"device": "a", "role": "sender", "gates": {"sender_drops": PASS},
             "offered_load": self.load(150, "UNMEASURED")},
            {"device": "b", "role": "receiver", "gates": {"decoder_drops": PASS}, "offered_load": UNKNOWN}]}
        result = self.analyze(self.broadcast, score, ("transport",))
        self.assertNotEqual("PASS", result["families"]["transport"]["status"])
        self.assertNotEqual("PASS", result["status"])

    def test_receivers_do_not_need_their_own_encoder_capacity(self):
        score = {"overall_status": "PASS", "devices": [
            {"device": "a", "role": "sender", "gates": {"sender_drops": PASS}, "offered_load": self.load()},
            {"device": "b", "role": "receiver", "gates": {"decoder_drops": PASS}, "offered_load": UNKNOWN}]}
        result = self.analyze(self.broadcast, score, ("transport",))
        self.assertEqual("PASS", result["families"]["transport"]["status"])

    def test_both_private_call_sources_require_actual_load(self):
        score = self.call_score()
        score["devices"][1]["outgoing"]["offered_load"] = self.load(150, "UNMEASURED")
        result = self.analyze(self.call, score, ("transport",))
        self.assertNotEqual("PASS", result["families"]["transport"]["status"])
        self.assertNotEqual("PASS", result["status"])

    def test_restarted_call_endpoint_keeps_original_wrong_peer_failure(self):
        def line(pid, peer):
            return f"10-03 00:00:01.000 {pid} {pid} I BluetoothVideo: Encrypted private-call link to {peer} over BLE GATT\n"
        files = (("a-old.log", 101, "ffffffffffff"), ("a-new.log", 102, "000000000002"),
                 ("b.log", 201, "000000000001"))
        for name, pid, peer in files:
            (self.folder / name).write_text(line(pid, peer), encoding="utf-8")
        manifest = {**self.call, "logs": [
            {"device": "a", "pid": 101, "path": "a-old.log"},
            {"device": "b", "pid": 201, "path": "b.log"},
            {"device": "a", "pid": 102, "path": "a-new.log"}]}
        score = calls.score_call_manifest(manifest, self.folder)
        endpoint = [row for row in score["devices"] if row["device"] == "a"]
        self.assertEqual(2, len(endpoint))
        self.assertTrue(any(row["gates"]["authenticated_peer"]["status"] == "FAIL" for row in endpoint))
        self.assertEqual({101, 102}, {row["outgoing"]["scope"]["pid"] for row in endpoint})
        self.assertTrue(all(row["outgoing"]["scope"]["records"] > 0 for row in endpoint))
        self.assertEqual("FAIL", score["overall_status"])

    def test_unfinalized_picture_does_not_pass_from_perfect_numbers(self):
        quality = {"status": "unmeasured", "measurement_status": "UNMEASURED",
                   "reasons": ["source phase not finalized"],
                   "quality": {"vmaf": 100, "ssim": 1, "psnr_db": 99}}
        result = self.analyze(self.call, self.call_score(), ("picture",), quality)
        self.assertEqual("UNMEASURED", result["families"]["picture"]["status"])
        self.assertNotEqual("PASS", result["status"])
        validity = [row for row in result["families"]["picture"]["measurements"]
                    if row.get("name") == "reference_validity"]
        self.assertEqual(2, len(validity))
        self.assertTrue(all(row["status"] == "UNMEASURED" for row in validity))
        self.assertTrue(all("not finalized" in row.get("reason", "") for row in validity))

    def test_verified_perfect_psnr_passes_without_nonfinite_json(self):
        quality = {"status": "ok", "measurement_status": "MEASURED",
                   "quality": {"vmaf": 100, "ssim": 1, "psnr_db": None, "psnr_perfect": True}}
        result = self.analyze(self.call, self.call_score(), ("picture",), quality)
        self.assertEqual("PASS", result["families"]["picture"]["status"])
        psnr = [row for row in result["families"]["picture"]["measurements"] if row.get("name") == "psnr_db"]
        self.assertEqual(2, len(psnr))
        self.assertTrue(all(row["status"] == "PASS" and row.get("perfect") is True for row in psnr))
        json.dumps(result, allow_nan=False)

    def test_excluded_post_control_cadence_cannot_approve_full_lifecycle(self):
        score = self.call_score()
        score["devices"][0]["incoming"]["surface_segments"] = [[
            {"cadence_lifecycle_overlap": False, "cadence_ms": {"p95": 66.7}},
            {"cadence_lifecycle_overlap": True, "cadence_ms": {"p95": 200.0}}]]
        score["devices"][0]["incoming"]["gates"]["surface_p95_ms"] = {
            "status": "PASS", "value": 66.7, "limit": 83.333}
        result = self.analyze(self.call, score, ("smoothness",))
        self.assertEqual("UNMEASURED", result["families"]["smoothness"]["status"])
        coverage = [row for row in result["families"]["smoothness"]["measurements"]
                    if row.get("name") == "full_lifecycle_cadence_coverage"]
        self.assertEqual(1, len(coverage))
        self.assertEqual("a", coverage[0]["device"])
        self.assertEqual("incoming", coverage[0]["direction"])
        self.assertEqual("UNMEASURED", coverage[0]["status"])
        self.assertNotEqual("PASS", result["status"])

    def test_original_gatt_crypto_and_parent_loss_raise_scoped_alerts(self):
        messages = ("Incoming GATT stream stopped: error:1e000065:Cipher functions:OPENSSL_internal:BAD_DECRYPT",
                    "The immediate parent withdrew this stream route.")
        for message in messages:
            with self.subTest(message=message):
                text = f"10-03 22:08:10.685 101 101 I BluetoothVideo: {message}\n"
                result = device_score.score_log(text, {"device": "b", "role": "receiver", "pid": 101}, {})
                self.assertEqual("FAIL", result["gates"]["unexpected_events"]["status"])
                self.assertEqual(1, result["gates"]["unexpected_events"]["value"])
                registry = {phone: {**meta, "serial": "offline-" + phone} for phone, meta in self.registry.items()}
                runner = suite.Runner("unused-adb", registry, self.broadcast["scenario"], self.folder)
                (self.folder / "loss.log").write_text(text, encoding="utf-8")
                runner.logs = [{"device": "b", "pid": 101, "path": "loss.log"}]
                runner.pids = {"b": "101"}
                runner.captures = [(Mock(poll=Mock(return_value=None)), None)]
                runner.adb = Mock(side_effect=lambda _phone, *args, **kwargs: "device" if args == ("get-state",) else "101")
                runner.beep = Mock()
                runner.health_check("b")
                runner.beep.assert_called_once()
                self.assertEqual(1, len(runner.health_events))
                self.assertFalse(runner.health_events[0]["planned"])

    def test_planned_parent_loss_remains_expected_but_crypto_error_is_alerted(self):
        registry = {phone: {**meta, "serial": "offline-" + phone} for phone, meta in self.registry.items()}
        for message, planned in (("The immediate parent withdrew this stream route.", True),
                                 ("Incoming GATT stream stopped: BAD_DECRYPT", False)):
            with self.subTest(message=message):
                runner = suite.Runner("unused-adb", registry, self.broadcast["scenario"], self.folder)
                (self.folder / "planned-loss.log").write_text(
                    f"10-03 22:08:10.685 101 101 I BluetoothVideo: {message}\n", encoding="utf-8")
                runner.logs = [{"device": "b", "pid": 101, "path": "planned-loss.log"}]
                runner.pids = {"b": "101"}
                runner.captures = [(Mock(poll=Mock(return_value=None)), None)]
                runner.pending_planned_losses = {"b": {"device": "b", "time": "10-03 22:08:10.000", "removed_ancestor": "a"}}
                runner.adb = Mock(side_effect=lambda _phone, *args, **kwargs: "device" if args == ("get-state",) else "101")
                runner.beep = Mock()
                runner.health_check("b")
                self.assertEqual(planned, runner.health_events[0]["planned"])
                self.assertEqual(0 if planned else 1, runner.beep.call_count)


if __name__ == "__main__":
    unittest.main()
