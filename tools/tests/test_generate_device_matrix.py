import importlib.util
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

spec = importlib.util.spec_from_file_location("matrix", Path(__file__).parents[1] / "generate_device_matrix.py")
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


class CompatibleMatrixTest(unittest.TestCase):
    def test_receiver_buffers_empty_modes_and_duplicate_exports(self):
        sender, receiver = self.capabilities(), self.capabilities()
        receiver["playback"] = ["low"]
        sender["encoders"][0]["modes"] *= 2
        sender["encoders"][0]["modes"].append({"width": 320, "height": 240, "fps": [], "bitrate_kbps": 100})
        cases = matrix.generate_cases("a", "b", sender, receiver)
        self.assertEqual({"low"}, {c["profile"]["playback"] for c in cases})
        self.assertEqual(len(cases), len({c["id"] for c in cases}))

    def test_duration_changes_identity_but_provenance_does_not(self):
        caps = self.capabilities()
        before = matrix.generate_cases("a", "b", caps, caps, 90)
        after = matrix.generate_cases("a", "b", caps, caps, 120)
        self.assertFalse({c["id"] for c in before} & {c["id"] for c in after})
        refreshed = copy.deepcopy(caps)
        refreshed["fingerprint"] = "new-export-fingerprint"
        self.assertEqual([c["id"] for c in before],
                         [c["id"] for c in matrix.generate_cases("a", "b", refreshed, refreshed, 90)])

    def test_cli_retains_capability_hashes_and_refuses_existing_output(self):
        with tempfile.TemporaryDirectory(prefix="device-matrix-") as directory:
            base = Path(directory)
            source = base / "sender.json"
            receiver = base / "receiver.json"
            for path in (source, receiver):
                path.write_text(json.dumps(self.capabilities()), encoding="utf-8")
            output = base / "matrix.json"
            argv = ["--source", "a", "--receiver", "b", "--sender-capabilities", str(source),
                    "--receiver-capabilities", str(receiver), "--output", str(output)]
            with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")), contextlib.redirect_stdout(io.StringIO()):
                matrix.main(argv)
            result = json.loads(output.read_text())
            for phone, path in (("a", source), ("b", receiver)):
                basis = result["cases"][0]["capability_basis"][phone]
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), basis["sha256"])
                self.assertEqual(str(path.resolve()), basis["path"])
            before = output.read_bytes()
            with self.assertRaises(FileExistsError):
                matrix.main(argv)
            self.assertEqual(before, output.read_bytes())

    def capabilities(self):
        return {"fingerprint": "real-device", "camera_lens": "back", "decoders": ["vp9"],
                "transport_max_kbps": {"gatt": 1100, "ble": 1200, "classic": 1500},
                "playback": ["low", "balanced", "stable"],
                "encoders": [{"name": "real.vp9.encoder", "codec": "vp9", "rate_controls": ["automatic", "cbr"],
                              "modes": [{"width": 640, "height": 480, "fps": [15, 30], "bitrate_kbps": 1500},
                                        {"width": 640, "height": 480, "fps": [15, 30], "bitrate_kbps": 1000}]}]}

    def test_full_cross_product_never_requests_hidden_or_clamped_options(self):
        caps = self.capabilities()
        cases = matrix.generate_cases("a", "b", caps, caps)
        self.assertEqual(len(cases), 96)
        self.assertEqual(len({case["id"] for case in cases}), len(cases))
        for case in cases:
            profile = case["profile"]
            self.assertLessEqual(profile["bitrate_kbps"], caps["transport_max_kbps"][case["transport"]])
            self.assertIn(profile["rate_control"], {"automatic", "cbr"})
            self.assertEqual(profile["codec"], "real.vp9.encoder")
        self.assertEqual({case["profile"]["playback"] for case in cases}, {"low", "balanced", "stable"})
        self.assertEqual({case["profile"]["thermal_reduction"] for case in cases}, {False, True})

    def test_decoder_incompatibility_does_not_silently_switch_codec(self):
        sender, receiver = self.capabilities(), self.capabilities()
        receiver["decoders"] = ["av1"]
        self.assertEqual(matrix.generate_cases("a", "b", sender, receiver), [])

    def test_one_phone_cannot_be_both_roles(self):
        with self.assertRaises(ValueError):
            matrix.generate_cases("a", "a", self.capabilities(), self.capabilities())


if __name__ == "__main__":
    unittest.main()
