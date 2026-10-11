"""The merged manifests of the two distribution flavours (needs `gradlew :app:processGithubReleaseManifest
:app:processFdroidReleaseManifest` first; skipped when the merge output is absent)."""
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MERGED = ROOT / "app/build/intermediates/merged_manifest"
PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"


REQUIRED = bool(os.environ.get("MESHLIVE_REQUIRE_FLAVOUR_MANIFESTS"))


def merged(variant):
    found = sorted((MERGED / variant).rglob("AndroidManifest.xml")) if (MERGED / variant).is_dir() else []
    return found[0].read_text(encoding="utf-8") if found else None


class FlavourManifestTests(unittest.TestCase):
    def missing(self, variant):
        """Skip, or fail when MESHLIVE_REQUIRE_FLAVOUR_MANIFESTS=1 (the release checklist sets it)."""
        message = f"no merged manifest for {variant}: run the process<Variant>Manifest tasks first"
        if REQUIRED:
            self.fail(message)
        self.skipTest(message)

    def test_github_release_has_the_updater_and_the_install_permission(self):
        text = merged("githubRelease")
        if text is None:
            self.missing("githubRelease")
        self.assertIn(PERMISSION, text)
        for component in ("UpdateActivity", "UpdateInstallReceiver", "UpdateJobService"):
            self.assertIn(component, text)
        self.assertNotIn('usesCleartextTraffic="true"', text)

    def test_fdroid_release_has_neither(self):
        text = merged("fdroidRelease")
        if text is None:
            self.missing("fdroidRelease")
        self.assertNotIn(PERMISSION, text)
        for component in ("UpdateActivity", "UpdateInstallReceiver", "UpdateJobService"):
            self.assertNotIn(component, text)
        self.assertNotIn('usesCleartextTraffic="true"', text)

    def test_only_debug_builds_allow_cleartext(self):
        debug = merged("githubDebug")
        if debug is None:
            self.missing("githubDebug")
        self.assertIn('usesCleartextTraffic="true"', debug)


if __name__ == "__main__":
    unittest.main()
