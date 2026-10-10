"""Offline cue-detector regressions; generated signals are not phone results."""
from contextlib import redirect_stderr
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave
from unittest.mock import patch

import numpy as np

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score_audio_sync as audio
import prepare_audio_track_observation as track
import benchmark_layout as layout


class AudioSyncTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="audio-sync-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.count = 144000
        self.cues = [{"id": f"cue-{i}", "kind": "noise", "file_frame": start * 30 // 48000,
                      "sample_index": start, "sample_count": 960}
                     for i, start in enumerate((4800, 48000, 96000))]
        self.signal = np.zeros(self.count, dtype="<i2")
        for i, cue in enumerate(self.cues):
            values = np.random.default_rng(i + 7).integers(-16000, 16000, cue["sample_count"], dtype="<i2")
            self.signal[cue["sample_index"]:cue["sample_index"] + cue["sample_count"]] = values
        self.reference = self.wav("reference.wav", self.signal)
        self.events = self.document("events.json", {"schema": "vsg-events-v1", "events": [], "audio_schedule": {
            "schema": "vsg-audio-schedule-v1", "enabled": True, "sample_rate": 48000, "channels": 1,
            "sample_count": self.count, "events": self.cues}})

    def pointer(self, name):
        return {"path": name, "sha256": hashlib.sha256((self.folder / name).read_bytes()).hexdigest()}

    def document(self, name, value):
        (self.folder / name).write_text(json.dumps(value), encoding="utf-8")
        return self.pointer(name)

    def wav(self, name, samples, channels=1, rate=48000):
        with wave.open(str(self.folder / name), "wb") as target:
            target.setnchannels(channels)
            target.setsampwidth(2)
            target.setframerate(rate)
            target.writeframes(samples.astype("<i2").tobytes())
        return self.pointer(name)

    def spec(self, delay=2400, drift=0, capture_count=None, visual=True):
        samples = np.zeros(capture_count if capture_count is not None else self.count + 4800, dtype="<i2")
        for cue in self.cues:
            pos = cue["sample_index"] + delay + round(cue["sample_index"] * drift)
            end = min(len(samples), pos + cue["sample_count"])
            if end > pos >= 0:
                samples[pos:end] = self.signal[cue["sample_index"]:cue["sample_index"] + end - pos]
        result = {"schema": "benchmark-audio-observation-v1", "reference": {"wav": self.reference, "events": self.events},
                  "capture": {"wav": self.wav("capture.wav", samples), "role": "decoded_pcm",
                              "clock": {"id": "capture-1", "sample_zero_ns": 7_000_000_000},
                              "timeline": {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}},
                  "plans": [{"stream_generation": 1, "loop_iteration": 0, "source_start_sample": 0,
                             "source_end_sample": self.count, "capture_start_sample": 0, "search_radius_samples": 4000}]}
        if visual:
            result["visual_observations"] = self.document("visual.json", {
                "schema": "benchmark-visual-markers-v1", "scope": {"clock": "capture-1", "timing_status": "MEASURED"},
                "observations": [{"stream_generation": 1, "loop_iteration": 0, "file_frame": cue["file_frame"],
                                  "marker_status": "MATCHED", "time_ns": 7_000_000_000 + cue["sample_index"] * 1_000_000_000 // 48000}
                                 for cue in self.cues]})
        return result

    def analyze(self, spec):
        return audio.analyze(spec, self.folder)

    def test_declared_sustained_workload_does_not_become_an_oversized_cue(self):
        spec = self.spec()
        document = json.loads((self.folder / "events.json").read_text())
        workload = {"id": "ongoing-load", "role": "workload", "kind": "noise", "file_frame": 0,
                    "sample_index": 0, "sample_count": self.count}
        document["audio_schedule"]["events"].append(workload)
        spec["reference"]["events"] = self.document("events.json", document)
        result = self.analyze(spec)
        self.assertEqual({"MATCHED": 3}, result["metrics"]["cue_counts"])
        self.assertEqual([workload], result["reference_workloads"])
        self.assertEqual("UNMEASURED", result["measurements"][-1]["status"])
        document["audio_schedule"]["events"] = [workload]
        spec["reference"]["events"] = self.document("events.json", document)
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertFalse(result["metrics"]["planned_timeline_complete"])

    def test_workload_roles_keep_bounds_and_bounded_cues_strict(self):
        spec = self.spec()
        document = json.loads((self.folder / "events.json").read_text())
        for role,count,error in (("unknown",960,"role"),("workload",self.count+1,"outside WAV"),
                                 ("cue",self.count,"2 second")):
            with self.subTest(role=role):
                document["audio_schedule"]["events"] = [{"id": "load", "role": role, "kind": "noise",
                    "file_frame": 0, "sample_index": 0, "sample_count": count}]
                spec["reference"]["events"] = self.document("events.json", document)
                with self.assertRaisesRegex(ValueError,error):
                    self.analyze(spec)

    def piecewise(self, spec, rate_error=0, defect=None):
        with wave.open(str(self.folder / spec["capture"]["wav"]["path"]), "rb") as source:
            count = source.getnframes()
        rows = []
        for start in range(0, count, 480):
            at = 7_100_000_000 + round(start * 1e9 / 48000 * (1 + rate_error))
            rows.append({"sample_start": start, "sample_count": min(480, count - start),
                         "source_pts_us": start * 1_000_000 // 48000, "stage": "track_accepted",
                         "at_ns": at + 10_000_000, "audio_frame_position": start, "audio_timestamp_ns": at,
                         "audio_underrun_count": 0})
        if defect == "missing":
            rows[16]["audio_frame_position"] = None
            rows[16]["audio_timestamp_ns"] = None
        elif defect == "stale":
            rows[16]["audio_timestamp_ns"] -= 5_000_000_000
        elif defect == "underrun":
            for row in rows[16:]:
                row["audio_underrun_count"] = 1
        elif defect == "reset":
            rows[16]["audio_frame_position"] = 0
        chunk_path = self.folder / "track.csv"
        with chunk_path.open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=(*track.FIELDS, track.UNDERRUN_FIELD))
            writer.writeheader()
            writer.writerows(rows)
        summary = self.document("track-summary.json", {"schema": "benchmark-pcm-trace-v1", "session": "session-1", "pid": 17,
            "role": "receiver", "clock": "System.nanoTime", "sample_rate": 48000, "channels": 1,
            "sample_count": count, "attempted_samples": count, "trace_overflow_chunks": 0,
            "finalized": True, "error": None, "audio_track_initial_written_frames": 0})
        native_spec = {"schema": "benchmark-audio-track-observation-v1", "run_id": "run-1", "device": "a", "pid": 17,
                      "session": "session-1", "clock": "run-1:a:17:System.nanoTime", "wav": spec["capture"]["wav"],
                      "chunks": self.pointer("track.csv"), "summary": summary,
                      "controls": {"maximum_anchor_age_ns": 100_000_000, "maximum_anchor_gap_ns": 50_000_000,
                                   "maximum_rate_error_ppm": 1500, "timestamp_uncertainty_ns": 25000}}
        output = track.analyze(native_spec, self.folder)
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", output)
        spec["capture"]["clock"] = {"id": native_spec["clock"]}
        spec["capture"]["timeline"] = {"continuity": "SPARSE", "gaps": [], "overflow_samples": 0}
        if spec.get("visual_observations"):
            visual = json.loads((self.folder / spec["visual_observations"]["path"]).read_text())
            visual["scope"]["clock"] = native_spec["clock"]
            spec["visual_observations"] = self.document("visual.json", visual)
        return spec

    def test_known_audio_offset_and_shared_clock_av_alignment(self):
        result = self.analyze(self.spec())
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual({"MATCHED": 3}, result["metrics"]["cue_counts"])
        self.assertTrue(result["metrics"]["planned_timeline_complete"])
        for row in result["events"]:
            self.assertAlmostEqual(50, row["nominal_offset_ms"], places=6)
            self.assertEqual("MEASURED", row["av_status"])
            self.assertAlmostEqual(50, row["audio_minus_video_ms"], places=6)
        self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["measurements"]))
        self.assertEqual("UNMEASURED", result["scope"]["source_to_capture_latency"])

    def test_relative_drift_fitted_from_cue_positions_and_keeps_span(self):
        result = self.analyze(self.spec(drift=0.001))
        measured = result["metrics"]["wall_clock_drift"][0]
        self.assertEqual("MEASURED", measured["status"])
        self.assertAlmostEqual(1000, measured["relative_drift_ppm"], delta=5)
        self.assertGreater(measured["source_span_seconds"], 1)
        self.assertLess(measured["fit_residual_rms_ms"], 0.02)

    def test_sparse_concatenated_pcm_is_content_evidence_not_clock_evidence(self):
        for timeline in ({"continuity": "UNKNOWN"}, {"continuity": "SPARSE", "gaps": [{"samples": 960}]},
                         {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 960}):
            with self.subTest(timeline=timeline):
                spec = self.spec()
                spec["capture"]["timeline"] = timeline
                result = self.analyze(spec)
                self.assertEqual({"MATCHED": 3}, result["metrics"]["cue_counts"])
                self.assertEqual("UNMEASURED", result["scope"]["capture_sample_clock_status"])
                self.assertTrue(all(row["av_status"] == "UNMEASURED" for row in result["events"]))
                self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["metrics"]["wall_clock_drift"]))
                self.assertTrue(all(row["status"] == "MEASURED" for row in result["metrics"]["sample_content_drift"]))

    def test_piecewise_track_clock_maps_whole_cues_without_scalar_anchor(self):
        result = self.analyze(self.piecewise(self.spec()))
        self.assertEqual("estimated_track_output", result["scope"]["timing_role"])
        self.assertEqual("run-1:a:17:System.nanoTime", result["scope"]["clock"])
        self.assertTrue(all(row["av_status"] == "MEASURED" for row in result["events"]))
        self.assertTrue(all(abs(row["audio_minus_video_ms"] - 150) < 1e-6 for row in result["events"]))
        self.assertTrue(all(row["timing_basis"] == "bracketed_linear_hardware_estimate" for row in result["events"]))
        self.assertEqual("UNMEASURED", result["measurements"][-1]["status"])

    def test_relocated_track_clock_recomputes_original_relative_phase_evidence(self):
        spec = self.piecewise(self.spec())
        clock = json.loads((self.folder / "sample-clock.json").read_text())
        for record in clock["evidence"].values():
            record["path"] = Path(record["path"]).name
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", clock)
        aliases = {"sample-clock.json": "benchmarks/runs/id/clock/sample-clock.json",
                   "capture.wav": "benchmarks/runs/id/media/capture.wav", "track.csv": "benchmarks/runs/id/media/track.csv",
                   "track-summary.json": "benchmarks/runs/id/media/track-summary.json"}
        for source, destination in aliases.items():
            target = self.folder / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            (self.folder / source).replace(target)
        ledger = self.folder / "benchmarks/catalog/ledger.json"
        ledger.parent.mkdir(parents=True)
        ledger.write_text(json.dumps({"storage": {"paths": aliases}}))
        resolve = lambda path, base=None: layout.resolve(path, base=base, root=self.folder)
        original = lambda path: layout.original(path, root=self.folder)
        with patch.object(audio, "resolve_input", resolve), patch.object(audio, "original", original), patch.object(track, "resolve_input", resolve):
            result = self.analyze(spec)
            self.assertEqual("estimated_track_output", result["scope"]["timing_role"])
            self.assertTrue(all(row["av_status"] == "MEASURED" for row in result["events"]))
            self.assertTrue(all(abs(row["audio_minus_video_ms"] - 150) < 1e-6 for row in result["events"]))
            self.assertEqual("UNMEASURED", result["measurements"][-1]["status"])
            (self.folder / aliases["track.csv"]).write_text("changed PCM clock samples")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                self.analyze(spec)

    def test_piecewise_hardware_wall_drift_and_sample_content_drift_are_distinct(self):
        result = self.analyze(self.piecewise(self.spec(), rate_error=.001))
        self.assertAlmostEqual(0, result["metrics"]["sample_content_drift"][0]["relative_drift_ppm"], delta=.01)
        self.assertAlmostEqual(1000, result["metrics"]["wall_clock_drift"][0]["relative_drift_ppm"], delta=.01)

    def test_timing_gate_includes_hardware_clock_uncertainty(self):
        spec = self.piecewise(self.spec())
        spec["acceptance"] = {"validation": self.document("validation.json", {
            "schema": "benchmark-audio-detector-validation-v1", "status": "VALIDATED",
            "correlation": {"minimum": .8, "peak_margin": .05},
            "validated_measurements": ["audio_drift_abs_ppm"]}),
            "limits": {"audio_drift_abs_ppm": 10}}
        result = self.analyze(spec)
        self.assertAlmostEqual(0, result["metrics"]["wall_clock_drift"][0]["relative_drift_ppm"], delta=.01)
        self.assertGreater(result["metrics"]["wall_clock_drift"][0]["uncertainty_ppm"], 10)
        gate = next(row for row in result["measurements"] if row["name"] == "audio_drift_abs_ppm")
        self.assertEqual("FAIL", gate["status"])

    def test_stale_underrun_missing_and_reset_brackets_cannot_time_crossing_cue(self):
        for defect in ("stale", "underrun", "missing", "reset"):
            with self.subTest(defect=defect):
                result = self.analyze(self.piecewise(self.spec(), defect=defect))
                self.assertEqual("MATCHED", result["events"][0]["status"])
                self.assertEqual("UNMEASURED", result["events"][0]["av_status"])
                self.assertNotIn("audio_minus_video_ms", result["events"][0])
                self.assertEqual("UNMEASURED", result["metrics"]["wall_clock_drift"][0]["status"])
                self.assertFalse(result["metrics"]["planned_clock_interval_complete"])

    def test_piecewise_gaps_cannot_fall_back_to_declared_scalar_clock(self):
        spec = self.piecewise(self.spec(), defect="missing")
        spec["capture"]["timeline"] = {"continuity": "CONTINUOUS", "gaps": [], "overflow_samples": 0}
        spec["capture"]["clock"]["sample_zero_ns"] = 7_000_000_000
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["events"][0]["av_status"])
        self.assertNotIn("capture_time_ns", result["events"][0])

    def test_sample_clock_no_usable_brackets_has_no_clock_measurement(self):
        spec = self.piecewise(self.spec())
        with (self.folder / "track.csv").open(newline="", encoding="utf-8") as source:
            rows = list(csv.DictReader(source))
        for row in rows:
            row["audio_underrun_count"] = ""
        with (self.folder / "track.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=(*track.FIELDS, track.UNDERRUN_FIELD))
            writer.writeheader()
            writer.writerows(rows)
        clock = json.loads((self.folder / "sample-clock.json").read_text())
        producer = {"schema": "benchmark-audio-track-observation-v1", **clock["scope"], "clock": clock["clock"],
                    **clock["evidence"], "controls": clock["controls"]}
        producer["chunks"] = self.pointer("track.csv")
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", track.analyze(producer, self.folder))
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["scope"]["capture_sample_clock_status"])
        self.assertTrue(all(row["av_status"] == "UNMEASURED" for row in result["events"]))

    def test_changed_wav_or_manipulated_clock_spans_cannot_be_bound(self):
        spec = self.piecewise(self.spec())
        wav_path = self.folder / "capture.wav"
        raw = bytearray(wav_path.read_bytes())
        raw[-2] ^= 1
        wav_path.write_bytes(raw)
        spec["capture"]["wav"] = self.pointer("capture.wav")
        with self.assertRaisesRegex(ValueError, "Sample clock WAV/hash"):
            self.analyze(spec)
        spec = self.piecewise(self.spec())
        clock = json.loads((self.folder / "sample-clock.json").read_text())
        clock["spans"][0]["end_ns"] += 500_000
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", clock)
        with self.assertRaisesRegex(ValueError, "differs from recomputed"):
            self.analyze(spec)

    def test_piecewise_declared_clock_and_native_count_must_match(self):
        spec = self.piecewise(self.spec())
        spec["capture"]["clock"]["id"] = "other-device-clock"
        with self.assertRaisesRegex(ValueError, "Declared capture clock differs"):
            self.analyze(spec)
        spec = self.piecewise(self.spec())
        clock = json.loads((self.folder / "sample-clock.json").read_text())
        clock["sample_count"] -= 1
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", clock)
        with self.assertRaisesRegex(ValueError, "native format differs"):
            self.analyze(spec)

    def test_clock_origin_scope_cannot_be_relabelled_as_different_receiver(self):
        spec = self.piecewise(self.spec())
        spec["capture"]["execution_scope"] = {"run_id": "run-1", "device": "other", "pid": 17, "session": "session-1"}
        with self.assertRaisesRegex(ValueError, "scope differs from sample clock origin"):
            self.analyze(spec)

    def test_piecewise_clock_rechecks_underlying_csv_and_unknown_ranges(self):
        spec = self.piecewise(self.spec())
        (self.folder / "track.csv").write_text("changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.analyze(spec)
        spec = self.piecewise(self.spec())
        clock = json.loads((self.folder / "sample-clock.json").read_text())
        clock["unknown_ranges"] = []
        spec["capture"]["sample_clock"] = self.document("sample-clock.json", clock)
        with self.assertRaisesRegex(ValueError, "unknown_ranges differs"):
            self.analyze(spec)

    def test_execution_scope_required_on_both_inputs_and_must_match(self):
        scope = {"run_id": "run-1", "device": "a", "pid": 17, "session": "session-1"}
        for mode in ("same", "phone", "pid", "run", "visual_missing", "capture_missing"):
            with self.subTest(mode=mode):
                spec = self.piecewise(self.spec())
                visual = json.loads((self.folder / "visual.json").read_text())
                spec["capture"]["execution_scope"] = dict(scope)
                visual["execution_scope"] = dict(scope)
                if mode in {"phone", "pid", "run"}:
                    field = {"phone": "device", "pid": "pid", "run": "run_id"}[mode]
                    visual["execution_scope"][field] = 99 if field == "pid" else "another"
                elif mode == "visual_missing":
                    visual.pop("execution_scope")
                elif mode == "capture_missing":
                    spec["capture"].pop("execution_scope")
                spec["visual_observations"] = self.document("visual.json", visual)
                result = self.analyze(spec)
                self.assertTrue(all(row["av_status"] == ("MEASURED" if mode == "same" else "UNMEASURED") for row in result["events"]))

    def test_actual_visual_adapter_output_joins_canonical_integer_generation(self):
        # Reuse the independently tested native marker fixture, then call the
        # real visual decoder; this is an adapter test, not phone acceptance.
        from test_score_visual_markers import VisualMarkersTest
        import score_visual_markers as visual
        fixture = VisualMarkersTest(methodName="runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        spec = self.spec(visual=False)
        spec["plans"][0]["source_end_sample"] = 9600
        visual_spec = fixture.spec(contexts=[("1", 0)] * 6,
                                   times=[7_000_000_000 + i * 1_000_000_000 // 30 for i in range(6)])
        visual_spec["planned"].update(clock="capture-1", start_ns=7_000_000_000, end_ns=7_200_000_000)
        visual_spec["planned"]["segments"][0]["start_ns"] = 7_000_000_000
        visual_spec["planned"]["segments"][0]["stream_generation"] = "1"
        observed = visual.analyze(visual_spec, fixture.folder)
        self.assertEqual("MEASURED", observed["scope"]["timing_status"])
        scope = {"run_id": "run-1", "device": "a", "pid": 17, "session": "session-1"}
        observed["execution_scope"] = scope
        spec["capture"]["execution_scope"] = scope
        spec["visual_observations"] = self.document("real-visual.json", observed)
        result = self.analyze(spec)
        self.assertEqual(1, len(result["events"]))
        self.assertEqual("1", result["events"][0]["stream_generation"])
        self.assertEqual("MEASURED", result["events"][0]["av_status"])
        self.assertAlmostEqual(50, result["events"][0]["audio_minus_video_ms"], places=6)

    def test_generation_string_is_explicit_and_empty_or_negative_is_invalid(self):
        spec = self.spec()
        spec["plans"][0]["stream_generation"] = "actual-stream-7"
        observed = json.loads((self.folder / "visual.json").read_text())
        for row in observed["observations"]:
            row["stream_generation"] = "actual-stream-7"
        spec["visual_observations"] = self.document("visual.json", observed)
        self.assertTrue(all(row["av_status"] == "MEASURED" for row in self.analyze(spec)["events"]))
        for value in (-1, "", True):
            spec["plans"][0]["stream_generation"] = value
            with self.assertRaisesRegex(ValueError, "stream_generation"):
                self.analyze(spec)

    def test_gain_noise_and_polarity_preserve_exact_cue_time(self):
        spec = self.spec()
        with wave.open(str(self.folder / "capture.wav"), "rb") as source:
            values = np.frombuffer(source.readframes(source.getnframes()), "<i2").astype(np.float64)
        noise = np.random.default_rng(123).normal(0, 100, len(values))
        values = np.rint(-values * 0.4 + noise).astype("<i2")
        spec["capture"]["wav"] = self.wav("capture.wav", values)
        result = self.analyze(spec)
        self.assertEqual({"MATCHED": 3}, result["metrics"]["cue_counts"])
        self.assertTrue(all(row["polarity"] == -1 for row in result["events"]))
        self.assertTrue(all(row["nominal_offset_ms"] == 50 for row in result["events"]))

    def test_silent_capture_is_missing_not_zero_delay(self):
        spec = self.spec()
        spec["capture"]["wav"] = self.wav("capture.wav", np.zeros(self.count, dtype="<i2"))
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertEqual({"MISSING": 3}, result["metrics"]["cue_counts"])
        self.assertFalse(result["metrics"]["planned_timeline_complete"])
        self.assertIsNone(result["metrics"]["audio_minus_video_ms"]["max"])

    def test_two_identical_candidates_are_ambiguous(self):
        spec = self.spec(delay=0)
        with wave.open(str(self.folder / "capture.wav"), "rb") as source:
            signal = np.frombuffer(source.readframes(source.getnframes()), "<i2").copy()
        cue = self.cues[0]
        signal[cue["sample_index"] + 2000:cue["sample_index"] + 2000 + cue["sample_count"]] = self.signal[cue["sample_index"]:cue["sample_index"] + cue["sample_count"]]
        spec["capture"]["wav"] = self.wav("capture.wav", signal)
        result = self.analyze(spec)
        self.assertEqual("AMBIGUOUS", result["events"][0]["status"])
        self.assertEqual(2, len(result["events"][0]["candidates"]))
        self.assertEqual("UNMEASURED", result["events"][0]["av_status"])

    def test_different_or_missing_visual_clock_cannot_produce_av_claim(self):
        for mode in ("missing", "different", "unmeasured"):
            with self.subTest(mode=mode):
                spec = self.spec(visual=mode != "missing")
                if mode != "missing":
                    visual = json.loads((self.folder / "visual.json").read_text())
                    if mode == "different":
                        visual["scope"]["clock"] = "another-phone-boot"
                    else:
                        visual["scope"]["timing_status"] = "UNMEASURED"
                    spec["visual_observations"] = self.document("visual.json", visual)
                result = self.analyze(spec)
                self.assertEqual(3, result["metrics"]["cue_counts"]["MATCHED"])
                self.assertTrue(all(row["av_status"] == "UNMEASURED" for row in result["events"]))

    def test_corrupt_hash_is_rejected_before_measurement(self):
        spec = self.spec()
        spec["capture"]["wav"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.analyze(spec)

    def test_clipping_and_full_planned_tail_are_visible(self):
        spec = self.spec(capture_count=60000)
        with wave.open(str(self.folder / "capture.wav"), "rb") as source:
            values = np.frombuffer(source.readframes(source.getnframes()), "<i2").copy()
        values[-25:] = -32768
        values[-50:-25] = 32767
        spec["capture"]["wav"] = self.wav("capture.wav", values)
        result = self.analyze(spec)
        self.assertEqual(50, result["metrics"]["capture_pcm"]["clipped_samples"])
        self.assertEqual(84000, result["metrics"]["nominal_uncovered_samples"])
        self.assertEqual("UNCOVERED", result["events"][-1]["status"])
        self.assertFalse(result["metrics"]["planned_timeline_complete"])

    def test_pcm_header_with_truncated_payload_is_invalid(self):
        spec = self.spec()
        target = self.folder / "capture.wav"
        target.write_bytes(target.read_bytes()[:-40])
        spec["capture"]["wav"] = self.pointer("capture.wav")
        with self.assertRaisesRegex(ValueError, "Truncated PCM"):
            self.analyze(spec)

    def test_reusing_same_capture_for_two_loops_is_not_guessed(self):
        spec = self.spec()
        spec["plans"].append({**spec["plans"][0], "loop_iteration": 1})
        result = self.analyze(spec)
        self.assertEqual({"AMBIGUOUS": 6}, result["metrics"]["cue_counts"])
        self.assertEqual("UNMEASURED", result["measurement_status"])

    def test_duplicate_planned_identity_is_rejected(self):
        spec = self.spec()
        spec["plans"].append(dict(spec["plans"][0]))
        with self.assertRaisesRegex(ValueError, "Repeated planned cue"):
            self.analyze(spec)

    def test_stereo_or_other_rate_is_explicitly_unsupported(self):
        for channels, rate in ((2, 48000), (1, 44100)):
            spec = self.spec()
            spec["capture"]["wav"] = self.wav("capture.wav", np.zeros(1000 * channels, dtype="<i2"), channels, rate)
            result = self.analyze(spec)
            self.assertEqual("UNMEASURED", result["measurement_status"])
            self.assertTrue(any("Unsupported PCM" in line for line in result["limitations"]))

    def test_partially_planned_cue_remains_unmeasured(self):
        spec = self.spec()
        spec["plans"][0]["source_start_sample"] = 5000
        spec["plans"][0]["capture_start_sample"] = 5000
        result = self.analyze(spec)
        self.assertEqual("UNMEASURED", result["events"][0]["status"])
        self.assertIn("clips", result["events"][0]["reason"])

    def test_validated_scoped_gate_never_passes_whole_acoustic_quality(self):
        spec = self.spec()
        spec["acceptance"] = {"validation": self.document("validation.json", {
            "schema": "benchmark-audio-detector-validation-v1", "status": "VALIDATED",
            "correlation": {"minimum": 0.8, "peak_margin": 0.05},
            "validated_measurements": ["audio_missing_cues", "audio_av_offset_abs_ms"]}),
            "limits": {"audio_missing_cues": 0, "audio_av_offset_abs_ms": 20}}
        result = self.analyze(spec)
        checks = {row["name"]: row for row in result["measurements"]}
        self.assertEqual("PASS", checks["audio_missing_cues"]["status"])
        self.assertEqual("FAIL", checks["audio_av_offset_abs_ms"]["status"])
        self.assertEqual("UNMEASURED", checks["acoustic_quality"]["status"])

    def test_unvalidated_detector_controls_cannot_use_old_validation(self):
        spec = self.spec()
        spec["acceptance"] = {"validation": self.document("validation.json", {
            "schema": "benchmark-audio-detector-validation-v1", "status": "VALIDATED",
            "correlation": {"minimum": 0.95, "peak_margin": 0.05}, "validated_measurements": []}), "limits": {}}
        with self.assertRaisesRegex(ValueError, "controls differ"):
            self.analyze(spec)

    def test_analysis_does_not_modify_reference_and_cli_requires_fresh_output(self):
        spec = self.spec()
        original = (self.folder / "reference.wav").read_bytes()
        specification = self.folder / "spec.json"
        specification.write_text(json.dumps(spec), encoding="utf-8")
        target = self.folder / "result.json"
        self.assertEqual(0, audio.main(["--spec", str(specification), "--output", str(target)]))
        self.assertEqual(original, (self.folder / "reference.wav").read_bytes())
        saved = target.read_bytes()
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            audio.main(["--spec", str(specification), "--output", str(target)])
        self.assertEqual(saved, target.read_bytes())

    def test_chunk_boundary_keeps_exact_cue_start(self):
        rng = np.random.default_rng(99)
        template = rng.integers(-16000, 16000, 960).astype(np.float64)
        samples = np.zeros(audio.CHUNK + 2000, dtype="<i2")
        samples[audio.CHUNK - 3:audio.CHUNK - 3 + len(template)] = template
        pointer = self.wav("boundary.wav", samples)
        pcm = audio.Pcm(self.folder / pointer["path"])
        try:
            result = audio.search(pcm, template, 0, len(samples) - len(template), 0.8, 0.05)
        finally:
            pcm.close()
        self.assertEqual("MATCHED", result["status"])
        self.assertEqual(audio.CHUNK - 3, result["capture_sample"])


if __name__ == "__main__":
    unittest.main()
