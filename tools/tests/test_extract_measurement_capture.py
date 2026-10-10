"""Bounded recording-import checks in OS temporary folders; no phone activity."""

import copy
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import extract_measurement_capture as capture
import score_audio_sync as audio_sync


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "Local FFmpeg/ffprobe not available")
class ExtractCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="measurement-capture-")
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.rgb = b"".join(bytes((index * 40, 80, 180)) * (64 * 64) for index in range(4))
        (self.folder / "source.rgb").write_bytes(self.rgb)
        self.audio = b"\x20\x03" * 9600
        (self.folder / "source.pcm").write_bytes(self.audio)
        self.input = self.make_recording()

    def run_media(self, args):
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def make_recording(self, name="recording.nut", audio_rate=48000, channels=1, audio_codec="pcm_s16le", audio=True):
        args = [shutil.which("ffmpeg"), "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "64x64", "-framerate", "25", "-i", str(self.folder / "source.rgb")]
        if audio:
            args += ["-f", "s16le", "-ar", str(audio_rate), "-ac", str(channels), "-i", str(self.folder / "source.pcm")]
        # Numerical raw-fixture mux only: deliberate original nonuniform PTS, no rendered test asset.
        args += ["-vf", "setpts=if(eq(N\\,0)\\,25\\,if(eq(N\\,1)\\,26\\,if(eq(N\\,2)\\,28\\,30)))", "-fps_mode", "passthrough",
                 "-c:v", "ffv1", "-pix_fmt", "bgr0"]
        if audio:
            args += ["-af", "asetpts=PTS+1.08/TB", "-c:a", audio_codec]
        path = self.folder / name
        args += ["-f", "nut", str(path)]
        self.run_media(args)
        return path

    def spec(self, path=None, audio=True):
        path = self.input if path is None else path
        document = json.loads(self.run_media([shutil.which("ffprobe"), "-v", "error", "-show_streams", "-of", "json", str(path)]))
        video = next(row for row in document["streams"] if row["codec_type"] == "video")
        color = {name: video.get(key, "UNSPECIFIED") for name, key in (("range", "color_range"), ("matrix", "color_space"),
                 ("primaries", "color_primaries"), ("transfer", "color_transfer"))}
        color = {name: "UNSPECIFIED" if value in {"unknown", "reserved", "unspecified"} else value for name, value in color.items()}
        spec = {"schema": "benchmark-capture-extract-v1", "input": {"path": str(path), "sha256": capture.digest(path)},
                "clock": "fixture-container-clock", "video": {"stream_index": video["index"], "width": 64, "height": 64,
                "rgb_conversion": "ffmpeg_native_rgb24", "source_color": color, "allow_unspecified_color": True},
                "expected_interval": {"start_ns": 1_000_000_000, "end_ns": 1_240_000_000}}
        if audio:
            stream = next((row for row in document["streams"] if row["codec_type"] == "audio"), {"index": 1})
            spec["audio"] = {"stream_index": stream["index"], "sample_rate": 48000, "channels": 1, "sample_format": "s16"}
        return spec

    def extract(self, spec=None, name="capture"):
        return capture.extract(self.spec() if spec is None else spec, self.folder, self.folder / name)

    def test_original_variable_video_pts_and_independent_audio_zero_are_preserved(self):
        original_hash = capture.digest(self.input)
        result = self.extract()
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual(self.rgb, Path(result["video"]["path"]).read_bytes())
        self.assertEqual(4, result["video"]["frame_count"])
        with Path(result["video"]["timestamps"]["path"]).open() as source:
            rows = list(capture.csv.DictReader(source))
        self.assertEqual([1_000_000_000, 1_040_000_000, 1_120_000_000, 1_200_000_000], [int(row["time_ns"]) for row in rows])
        self.assertEqual(1_080_000_000, result["audio"]["clock"]["sample_zero_ns"])
        self.assertEqual(9600, result["audio"]["sample_count"])
        self.assertEqual("CONTINUOUS", result["audio"]["timeline"]["continuity"])
        self.assertEqual("MEASURED", result["scope"]["audio_timing_status"])
        self.assertEqual(hashlib.sha256(self.audio).hexdigest(), result["audio"]["pcm_sha256"])
        self.assertEqual(80_000_000, result["audio"]["coverage"]["prefix_ns"])
        self.assertEqual(original_hash, capture.digest(self.input))
        argv = result["commands"][-1]["argv"]
        self.assertNotIn("-ar", argv)
        self.assertNotIn("-ac", argv)
        self.assertNotIn("-af", argv)
        video_argv = next(row["argv"] for row in result["commands"] if str(Path(result["video"]["path"])) in row["argv"])
        self.assertNotIn("-r", video_argv)
        self.assertNotIn("-vf", video_argv)
        self.assertIn("-noautorotate", video_argv)
        self.assertIn("passthrough", video_argv)

    def test_optional_audio_is_explicit_and_no_audio_selection_is_unmeasured(self):
        video_only = self.make_recording("video-only.nut", audio=False)
        result = self.extract(self.spec(video_only, audio=False))
        self.assertEqual("UNMEASURED", result["audio"]["measurement_status"])
        self.assertEqual("UNMEASURED", result["scope"]["audio_timing_status"])
        self.assertFalse((self.folder / "capture/audio.wav").exists())
        with self.assertRaisesRegex(ValueError, "absent or is not audio"):
            self.extract(self.spec(video_only), name="missing-audio")

    def test_unsupported_audio_rate_channels_and_decoder_depth_are_never_substituted(self):
        for options in ({"audio_rate": 44100}, {"channels": 2}, {"audio_codec": "pcm_f32le"}):
            with self.subTest(options=options):
                path = self.make_recording("unsupported-" + str(len(list(self.folder.glob('*.nut')))) + ".nut", **options)
                with self.assertRaisesRegex(ValueError, "Actual decoded audio"):
                    self.extract(self.spec(path), name="unsupported-result")
                self.assertFalse((self.folder / "unsupported-result").exists())

    def test_explicit_stereo_channel_keeps_exact_samples_and_original_clock(self):
        left, right = b"\x20\x03", b"\x90\xfd"
        (self.folder / "source.pcm").write_bytes((left + right) * 9600)
        path = self.make_recording("stereo.nut", channels=2)
        spec = self.spec(path)
        spec["audio"].update(channels=2, channel_index=1)
        result = self.extract(spec)
        with wave.open(result["audio"]["path"], "rb") as source:
            self.assertEqual(right * 9600, source.readframes(9600))
        self.assertEqual(1_080_000_000, result["audio"]["clock"]["sample_zero_ns"])
        self.assertEqual("CONTINUOUS", result["audio"]["timeline"]["continuity"])
        self.assertEqual(2, result["audio"]["channel_selection"]["native_channels"])
        self.assertEqual(1, result["audio"]["channel_selection"]["channel_index"])
        self.assertFalse((self.folder / "capture/audio-native.pcm").exists())
        argv = result["commands"][-1]["argv"]
        self.assertTrue(set(("-ar", "-ac", "-af")).isdisjoint(argv))
        for index in (None, -1, 2, True):
            spec["audio"]["channel_index"] = index
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, "explicit native channel_index"):
                self.extract(spec, name="invalid-channel")
        self.assertFalse((self.folder / "invalid-channel").exists())

    def test_wrong_hash_geometry_and_color_declarations_block_before_output(self):
        spec = self.spec()
        spec["input"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            self.extract(spec)
        spec = self.spec()
        spec["video"]["width"] = 62
        with self.assertRaisesRegex(ValueError, "native geometry"):
            self.extract(spec)
        spec = self.spec()
        del spec["video"]["rgb_conversion"]
        with self.assertRaisesRegex(ValueError, "rgb_conversion"):
            self.extract(spec)
        spec = self.spec()
        spec["video"]["allow_unspecified_color"] = False
        with self.assertRaisesRegex(ValueError, "Unknown color"):
            self.extract(spec)
        self.assertFalse((self.folder / "capture").exists())

    def test_tail_prefix_and_absent_final_duration_are_explicit(self):
        spec = self.spec()
        spec["expected_interval"] = {"start_ns": 0, "end_ns": 1_400_000_000}
        result = self.extract(spec)
        self.assertEqual("INCOMPLETE", result["video"]["coverage"]["status"])
        self.assertEqual(1_000_000_000, result["video"]["coverage"]["prefix_ns"])
        self.assertEqual(160_000_000, result["video"]["coverage"]["tail_ns"])
        self.assertEqual("UNMEASURED", capture.coverage({"start_ns": 0, "end_ns": 1000}, Fraction(0), None)["status"])
        self.assertEqual("UNMEASURED", capture.coverage(None, Fraction(0), Fraction(1))["status"])

    def test_missing_timestamp_and_display_rotation_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "best_effort_timestamp"):
            capture.timestamp({}, Fraction(1, 25))
        with self.assertRaisesRegex(ValueError, "Rotated"):
            capture.rotations({"side_data_list": [{"rotation": 90}]})
        with self.assertRaisesRegex(ValueError, "Rotated"):
            capture.rotations({"tags": {"rotate": "invalid"}})
        capture.rotations({"tags": {"rotate": "0"}})

    def test_subnanosecond_remainder_and_negative_original_pts_are_preserved(self):
        rounded, remainder = capture.to_ns(Fraction(-1, 3))
        self.assertEqual(-333_333_334, rounded)
        self.assertEqual({"numerator": 2, "denominator": 3}, remainder)
        self.assertEqual(Fraction(-1, 3), capture.timestamp({"best_effort_timestamp": -1}, Fraction(1, 3)))
        value = capture.coverage({"start_ns": 0, "end_ns": 1}, Fraction(1, 3_000_000_000), Fraction(1, 1_000_000_000))
        self.assertEqual("INCOMPLETE", value["status"])
        self.assertEqual(0, value["prefix_ns"])
        self.assertEqual({"numerator": 1, "denominator": 3}, value["prefix_exact_ns"])

    def test_sample_color_switch_and_backward_audio_clock_are_rejected(self):
        original = capture.command

        def altered_probe(args, commands):
            result = original(args, commands)
            if "-show_frames" in args:
                document = json.loads(result)
                if args[args.index("-select_streams") + 1] == "0":
                    document["frames"][-1]["color_range"] = "tv"
                return json.dumps(document)
            return result

        with patch.object(capture, "command", side_effect=altered_probe), self.assertRaisesRegex(ValueError, "color declarations"):
            self.extract()

        def backward_audio(args, commands):
            result = original(args, commands)
            if "-show_frames" in args and args[args.index("-select_streams") + 1] == "1":
                document = json.loads(result)
                document["frames"][1]["best_effort_timestamp"] = document["frames"][0]["best_effort_timestamp"]
                return json.dumps(document)
            return result

        with patch.object(capture, "command", side_effect=backward_audio), self.assertRaisesRegex(ValueError, "strictly increase"):
            self.extract()

    def test_fresh_output_required_and_cli_cannot_replace_input(self):
        output = self.folder / "existing"
        output.mkdir()
        sentinel = output / "user-data.txt"
        sentinel.write_text("preserve", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "fresh"):
            capture.extract(self.spec(), self.folder, output)
        self.assertEqual("preserve", sentinel.read_text())
        path = self.folder / "spec.json"
        path.write_text(json.dumps(self.spec()), encoding="utf-8")
        with self.assertRaises(SystemExit):
            capture.main(["--spec", str(path), "--output", str(self.folder)])
        self.assertEqual(0, capture.main(["--spec", str(path), "--output", str(self.folder / "cli-result")]))
        result = json.loads((self.folder / "cli-result/capture.json").read_text())
        self.assertEqual(capture.digest(path), result["spec"]["sha256"])

    def test_gap_in_audio_decoded_pts_is_preserved_and_disables_sample_clock(self):
        original = capture.command

        def delayed_probe(args, commands):
            result = original(args, commands)
            if "-show_frames" in args and "-select_streams" in args and args[args.index("-select_streams") + 1] == "1":
                document = json.loads(result)
                self.assertGreater(len(document["frames"]), 1)
                for row in document["frames"][1:]:
                    row["best_effort_timestamp"] += 480
                return json.dumps(document)
            return result

        with patch.object(capture, "command", side_effect=delayed_probe):
            result = self.extract()
        self.assertEqual("SPARSE", result["audio"]["timeline"]["continuity"])
        self.assertEqual("UNMEASURED", result["scope"]["audio_timing_status"])
        self.assertTrue(result["audio"]["timeline"]["gaps"])
        self.assertEqual(9600, result["audio"]["sample_count"])
        events = self.folder / "audio-events.json"
        events.write_text(json.dumps({"schema": "vsg-events-v1", "audio_schedule": {
            "schema": "vsg-audio-schedule-v1", "enabled": True, "sample_rate": 48000,
            "channels": 1, "sample_count": 9600, "events": []}}), encoding="utf-8")
        scored = audio_sync.analyze({"schema": "benchmark-audio-observation-v1",
            "reference": {"wav": result["audio"], "events": {"path": str(events), "sha256": capture.digest(events)}},
            "capture": {"wav": result["audio"], "clock": result["audio"]["clock"], "timeline": result["audio"]["timeline"]},
            "plans": [{"stream_generation": "1", "loop_iteration": 0, "source_start_sample": 0,
                       "source_end_sample": 9600, "capture_start_sample": 0, "search_radius_samples": 0}]}, self.folder)
        self.assertEqual(9600, scored["metrics"]["capture_pcm"]["sample_count"])
        self.assertEqual("UNMEASURED", scored["scope"]["capture_sample_clock_status"])

    def test_failed_extraction_keeps_interrupted_receipt_without_overwriting_original(self):
        original = capture.command

        def fail_video(args, commands):
            if "rawvideo" in args:
                raise ValueError("injected extraction interruption")
            return original(args, commands)

        before = capture.digest(self.input)
        with patch.object(capture, "command", side_effect=fail_video), self.assertRaisesRegex(ValueError, "interruption"):
            self.extract()
        result = json.loads((self.folder / "capture/capture.json").read_text())
        self.assertEqual("INTERRUPTED", result["measurement_status"])
        self.assertEqual(before, capture.digest(self.input))


if __name__ == "__main__":
    unittest.main()
