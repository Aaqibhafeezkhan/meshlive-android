import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("encoded_score", ROOT / "tools/score_encoded_file.py")
encoded_score = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(encoded_score)
POWERSHELL = shutil.which("pwsh") or shutil.which("powershell")


class EncodedScoreOutputTest(unittest.TestCase):
    def test_file_diagnostics_cannot_replace_original_scores(self):
        import sys
        import score_device_run
        cases = [(score_device_run, ["--manifest", "missing.json"])]
        for name, arguments in (("analyze_stream_log", ["missing.log"]),
                                ("calculate_bd_rate", ["--anchor-label", "a", "--anchor", "missing-a.json",
                                                       "--test-label", "b", "--test", "missing-b.json"])):
            spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / (name + ".py"))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            cases.append((module, arguments))
        with tempfile.TemporaryDirectory(prefix="retained-score-") as directory:
            output = Path(directory) / "original-score.json"
            output.write_bytes(b"original measured score")
            for module, arguments in cases:
                with self.subTest(module=module.__name__), patch.object(sys, "argv", [module.__name__, *arguments, "--output", str(output)]):
                    with self.assertRaises(FileExistsError):
                        module.main()
                self.assertEqual(b"original measured score", output.read_bytes())

    def test_cli_rejects_legacy_directory_before_reading_or_scoring_sources(self):
        with patch.object(encoded_score, "run") as process, patch.object(encoded_score, "resolve") as inputs:
            with self.assertRaisesRegex(ValueError, "benchmarks/runs"):
                encoded_score.main(["missing.ivf", "missing.yuv", "--output-directory",
                                    str(ROOT / "benchmarks/android_offline/forbidden-new-score")])
        process.assert_not_called()
        inputs.assert_not_called()

    def test_existing_result_directory_survives_without_source_or_process_access(self):
        with tempfile.TemporaryDirectory(prefix="codec-score-") as directory:
            output = Path(directory) / "scores"
            output.mkdir()
            result = output / "input-result.json"
            result.write_bytes(b"original result")
            with patch.object(encoded_score, "run") as process:
                with self.assertRaises(FileExistsError):
                    encoded_score.main(["missing.ivf", "missing.yuv", "--output-directory", str(output)])
            process.assert_not_called()
            self.assertEqual(b"original result", result.read_bytes())

    def test_partial_short_reference_and_wrong_encoded_geometry_cannot_be_scored(self):
        with tempfile.TemporaryDirectory(prefix="codec-input-") as directory:
            reference = Path(directory) / "reference.yuv"
            output = Path(directory) / "scores"
            for byte_count, geometry in ((25, (4, 4)), (24, (4, 4)), (48, (8, 4))):
                with self.subTest(byte_count=byte_count, geometry=geometry):
                    reference.write_bytes(bytes(byte_count))
                    probe = {"streams": [{"nb_read_frames": "2", "width": geometry[0], "height": geometry[1]}]}
                    with patch.object(encoded_score, "run", return_value=Mock(stdout=json.dumps(probe))) as process:
                        with self.assertRaises(ValueError):
                            encoded_score.main(["missing.ivf", str(reference), "--width", "4", "--height", "4",
                                                "--output-directory", str(output)])
                    self.assertEqual(1, process.call_count)
                    self.assertFalse(output.exists())

    def test_perfect_psnr_is_json_safe_and_missing_metric_frames_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="codec-metric-") as directory:
            output = Path(directory)
            log = output / "vmaf.json"
            log.write_text(json.dumps({"frames": [{}], "pooled_metrics": {"vmaf": {"mean": 100}}}))
            process = Mock(side_effect=[Mock(), Mock(stderr="All:1.0"), Mock(stderr="average:inf min:inf")])
            with patch.object(encoded_score, "run", process):
                result = encoded_score.score_range(Path("video.ivf"), Path("raw.yuv"), [], "", output, log.name, 0, 1)
            self.assertEqual("inf", result["psnr_db"])
            json.dumps(result, allow_nan=False)
            with patch.object(encoded_score, "run", return_value=Mock()):
                with self.assertRaisesRegex(ValueError, "every requested"):
                    encoded_score.score_range(Path("video.ivf"), Path("raw.yuv"), [], "", output, log.name, 0, 2)


@unittest.skipUnless(POWERSHELL, "PowerShell is required for offline helper execution")
class ReceiverHelperOutputTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="receiver-helpers-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)

    def invoke(self, helper, arguments, mock):
        wrapper = self.folder / "offline-wrapper.ps1"
        wrapper.write_text("""
$ErrorActionPreference = 'Stop'
$global:mockCalls = 0
function global:adb {
    $global:mockCalls++
""" + mock + """
}
try {
    $parameters = @{}
    (ConvertFrom-Json $env:OFFLINE_HELPER_ARGS).PSObject.Properties |
        ForEach-Object { $parameters[$_.Name] = $_.Value }
    & $env:OFFLINE_HELPER_PATH @parameters | Out-Null
    $message = ''
} catch { $message = $_.Exception.Message }
@{error=$message; calls=$global:mockCalls} | ConvertTo-Json -Compress
""", encoding="utf-8")
        environment = dict(os.environ, OFFLINE_HELPER_PATH=str(ROOT / "tools" / helper),
                           OFFLINE_HELPER_ARGS=json.dumps(arguments))
        process = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-File", str(wrapper)],
                                 capture_output=True, text=True, check=True, env=environment, timeout=30)
        return json.loads(process.stdout.strip().splitlines()[-1])

    def test_existing_outputs_refused_before_any_device_command(self):
        output = self.folder / "existing"
        output.mkdir()
        sentinel = output / "evidence.csv"
        sentinel.write_bytes(b"original measurement")
        cases = [
            ("run_android_encoder_case.ps1", {"DeviceSerial": "mock-only", "CaseName": "case",
                                             "ReferencePath": "missing.yuv", "OutputDirectory": str(output)}),
            ("run_motion_quality_matrix.ps1", {"DeviceSerial": "mock-only", "SourcePath": "missing.yuv",
                                              "OutputDirectory": str(output)}),
            ("pull_media_trace.ps1", {"SenderSerial": "mock-a", "ReceiverSerial": "mock-b",
                                      "SessionName": "case", "OutputDirectory": str(output)}),
            ("pull_session_metrics.ps1", {"Serial": "mock-only", "OutputDirectory": str(output)}),
            ("collect_connected_cpu.ps1", {"Serial": "mock-only", "OutputPath": str(sentinel)}),
        ]
        for helper, arguments in cases:
            with self.subTest(helper=helper):
                result = self.invoke(helper, arguments, "throw 'Device commands are forbidden in this test'")
                self.assertEqual(0, result["calls"])
                self.assertIn("already exists", result["error"])
                self.assertEqual(b"original measurement", sentinel.read_bytes())

    def test_legacy_output_cannot_start_a_device_collection(self):
        destination = str(ROOT / "artifacts/forbidden-new-output")
        cases = [
            ("run_android_encoder_case.ps1", {"DeviceSerial": "mock-only", "CaseName": "case", "ReferencePath": "missing.yuv", "OutputDirectory": destination}),
            ("run_motion_quality_matrix.ps1", {"DeviceSerial": "mock-only", "SourcePath": "missing.yuv", "OutputDirectory": destination}),
            ("pull_media_trace.ps1", {"SenderSerial": "mock-a", "ReceiverSerial": "mock-b", "SessionName": "case", "OutputDirectory": destination}),
            ("pull_session_metrics.ps1", {"Serial": "mock-only", "OutputDirectory": destination}),
            ("collect_connected_cpu.ps1", {"Serial": "mock-only", "OutputPath": destination + ".csv"}),
        ]
        for helper, arguments in cases:
            with self.subTest(helper=helper):
                result = self.invoke(helper, arguments, "throw 'Device commands are forbidden'")
                self.assertEqual(0, result["calls"])
                self.assertIn("benchmarks/runs", result["error"])
        self.assertFalse((ROOT / "artifacts/forbidden-new-output").exists())

    def test_failed_pull_cannot_succeed_because_partial_file_exists(self):
        output = self.folder / "trace"
        result = self.invoke("pull_media_trace.ps1", {
            "SenderSerial": "mock-a", "ReceiverSerial": "mock-b", "SessionName": "case",
            "OutputDirectory": str(output)}, """
if ($args -contains 'get-state') { $global:LASTEXITCODE=0; 'device' }
else { Set-Content -LiteralPath $args[-1] -Value 'partial pull'; $global:LASTEXITCODE=7 }
""")
        self.assertIn("Could not pull", result["error"])
        self.assertEqual(3, result["calls"])
        self.assertTrue((output / "case-sender.csv").exists())
        self.assertFalse((output / "case-integrity.json").exists())

    def test_failed_cpu_sample_is_not_written_as_zero_and_success_uses_csv_decimal_point(self):
        for failed in (True, False):
            with self.subTest(failed=failed):
                output = self.folder / f"cpu-{failed}.csv"
                mock = """
if ($args -contains 'get-state') { $global:LASTEXITCODE=0; 'device' }
else {
    [Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('de-DE')
    $global:LASTEXITCODE = """ + ("7" if failed else "0") + """
    ' 123 12.5 io.github.meshlive.app'
}
"""
                result = self.invoke("collect_connected_cpu.ps1", {
                    "Serial": "mock-only", "DurationSeconds": 1, "IntervalSeconds": 1,
                    "OutputPath": str(output)}, mock)
                rows = output.read_text(encoding="utf-8-sig").splitlines()
                if failed:
                    self.assertIn("Could not collect", result["error"])
                    self.assertEqual(1, len(rows))
                else:
                    self.assertEqual("", result["error"])
                    self.assertEqual(2, len(rows))
                    self.assertEqual("12.50", rows[1].split(",")[1])
                    self.assertEqual(5, len(rows[1].split(",")))


if __name__ == "__main__":
    unittest.main()
