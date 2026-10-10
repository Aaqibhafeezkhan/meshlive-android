from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_release
import parse_user_diagnostics as pd

CERT = make_release.EXPECTED_CERT_SHA256

BASE = [
    "app.version: 1.0",
    "app.build: 1",
    "app.build_type: release",
    f"app.signing_sha256: {CERT}",
    "device.manufacturer: samsung",
    "device.model: SM-S901B",
    "device.soc: Qualcomm SM8450",
    "device.android: 14",
    "device.sdk: 34",
    "device.security_patch: 2025-09-01",
    "bluetooth.le_2m: true",
    "bluetooth.le_coded: false",
    "bluetooth.le_extended_advertising: true",
    "bluetooth.le_coc: true",
    "identity.short_hash: 0123456789ab",
    "device_policy: matched=le-coc-eatt-stranded-channels | registry=4",
]
CONN_BAD = ("conn: t=2026-10-10T10:00:00Z | transport=coc | role=central | peer=abcdef012345 | status=42 | "
            "phy_req=2M | phy_obs=1M | mtu_req=517 | mtu_obs=247 | interval_req_ms=15 | interval_obs_ms=45 | "
            "outcome=failed | reason=phy_unavailable")


def block(lines):
    return "\n".join(["noise before", pd.HEADER, *lines, f"=== END (lines={len(lines)}) ===", "noise after"])


class ParseTest(unittest.TestCase):
    def test_valid_block_and_suggestion(self):
        lines = BASE + [CONN_BAD] * 4 + [
            "adaptive_phy: t=2026-10-10T10:00:01Z | link=coc | from=2M | to=1M | reason=early_loss | kbps=120 | blocked=0.40",
            "codec: t=2026-10-10T10:00:02Z | role=tx | mime=video/x-vnd.on2.vp9 | component=c2.qti.vp9.encoder | "
            "hw=true | req=640x480@15/350kbps | eff=640x480@15/350kbps"]
        out = pd.suggest(pd.parse_diagnostics(block(lines), {"le-coc-eatt-stranded-channels"}))
        self.assertTrue(out["official_build"])
        self.assertEqual(out["family_key"], "samsung/sm-s901b/sdk34")
        self.assertEqual(out["observations"]["failure_status_histogram"], {"42": 4})
        self.assertEqual(out["observations"]["phy_requested_vs_observed_mismatch"], "4/4")
        self.assertEqual(out["observations"]["adaptive_phy_reasons"], {"early_loss": 1})
        actions = [(s["action"], s.get("entry_id")) for s in out["registry_suggestions"]]
        self.assertIn(("append-evidence", "le-coc-eatt-stranded-channels"), actions)
        self.assertEqual(sum(1 for a, _ in actions if a == "candidate-new-entry"), 2)
        self.assertEqual(out["warnings"], [])

    def test_unofficial_fingerprint_flagged(self):
        lines = [l.replace(CERT, "00:" + CERT[3:]) for l in BASE]
        out = pd.suggest(pd.parse_diagnostics(block(lines)))
        self.assertFalse(out["official_build"])
        self.assertTrue(any("not an official" in w for w in out["warnings"]))

    def test_unknown_policy_id_warns(self):
        out = pd.parse_diagnostics(block(BASE), {"something-else"})
        self.assertTrue(out["warnings"])

    def test_truncated_or_edited_rejected(self):
        text = block(BASE).replace(f"lines={len(BASE)}", f"lines={len(BASE) + 1}")
        with self.assertRaisesRegex(pd.DiagnosticsError, "truncated"):
            pd.parse_diagnostics(text)
        with self.assertRaisesRegex(pd.DiagnosticsError, "header"):
            pd.parse_diagnostics("nothing here")
        with self.assertRaisesRegex(pd.DiagnosticsError, "footer"):
            pd.parse_diagnostics(pd.HEADER + "\n" + "\n".join(BASE))

    def test_privacy_allowlist(self):
        for bad in ["contact.name: Alice", "message: hello", "identity.public_key: " + "ab" * 32]:
            with self.assertRaisesRegex(pd.DiagnosticsError, "unexpected field"):
                pd.parse_diagnostics(block(BASE + [bad]))
        with self.assertRaisesRegex(pd.DiagnosticsError, "MAC"):
            pd.parse_diagnostics(block(BASE + [CONN_BAD.replace("reason=phy_unavailable", "reason=AA:BB:CC:DD:EE:FF")]))
        with self.assertRaisesRegex(pd.DiagnosticsError, "long hex"):
            pd.parse_diagnostics(block(BASE + [CONN_BAD.replace("reason=phy_unavailable", "reason=" + "a1" * 16)]))
        with self.assertRaisesRegex(pd.DiagnosticsError, "12-hex"):
            pd.parse_diagnostics(block(BASE + [CONN_BAD.replace("abcdef012345", "Alice's Pixel")]))
        with self.assertRaisesRegex(pd.DiagnosticsError, "12 lowercase hex"):
            pd.parse_diagnostics(block([l.replace("0123456789ab", "a" * 16) for l in BASE]))

    def test_record_field_allowlist_and_limits(self):
        with self.assertRaisesRegex(pd.DiagnosticsError, "unexpected field"):
            pd.parse_diagnostics(block(BASE + [CONN_BAD + " | name=Alice"]))
        with self.assertRaisesRegex(pd.DiagnosticsError, "more than 50"):
            pd.parse_diagnostics(block(BASE + [CONN_BAD] * 51))

    def test_missing_required_field(self):
        with self.assertRaisesRegex(pd.DiagnosticsError, "missing required"):
            pd.parse_diagnostics(block([l for l in BASE if not l.startswith("device.soc")]))

    def test_registry_ids_read_from_real_policy_file(self):
        ids = pd.registry_ids(Path(__file__).resolve().parents[2]
                              / "app/src/main/java/com/example/bluetoothtest")
        self.assertIn("le-coc-server-leak", ids)


if __name__ == "__main__":
    unittest.main()
