import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


codec = load("prepare_codec_stress_fixture")
legacy = load("prepare_standard_stress_fixture")


class FixtureOutputTest(unittest.TestCase):
    def test_fixture_references_keep_project_and_document_relative_meanings_after_move(self):
        import benchmark_layout
        project = self.folder
        documents = project / "benchmarks/inputs/metadata"
        samples = project / "benchmarks/inputs/samples"
        documents.mkdir(parents=True)
        samples.mkdir(parents=True)
        fixture = documents / "fixture.json"
        fixture.write_text("{}")
        native = samples / "native.yuv"
        native.write_bytes(bytes(24))
        aliases = {"benchmarks/old/fixture.json": fixture.relative_to(project).as_posix(),
                   "benchmarks/old/native.yuv": native.relative_to(project).as_posix()}
        catalog = project / "benchmarks/catalog"
        catalog.mkdir()
        (catalog / "ledger.json").write_text(__import__("json").dumps({"storage": {"paths": aliases}}))
        with patch.object(codec, "resolve", side_effect=lambda path, base=None: benchmark_layout.resolve(path, base, project)), \
                patch.object(codec, "original", side_effect=lambda path: benchmark_layout.original(path, project)), \
                patch.object(codec, "__file__", str(project / "tools/prepare_codec_stress_fixture.py")):
            for pointer in ("native.yuv", "benchmarks/old/native.yuv", str(project / "benchmarks/old/native.yuv")):
                self.assertEqual(native, codec.fixture_input(fixture, pointer))

    def test_cli_rejects_legacy_output_before_media_processes(self):
        for module, extra in ((codec, ["--clip", "ReadySetGo"]), (legacy, [])):
            with self.subTest(module=module.__name__):
                argv = [module.__name__, "--source", "missing.yuv", "--output-directory",
                        str(TOOLS.parent / "benchmarks/motion_quality/forbidden-new-input"), *extra]
                with patch.object(sys, "argv", argv), patch.object(module.subprocess, "run") as process:
                    with self.assertRaisesRegex(ValueError, "benchmarks/inputs"):
                        module.main()
                process.assert_not_called()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="fixture-output-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name).resolve()

    def test_cli_preparation_refuses_existing_output_before_media_processes(self):
        for module, extra in ((codec, ["--clip", "ReadySetGo"]), (legacy, [])):
            with self.subTest(module=module.__name__):
                output = self.folder / module.__name__
                output.mkdir()
                evidence = output / "fixture.json"
                evidence.write_bytes(b"original measured reference")
                argv = [module.__name__, "--source", str(self.folder / "missing-source"),
                        "--output-directory", str(output), *extra]
                with patch.object(sys, "argv", argv), patch.object(module.subprocess, "run") as process:
                    with self.assertRaises(FileExistsError):
                        module.main()
                process.assert_not_called()
                self.assertEqual(b"original measured reference", evidence.read_bytes())

    def test_montage_refuses_existing_output_before_reading_sources(self):
        output = self.folder / "natural_variable"
        output.mkdir()
        evidence = output / "fixture.json"
        evidence.write_bytes(b"original montage")
        with self.assertRaises(FileExistsError):
            codec.prepare_variable_montage(self.folder)
        self.assertEqual(b"original montage", evidence.read_bytes())
        self.assertEqual([evidence], list(output.iterdir()))

    def test_burst_lab_refuses_existing_output_before_reading_sources(self):
        output = self.folder / "bursts"
        output.mkdir()
        evidence = output / "cbr-1100k.ivf"
        evidence.write_bytes(b"original measured bitstream")
        with patch.object(codec.subprocess, "run") as process:
            with self.assertRaises(FileExistsError):
                codec.measure_bursts(self.folder / "missing-fixture.json", output)
        process.assert_not_called()
        self.assertEqual(b"original measured bitstream", evidence.read_bytes())

    def test_luma_measurement_rejects_partial_and_empty_native_files(self):
        for data in (b"", bytes(25)):
            with self.subTest(bytes=len(data)):
                source = self.folder / "partial.yuv"
                source.write_bytes(data)
                output = self.folder / "samples.csv"
                with self.assertRaisesRegex(ValueError, "whole.*I420"):
                    codec.measure_luma(source, 4, 4, 30, output)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
