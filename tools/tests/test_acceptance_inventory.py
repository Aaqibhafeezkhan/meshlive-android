"""The acceptance profile is part of the reviewed scope, never of an exact request identity."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_benchmark_inventory as inventory
from run_benchmark_suite import load_jobs

PHONES = ["s24", "s22"]
TRANS = ["classic", "ble", "gatt"]


def capability(alias):
    return {"schema_version": 1, "fingerprint": "fingerprint-" + alias, "node_prefix": f"{PHONES.index(alias) + 1:012x}",
            "decoders": ["vp9"], "playback": ["low", "balanced", "stable"], "transport_max_kbps": dict.fromkeys(TRANS, 1000),
            "encoders": [{"name": "encoder-" + alias, "codec": "vp9", "rate_controls": ["automatic", "cbr"],
                          "modes": [{"width": 640, "height": 360, "bitrate_kbps": 100, "fps": [15]},
                                    {"width": 720, "height": 480, "bitrate_kbps": 900, "fps": [30]}]}]}


class AcceptanceInventoryTest(unittest.TestCase):
    def compile(self, **scope):
        return inventory.compile_inventory({"required_phones": PHONES, "transports": TRANS, "duration_s": 90,
                                            "settings_sweep": {"duration_s": 45, "pairs": [["s24", "s22"]]}, **scope},
                                           {p: capability(p) for p in PHONES})

    def test_profile_never_changes_request_identity(self):
        v3, v3_inventory, _ = self.compile()
        v4, v4_inventory, _ = self.compile(acceptance_profile="device-run-v4-user")
        self.assertEqual({c["id"] for v in v3.values() for c in v}, {c["id"] for v in v4.values() for c in v})
        self.assertEqual(v3, v4)
        self.assertNotIn("acceptance_profile", v3_inventory)
        self.assertEqual("device-run-v4-user", v4_inventory["acceptance_profile"])
        self.assertNotIn("picture", v4_inventory["settings_sweep"]["required_families"])
        self.assertIn("picture", v3_inventory["settings_sweep"]["required_families"])

    def test_explicit_v3_is_identical_to_no_profile(self):
        self.assertEqual(self.compile(), self.compile(acceptance_profile="device-run-v3"))

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            self.compile(acceptance_profile="device-run-v9")

    def test_cli_bundle_carries_profile_and_user_families_for_full_and_sweep_groups(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            scope = {"required_phones": PHONES, "registry": str(folder / "registry.json"), "capabilities": {},
                     "acceptance_profile": "device-run-v4-user", "settings_sweep": {"duration_s": 45, "pairs": [["s24", "s22"]]}}
            registry = {p: {"serial": "offline-" + p, "node_prefix": capability(p)["node_prefix"]} for p in PHONES}
            for p in PHONES:
                (folder / (p + ".json")).write_text(json.dumps(capability(p)), encoding="utf-8")
                scope["capabilities"][p] = str(folder / (p + ".json"))
            (folder / "registry.json").write_text(json.dumps({"devices": registry}), encoding="utf-8")
            (folder / "scope.json").write_text(json.dumps(scope), encoding="utf-8")
            with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")), contextlib.redirect_stdout(io.StringIO()):
                inventory.main(["--scope", str(folder / "scope.json"), "--output", str(folder / "out")])
            jobs = load_jobs(folder / "out/benchmark-bundle.json", registry)
            sweep = [j for j in jobs if j["case"]["duration_s"] == 45]
            full = [j for j in jobs if j["case"]["duration_s"] != 45]
            self.assertTrue(sweep and full)
            self.assertTrue(all(j["acceptance_profile"] == "device-run-v4-user" for j in jobs))
            self.assertTrue(all(j["required_families"] == ["smoothness", "connection", "transport"] for j in sweep))
            self.assertTrue(all("media_age" in j["required_families"] and "task_success" in j["required_families"] and
                                "speech_comprehension" in j["required_families"] for j in full))

    def test_default_scope_emits_no_profile_keys_so_v3_bytes_are_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            scope = {"required_phones": PHONES, "registry": str(folder / "registry.json"), "capabilities": {}}
            registry = {p: {"serial": "offline-" + p, "node_prefix": capability(p)["node_prefix"]} for p in PHONES}
            for p in PHONES:
                (folder / (p + ".json")).write_text(json.dumps(capability(p)), encoding="utf-8")
                scope["capabilities"][p] = str(folder / (p + ".json"))
            (folder / "registry.json").write_text(json.dumps({"devices": registry}), encoding="utf-8")
            (folder / "scope.json").write_text(json.dumps(scope), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                inventory.main(["--scope", str(folder / "scope.json"), "--output", str(folder / "out")])
            self.assertNotIn("acceptance_profile", (folder / "out/benchmark-bundle.json").read_text())
            self.assertNotIn("acceptance_profile", (folder / "out/inventory.json").read_text())
            self.assertTrue(all("acceptance_profile" not in j for j in load_jobs(folder / "out/benchmark-bundle.json", registry)))


if __name__ == "__main__":
    unittest.main()
