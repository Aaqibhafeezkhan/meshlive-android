import re
"""The merged manifests of the three distribution flavours (needs `gradlew :app:processGithubReleaseManifest
:app:processFdroidReleaseManifest :app:processPlayReleaseManifest` first; skipped when the merge output is absent)."""
import os
import unittest
import xml.etree.ElementTree as ET
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

    def test_play_release_keeps_sharing_without_updater_or_location_service_type(self):
        text = merged("playRelease")
        if text is None:
            self.missing("playRelease")
        # Comments may name removed components; only the declarations count.
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        for absent in ("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", PERMISSION, "UpdateActivity", "UpdateInstallReceiver", "UpdateJobService",
                       "android.permission.FOREGROUND_SERVICE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
                       "android.permission.QUERY_ALL_PACKAGES", "android.permission.SCHEDULE_EXACT_ALARM",
                       "android.permission.USE_EXACT_ALARM"):
            self.assertNotIn(absent, text)
        root = ET.fromstring(text)
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = {p.get(android + "name"): p for p in root.findall("uses-permission")}
        self.assertEqual("neverForLocation", permissions["android.permission.NEARBY_WIFI_DEVICES"].get(android + "usesPermissionFlags"))
        app = root.find("application")
        activities = {p.get(android + "name").rsplit(".", 1)[-1]: p for p in app.findall("activity")}
        self.assertEqual("false", activities["OfflineAppShareActivity"].get(android + "exported"))
        providers = {p.get(android + "name").rsplit(".", 1)[-1]: p for p in app.findall("provider")}
        provider = providers["AppShareFileProvider"]
        self.assertEqual("io.github.meshlive.app.appshare", provider.get(android + "authorities"))
        self.assertEqual("false", provider.get(android + "exported"))
        self.assertEqual("true", provider.get(android + "grantUriPermissions"))
        paths = provider.find("meta-data")
        self.assertEqual("@xml/app_share_paths", paths.get(android + "resource"))
        self.assertIn('android:foregroundServiceType="camera|microphone|connectedDevice|mediaPlayback"', text)
        self.assertIn('package="io.github.meshlive.app"', text)
        self.assertNotIn('usesCleartextTraffic="true"', text)

    def test_only_debug_builds_allow_cleartext(self):
        debug = merged("githubDebug")
        if debug is None:
            self.missing("githubDebug")
        self.assertIn('usesCleartextTraffic="true"', debug)


if __name__ == "__main__":
    unittest.main()
