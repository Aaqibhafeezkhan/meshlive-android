"""Offline regressions for the decoded-PCM speech quality scorer; generated traces are not phone results."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave

import numpy as np

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score_audio_quality as quality

RATE = 48000
CHUNK = 960
COMMITTED = Path(__file__).resolve().parents[2] / "benchmarks" / "inputs" / "speech-v1"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_wav(path, pcm):
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(RATE)
        target.writeframes(np.asarray(pcm, dtype="<i2").tobytes())


def voiced(seconds, f0_start, seed):
    """Speech-like harmonic burst train: gliding pitch, 4 Hz syllable envelope, formant-ish tilt and a little noise."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    phase = 2 * np.pi * np.cumsum(f0_start * (1 + 0.15 * np.sin(2 * np.pi * 0.7 * t + seed)) / RATE)
    signal = sum(np.sin(k * phase) / k for k in range(1, 25))
    envelope = np.clip(np.sin(2 * np.pi * 4 * t + seed), 0, None) ** 0.5
    rng = np.random.default_rng(seed)
    signal = signal * envelope + 0.02 * rng.standard_normal(n)
    return signal / np.max(np.abs(signal))


class QualityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="audio-quality-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        step = voiced(2.0, 140, 3)
        parts = [("speech_a", "speech_clean", 0.5 * voiced(4.0, 120, 1)), ("silence", "room_tone", np.zeros(RATE)),
                 ("step_1", "level_step", 0.1 * step), ("step_2", "level_step", 0.4 * step)]
        self.reference_float = np.concatenate([p[2] for p in parts])
        self.reference = np.round(self.reference_float * 32768).astype("<i2")
        cursor, segments = 0, []
        for name, kind, x in parts:
            segment = {"id": name, "kind": kind, "scoring": "silence" if kind == "room_tone" else "speech",
                       "start_sample": cursor, "end_sample": cursor + len(x)}
            if kind == "level_step":
                segment["group"] = "loudness_steps"
            segments.append(segment)
            cursor += len(x)
        self.reference_path = self.folder / "reference.wav"
        write_wav(self.reference_path, self.reference)
        self.provenance = self.folder / "speech.provenance.json"
        self.provenance.write_text(json.dumps({"variants": {"synthetic": {
            "file": "reference.wav", "sha256": sha(self.reference_path), "sample_count": len(self.reference),
            "segments": segments}}}), encoding="utf-8")

    def trace(self, name="phase", offset=17000, seconds=20, gain=1.0, drop=(), finalized=True, source=None, noise=0.0):
        """Write a DebugAudioTrace-shaped receiver trace of the looped reference starting `offset` samples in."""
        source = self.reference if source is None else source
        total = seconds * RATE // CHUNK * CHUNK
        looped = np.take(source.astype(np.float64), np.arange(offset, offset + total), mode="wrap") * gain
        looped = looped + noise * np.random.default_rng(5).standard_normal(total)
        pcm, rows, written = [], ["sample_start,sample_count,source_pts_us,stage,at_ns,audio_frame_position,audio_timestamp_ns,audio_underrun_count"], 0
        for i in range(total // CHUNK):
            if i in drop:
                continue
            chunk = np.round(np.clip(looped[i * CHUNK:(i + 1) * CHUNK], -32768, 32767)).astype("<i2")
            pcm.append(chunk)
            rows.append(f"{written},{CHUNK},{5_000_000 + i * 20000},track_accepted,{i * 20000000},,,0")
            written += CHUNK
        wav, csv_path, summary = (self.folder / f"{name}.wav", self.folder / f"{name}.csv", self.folder / f"{name}-summary.json")
        write_wav(wav, np.concatenate(pcm))
        csv_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        summary.write_text(json.dumps({"schema": "benchmark-pcm-trace-v1", "session": "s", "phase": name, "role": "receiver",
                                       "pid": 7, "clock": "System.nanoTime", "sample_rate": RATE, "channels": 1,
                                       "sample_count": written, "attempted_samples": written, "trace_overflow_chunks": 0,
                                       "finalized": finalized, "error": None}), encoding="utf-8")
        return {"wav": wav, "csv": csv_path, "summary": summary}

    def score(self, trace, **kwargs):
        return quality.analyze(self.reference_path, self.provenance, trace, **kwargs)

    def test_loop_offset_level_and_no_gap_are_recovered(self):
        result = self.score(self.trace(offset=17000))
        self.assertEqual(result["measurement_status"], "MEASURED")
        alignment = result["metrics"]["alignment"]
        self.assertTrue(alignment["confident"])
        self.assertEqual(alignment["loop_offset_samples"], 17000)
        self.assertEqual(result["metrics"]["dropout"]["gap_events"], 0)
        self.assertAlmostEqual(result["metrics"]["level"]["speech_level_error_db_mean"], 0.0, delta=0.1)
        self.assertGreaterEqual(len(result["segments"]), 4)
        self.assertEqual(result["physical_audio_quality"], "UNMEASURED")

    def test_sender_origin_is_only_a_consistency_check(self):
        result = self.score(self.trace(offset=17000), origin_pts_us=5_000_000 - 17000 * 1_000_000 // RATE)
        self.assertAlmostEqual(result["metrics"]["alignment"]["sender_origin_check_ms"], 0.0, delta=0.1)

    def test_gain_becomes_level_error_and_step_slope_stays_one(self):
        result = self.score(self.trace(gain=0.5))
        self.assertAlmostEqual(result["metrics"]["level"]["speech_level_error_db_mean"], -6.02, delta=0.2)
        slope = result["metrics"]["level"]["loudness_step_response"]["slope"]
        self.assertAlmostEqual(slope, 1.0, delta=0.05)

    def test_compressed_steps_lower_the_slope(self):
        squashed = self.reference_float.copy()
        squashed[-2 * RATE:] *= 0.25   # the loud step is attenuated, as an AGC/limiter would
        result = self.score(self.trace(source=np.round(squashed * 32768).astype("<i2")))
        self.assertLess(result["metrics"]["level"]["loudness_step_response"]["slope"], 0.8)

    def test_lost_chunks_are_gaps_not_hidden_by_concatenation(self):
        result = self.score(self.trace(offset=0, drop=(60, 61, 62)))
        dropout = result["metrics"]["dropout"]
        self.assertEqual(dropout["gap_events"], 1)
        self.assertAlmostEqual(dropout["max_gap_ms"], 60.0, delta=1.0)
        self.assertGreater(dropout["gap_share"], 0.0)
        self.assertGreater(dropout["speech_active_frame_dropout_share"], 0.0)

    def test_unrelated_audio_is_not_scored(self):
        noise = np.round(8000 * np.random.default_rng(9).standard_normal(len(self.reference))).astype("<i2")
        result = self.score(self.trace(source=noise))
        self.assertEqual(result["measurement_status"], "UNMEASURED")
        self.assertIn("alignment_not_confident_trace_does_not_match_reference_loop", result["issues"])

    def test_unfinalized_trace_is_rejected(self):
        result = self.score(self.trace(finalized=False))
        self.assertEqual(result["measurement_status"], "UNMEASURED")
        self.assertIn("trace_rejected_nothing_scored", result["issues"])

    def test_hash_binding_and_variant_match_are_enforced(self):
        trace = self.trace()
        with self.assertRaises(ValueError):
            self.score(trace, expected={"wav": "0" * 64})
        self.assertEqual(self.score(trace, expected={"wav": sha(trace["wav"])})["measurement_status"], "MEASURED")
        write_wav(self.folder / "other.wav", self.reference[::-1])
        with self.assertRaises(ValueError):
            quality.analyze(self.folder / "other.wav", self.provenance, trace)

    def test_external_visqol_command_is_parsed(self):
        fake = self.folder / "fake_visqol.py"
        fake.write_text("import sys\nassert '--use_speech_scoring' in sys.argv\nprint('MOS-LQO:\\t4.25')\n", encoding="utf-8")
        result = self.score(self.trace(), visqol=[sys.executable, str(fake)])
        self.assertEqual(result["perceptual"]["summary"]["visqol_speech"]["status"], "MEASURED")
        self.assertAlmostEqual(result["perceptual"]["summary"]["visqol_speech"]["duration_weighted_mean"], 4.25)

    def test_visqol_is_declared_unmeasured_when_absent(self):
        summary = self.score(self.trace())["perceptual"]["summary"]
        self.assertEqual(summary["visqol_speech"]["status"], "UNMEASURED")

    def test_cli_writes_a_fresh_result_and_refuses_overwrite(self):
        trace = self.trace()
        output = Path(tempfile.gettempdir()) / f"quality-cli-{self.folder.name}.json"
        self.addCleanup(lambda: output.unlink(missing_ok=True))
        arguments = ["--reference", str(self.reference_path), "--provenance", str(self.provenance), "--wav", str(trace["wav"]),
                     "--csv", str(trace["csv"]), "--summary", str(trace["summary"]), "--output", str(output)]
        self.assertEqual(quality.main(arguments), 0)
        self.assertEqual(json.loads(output.read_text())["schema"], "benchmark-audio-quality-v1")
        with self.assertRaises(SystemExit):
            quality.main(arguments)


@unittest.skipUnless(importlib.util.find_spec("pesq") and importlib.util.find_spec("pystoi")
                     and (COMMITTED / "speech-v1.wav").exists(), "needs pesq, pystoi and the committed speech-v1 build")
class PerceptualSpeechTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="audio-quality-speech-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)

    def trace_of(self, name, pcm):
        total = len(pcm) // CHUNK * CHUNK
        rows = ["sample_start,sample_count,source_pts_us,stage,at_ns"]
        rows += [f"{i * CHUNK},{CHUNK},{1_000_000 + i * 20000},track_accepted,0" for i in range(total // CHUNK)]
        write_wav(self.folder / f"{name}.wav", pcm[:total])
        (self.folder / f"{name}.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")
        (self.folder / f"{name}-summary.json").write_text(json.dumps({
            "schema": "benchmark-pcm-trace-v1", "role": "receiver", "sample_count": total, "trace_overflow_chunks": 0,
            "finalized": True, "error": None}), encoding="utf-8")
        return {k: self.folder / n for k, n in (("wav", f"{name}.wav"), ("csv", f"{name}.csv"), ("summary", f"{name}-summary.json"))}

    def score(self, pcm, name="t"):
        return quality.analyze(COMMITTED / "speech-v1.wav", COMMITTED / "speech-v1.provenance.json", self.trace_of(name, pcm))

    def test_identity_is_near_the_ceiling_and_a_gap_lowers_it(self):
        reference = quality.read_wav(COMMITTED / "speech-v1.wav")
        pcm = np.round(reference * 32768).astype("<i2")
        clean = self.score(pcm)["perceptual"]["summary"]
        self.assertGreater(clean["pesq_wb"]["duration_weighted_mean"], 4.4)
        self.assertGreater(clean["stoi"]["duration_weighted_mean"], 0.99)
        damaged = pcm.copy()
        damaged[10 * RATE:12 * RATE] = 0
        worse = self.score(damaged, "damaged")["perceptual"]["summary"]
        self.assertLess(worse["pesq_wb"]["min"], clean["pesq_wb"]["min"] - 0.5)

    @unittest.skipUnless(shutil.which("ffmpeg") and "libopus" in subprocess.run(
        ["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout if shutil.which("ffmpeg") else False,
        "needs ffmpeg with libopus")
    def test_lower_opus_bitrate_scores_lower(self):
        scores = {}
        for kbps in (64, 8):
            encoded, decoded = self.folder / f"{kbps}.opus", self.folder / f"{kbps}.wav"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(COMMITTED / "speech-v1.wav"), "-c:a", "libopus", "-b:a",
                            f"{kbps}k", "-application", "voip", str(encoded)], check=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(encoded), "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
                            str(decoded)], check=True)
            pcm = np.round(quality.read_wav(decoded)[:2880000] * 32768).astype("<i2")
            result = self.score(pcm, f"opus{kbps}")
            scores[kbps] = result["perceptual"]["summary"]["pesq_wb"]["duration_weighted_mean"]
            self.assertTrue(result["metrics"]["alignment"]["confident"])
        self.assertGreater(scores[64], scores[8] + 0.3)


if __name__ == "__main__":
    unittest.main()
