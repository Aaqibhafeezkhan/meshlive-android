"""Offline regressions for the speech reference builder; tiny synthetic sources stand in for the real recordings."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import shutil
import tarfile
import tempfile
import unittest
import wave
from unittest import mock

import numpy as np
import soundfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_speech_reference as speech
import run_device_benchmark as runner

COMMITTED = Path(__file__).resolve().parents[2] / "benchmarks" / "inputs" / "speech-v1"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def tone_burst(seconds, rate, seed, level=0.3):
    t = np.arange(int(seconds * rate)) / rate
    wobble = 1 + 0.2 * np.sin(2 * np.pi * 0.9 * t + seed)
    x = sum(np.sin(2 * np.pi * 110 * seed * k * t * wobble) / k for k in range(1, 8))
    x = x * np.clip(np.sin(2 * np.pi * 3 * t), 0, None)
    return level * x / np.max(np.abs(x))


class BuilderTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="speech-build-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.sources = self.folder / "sources"
        self.sources.mkdir()
        rows = {}
        for name, rate, kind, seed in (("a", 16000, "librispeech", 1), ("b", 16000, "librispeech", 2), ("c", 16000, "librispeech", 3),
                                       ("d", 16000, "librispeech", 4), ("e", 16000, "librispeech", 5), ("m", 44100, "music", 6)):
            path = self.sources / f"{name}.flac"
            soundfile.write(str(path), np.round(tone_burst(4.0, rate, seed) * 32768).astype("<i2"), rate, format="FLAC", subtype="PCM_16")
            rows[name] = {"id": name, "file": path.name, "kind": kind, "language": "en", "sha256": sha(path),
                          "licence": "test", "fetch": {"type": "tar_member", "member": f"x/{name}.flac"}}
        timeline = [
            ("guard", "digital_silence", {"ms": 250}),
            ("clean", "speech_clean", {"clip": {"source": "a", "start_ms": 100, "end_ms": 1500}}),
            ("quiet", "room_tone", {"ms": 500}),
            ("step", "level_step", {"clip": {"source": "b", "start_ms": 0, "end_ms": 1000}, "peak_dbfs": -24}),
            ("talk", "double_talk", {"a": {"source": "a", "start_ms": 0, "end_ms": 1500}, "b": {"source": "b", "start_ms": 0, "end_ms": 1000}, "b_offset_ms": 500}),
            ("babble", "speech_babble", {"clip": {"source": "c", "start_ms": 0, "end_ms": 1200}, "snr_db": 10,
                                         "talkers": [{"source": "d", "start_ms": 0}, {"source": "e", "start_ms": 100}]}),
            ("noise", "speech_noise", {"clip": {"source": "d", "start_ms": 0, "end_ms": 1000}, "snr_db": 15, "seed_offset": 5}),
            ("music", "music", {"clip": {"source": "m", "start_ms": 0, "end_ms": 1000}}),
            ("end", "digital_silence", {"fill": True}),
        ]
        patches = [mock.patch.object(speech, "SOURCES", rows),
                   mock.patch.object(speech, "VARIANTS", {"speech-v1": {"duration_ms": 10000, "timeline": timeline}})]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)
        self.rows = rows

    def build(self, name="out"):
        return speech.build(self.sources, self.folder / name), self.folder / name

    def test_build_is_exact_deterministic_and_app_loadable(self):
        first, folder = self.build("one")
        second, other = self.build("two")
        variant = first["variants"]["speech-v1"]
        self.assertEqual(variant["sha256"], second["variants"]["speech-v1"]["sha256"])
        self.assertEqual(first["recipe_sha256"], second["recipe_sha256"])
        wav = folder / "speech-v1.wav"
        self.assertEqual(wav.stat().st_size, 44 + 2 * 480000)
        with wave.open(str(wav), "rb") as handle:
            self.assertEqual((handle.getnchannels(), handle.getsampwidth(), handle.getframerate(), handle.getnframes()), (1, 2, 48000, 480000))
        self.assertEqual(int.from_bytes(wav.read_bytes()[4:8], "little") + 8, wav.stat().st_size)  # ReferencePcmInput header rule
        segments = variant["segments"]
        self.assertEqual(segments[0]["start_sample"], 0)
        self.assertEqual(segments[-1]["end_sample"], 480000)
        for before, after in zip(segments, segments[1:]):
            self.assertEqual(before["end_sample"], after["start_sample"])
        by_id = {s["id"]: s for s in segments}
        self.assertAlmostEqual(by_id["step"]["peak_dbfs"], -24.0, delta=0.01)
        self.assertEqual(by_id["step"]["group"], "loudness_steps")
        self.assertAlmostEqual(by_id["clean"]["active_rms_dbfs"], -26.0, delta=0.6)   # fade at the edges moves it slightly
        self.assertAlmostEqual(by_id["quiet"]["rms_dbfs"], -65.0, delta=0.2)
        self.assertEqual(by_id["guard"]["scoring"], "silence")
        self.assertEqual(by_id["music"]["scoring"], "music")
        self.assertLess(variant["sample_peak_dbfs"], -0.9)
        self.assertIn("whole samples", first["loop_rule"])

    def test_silent_segments_are_digital_zero_only_for_guards(self):
        _, folder = self.build()
        with wave.open(str(folder / "speech-v1.wav"), "rb") as handle:
            pcm = np.frombuffer(handle.readframes(handle.getnframes()), dtype="<i2")
        self.assertTrue((pcm[:12000] == 0).all())
        self.assertTrue((pcm[-4800:] == 0).all())
        self.assertFalse((pcm[12000 + 67200:12000 + 67200 + 24000] == 0).all())

    def test_noise_generator_is_pinned(self):
        np.testing.assert_allclose(speech.gaussian(1, 4), speech.gaussian(1, 4))
        self.assertEqual(len(speech.gaussian(3, 5)), 5)
        self.assertAlmostEqual(float(np.mean(speech.gaussian(9, 200000))), 0.0, delta=0.02)
        self.assertAlmostEqual(float(np.std(speech.gaussian(9, 200000))), 1.0, delta=0.02)

    def test_source_hash_mismatch_clipping_and_short_fill_are_refused(self):
        self.rows["a"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "differs from its recorded SHA256"):
            self.build()
        self.rows["a"]["sha256"] = sha(self.sources / "a.flac")
        with mock.patch.object(speech, "PEAK_LIMIT", 0.01), self.assertRaisesRegex(ValueError, "no silent clipping"):
            self.build("clip")
        with mock.patch.dict(speech.VARIANTS["speech-v1"], {"duration_ms": 6000}), self.assertRaisesRegex(ValueError, "leaves"):
            self.build("short")

    def test_existing_build_is_never_overwritten(self):
        self.build()
        with self.assertRaises(FileExistsError):
            self.build()

    def test_verify_reports_a_changed_wav(self):
        _, folder = self.build()
        self.assertEqual(speech.verify(self.sources, folder)["state"], "VERIFIED")
        data = bytearray((folder / "speech-v1.wav").read_bytes())
        data[10000] ^= 1
        (folder / "speech-v1.wav").write_bytes(bytes(data))
        self.assertEqual(speech.verify(self.sources, folder)["state"], "MISMATCH")

    def test_attach_selects_by_exact_loop_length_and_excludes_the_rest(self):
        _, built = self.build()
        base = self.folder / "video"
        base.mkdir()
        (base / "gen.wav").write_bytes(b"original generator audio")
        fixtures = {"schema_version": 1, "fixtures": [
            {"width": 2, "height": 2, "fps": 10, "format": "I420", "bit_depth": 8, "path": "a.yuv", "sha256": "0" * 64, "frame_count": 100,
             "sidecars": {"reference_audio": {"path": "gen.wav", "sha256": "1" * 64}, "frames": {"path": "f.csv", "sha256": "2" * 64}}},
            {"width": 4, "height": 2, "fps": 10, "format": "I420", "bit_depth": 8, "path": "b.yuv", "sha256": "0" * 64, "frame_count": 50},
            {"width": 6, "height": 2, "fps": 7, "format": "I420", "bit_depth": 8, "path": "c.yuv", "sha256": "0" * 64, "frame_count": 1}]}
        (base / "raw-fixtures.json").write_text(json.dumps(fixtures), encoding="utf-8")
        target = Path(tempfile.gettempdir()) / f"speech-attach-{self.folder.name}"
        self.addCleanup(lambda: shutil.rmtree(target, ignore_errors=True))
        report = speech.attach(base / "raw-fixtures.json", built, target)
        self.assertEqual([a["fixture"] for a in report["attached"]], ["2x2@10"])
        self.assertEqual(len(report["excluded"]), 2)
        self.assertIn("fractional", str(report["excluded"][1]["loop_samples"]))
        result = json.loads((target / "raw-fixtures.json").read_text())
        sidecars = result["fixtures"][0]["sidecars"]
        self.assertEqual(sidecars["reference_audio"]["sha256"], sha(built / "speech-v1.wav"))
        self.assertEqual(sidecars["generator_reference_audio"]["sha256"], "1" * 64)
        self.assertEqual(Path(target, sidecars["reference_audio"]["path"]).resolve(), (built / "speech-v1.wav").resolve())
        with self.assertRaises(FileExistsError):
            speech.attach(base / "raw-fixtures.json", built, target)

    def test_attached_audio_passes_the_runner_whole_sample_loop_rule(self):
        _, built = self.build()
        raw = self.folder / "raw.yuv"
        raw.write_bytes(bytes(6 * 100))
        case = {"raw_fixture_file": str(raw), "profile": {"width": 2, "height": 2, "fps": 10}, "media_trace": True,
                "reference_audio": {"path": str(built / "speech-v1.wav"), "sha256": sha(built / "speech-v1.wav")}}
        self.assertEqual(runner.reference_audio_metadata(case)["sample_count"], 480000)
        raw.write_bytes(bytes(6 * 99))
        with self.assertRaisesRegex(ValueError, "whole-sample"):
            runner.reference_audio_metadata(case)

    def test_attached_manifest_binds_with_reference_audio_end_to_end(self):
        import bind_benchmark_inputs as binding
        _, built = self.build()
        base = self.folder / "video"
        base.mkdir()
        raw = bytes(i % 251 for i in range(6 * 100))
        (base / "a.yuv").write_bytes(raw)
        color = {"range": "limited", "primaries": "bt709", "matrix": "bt709", "transfer": "bt709", "chroma_location": "left"}
        fixtures = {"schema_version": 1, "fixtures": [{"width": 2, "height": 2, "fps": 10, "format": "I420", "bit_depth": 8,
                    "path": "a.yuv", "sha256": hashlib.sha256(raw).hexdigest(), "frame_count": 100, "color": color}]}
        (base / "raw-fixtures.json").write_text(json.dumps(fixtures), encoding="utf-8")
        attached = Path(tempfile.gettempdir()) / f"speech-bind-{self.folder.name}"
        bound = Path(tempfile.gettempdir()) / f"speech-bound-{self.folder.name}"
        self.addCleanup(lambda: [shutil.rmtree(p, ignore_errors=True) for p in (attached, bound)])
        speech.attach(base / "raw-fixtures.json", built, attached)
        profile = {"width": 2, "height": 2, "fps": 10, "bitrate_kbps": 100, "codec": "encoder", "rate_control": "cbr",
                   "playback": "balanced", "thermal_reduction": False}
        case = {"id": "camera-source", "state": "NOT RUN", "source": "s24", "transport": "classic", "profile": profile,
                "receivers": [{"phone": "s22", "parent": "s24"}], "duration_s": 90}
        suite = self.folder / "suite.json"
        suite.write_text(json.dumps({"schema_version": 1, "cases": [case]}), encoding="utf-8")
        registry = self.folder / "registry.json"
        registry.write_text(json.dumps({"devices": {p: {"serial": "offline-" + p, "node_prefix": f"{i + 1:012x}"}
                                                    for i, p in enumerate(("s24", "s22"))}}), encoding="utf-8")
        with mock.patch("subprocess.run", side_effect=AssertionError("no device process")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, binding.main(["--suite", str(suite), "--case", "camera-source", "--fixtures", str(attached / "raw-fixtures.json"),
                                              "--devices", str(registry), "--output", str(bound), "--with-reference-audio"]))
        bound_case = json.loads((bound / "raw-suite.json").read_text())["cases"][0]
        self.assertEqual(bound_case["reference_audio"]["sha256"], sha(built / "speech-v1.wav"))
        self.assertTrue(bound_case["media_trace"])
        self.assertEqual(bound_case["state"], "NOT RUN")

    def test_fetch_extracts_declared_members_and_verifies_the_archive(self):
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as tar:
            for name in ("a", "b", "c", "d", "e"):
                data = (self.sources / f"{name}.flac").read_bytes()
                info = tarfile.TarInfo(f"x/{name}.flac")
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        blob = archive.getvalue()
        music = self.rows.pop("m")
        for row in self.rows.values():
            row["fetch"]["member"] = f"x/{row['id']}.flac"
        constants = {**speech.LIBRISPEECH, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}
        with mock.patch.object(speech, "LIBRISPEECH", constants):
            hashes = speech.fetch(self.folder / "fetched", urlopen=lambda url, headers=None: io.BytesIO(blob))
            self.assertEqual(hashes["a"], self.rows["a"]["sha256"])
            self.assertEqual(sha(self.folder / "fetched" / "a.flac"), self.rows["a"]["sha256"])
            with self.assertRaisesRegex(ValueError, "differs from the recorded size/SHA256"):
                speech.fetch(self.folder / "fetched2", urlopen=lambda url, headers=None: io.BytesIO(blob + b"x"))
        self.rows["m"] = music


@unittest.skipUnless((COMMITTED / "speech-v1.wav").exists() and (COMMITTED / "sources").exists(), "committed speech-v1 build absent")
class CommittedBuildTest(unittest.TestCase):
    def test_committed_build_rebuilds_to_the_recorded_hashes(self):
        report = speech.verify(COMMITTED / "sources", COMMITTED)
        self.assertEqual(report["state"], "VERIFIED", report)

    def test_committed_variants_match_the_native_loop_lengths_and_carry_licences(self):
        provenance = json.loads((COMMITTED / "speech-v1.provenance.json").read_text(encoding="utf-8"))
        self.assertEqual(provenance["variants"]["speech-v1"]["sample_count"], 60 * 48000)
        self.assertEqual(provenance["variants"]["speech-v1-20s"]["sample_count"], 20 * 48000)
        languages = {row["language"] for row in provenance["sources"].values() if row["language"] != "-"}
        self.assertTrue({"en", "de", "es"} <= languages)
        for row in provenance["sources"].values():
            self.assertTrue(row["licence"] and row["origin_url"] and len(row["sha256"]) == 64)
        kinds = {s["kind"] for s in provenance["variants"]["speech-v1"]["segments"]}
        self.assertTrue({"level_step", "double_talk", "speech_babble", "speech_noise", "music", "room_tone"} <= kinds)
        peaks = sorted(s["peak_dbfs"] for s in provenance["variants"]["speech-v1"]["segments"] if s["kind"] == "level_step")
        self.assertEqual([round(p) for p in peaks], [-30, -24, -18, -12, -6])
        self.assertLess(sum(row["bytes"] for row in provenance["sources"].values()), 100 * 1024 * 1024)


if __name__ == "__main__":
    unittest.main()
