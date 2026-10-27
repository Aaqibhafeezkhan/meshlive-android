"""The F-Droid recipe draft must stay in step with the build files and the pinned native sources."""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import fetch_native_sources as fns

RECIPE = ROOT / "distribution/fdroid/io.github.meshlive.app.yml"


class FdroidRecipeTests(unittest.TestCase):
    def setUp(self):
        self.text = RECIPE.read_text(encoding="utf-8")
        self.gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")

    def entries(self):
        body = self.text.split("\nBuilds:\n", 1)[1].split("\n# Reproducible-build", 1)[0]
        return [chunk for chunk in re.split(r"\n  - versionName", "\n" + body) if chunk.strip()]

    def test_file_name_is_the_application_id(self):
        app_id = re.search(r'\?: "([^"]+)"', self.gradle).group(1)
        self.assertEqual(RECIPE.name, app_id + ".yml")

    def test_libaom_pin_matches_fetch_script_everywhere(self):
        commit = fns.NATIVE_SOURCES["libaom"]["commit"]
        self.assertIn(f"libaom@{commit}", self.text)
        srclib = (RECIPE.parent / "srclibs/libaom.yml").read_text(encoding="utf-8")
        self.assertIn("Repo: " + fns.NATIVE_SOURCES["libaom"]["url"], srclib)
        self.assertEqual(set(re.findall(r"libaom@([0-9a-f]{40})", self.text)), {commit})

    def test_libopus_sha_matches_fetch_script(self):
        self.assertIn(fns.NATIVE_SOURCES["libopus"]["sha256"], self.text)
        self.assertIn("fetch_native_sources.py --only libopus", self.text)

    def test_one_entry_per_abi_with_split_version_codes(self):
        base = int(re.search(r"versionCode = (\d+)", self.gradle).group(1))
        name = re.search(r'versionName = "([^"]+)"', self.gradle).group(1)
        digits = dict(re.findall(r'"(armeabi-v7a|arm64-v8a)" to (\d)', self.gradle))
        codes = {}
        for entry in self.entries():
            code = int(re.search(r"versionCode: (\d+)", entry).group(1))
            abi = re.search(r"app-fdroid-(armeabi-v7a|arm64-v8a)-release-unsigned\.apk", entry).group(1)
            codes[abi] = code
            self.assertIn("splitAbis=true", entry)
            self.assertIn("universalApk=false", entry)
            self.assertIn("ndk: r28c", entry)
        self.assertEqual(codes, {abi: base * 10 + int(d) for abi, d in digits.items()})

    def test_ndk_and_cmake_versions_match_gradle(self):
        self.assertIn('ndkVersion = "28.2.13676358"', self.gradle)  # r28c
        cmake = re.search(r'version = "(3\.\d+\.\d+)"', self.gradle).group(1)
        self.assertIn(f'sdkmanager --install "cmake;{cmake}"', self.text)

    def test_native_build_is_path_independent(self):
        self.assertIn("-ffile-prefix-map", self.cmake)
        for name in ("libopus", "libaom", "app", "build"):
            self.assertIn(f" {name}", self.cmake)

    def test_recipe_builds_the_fdroid_flavour_only(self):
        for entry in self.entries():
            self.assertRegex(entry, r"gradle:\n      - fdroid\n")
            self.assertIn("build/outputs/apk/fdroid/release/app-fdroid-", entry)
            self.assertNotIn("github", entry.split("output:")[1].splitlines()[0])
        # The flavours are declared once, and only the github one has the updater.
        self.assertRegex(self.gradle, r'create\("github"\)[^}]*"UPDATER_AVAILABLE", "true"')
        self.assertRegex(self.gradle, r'create\("fdroid"\)[^}]*"UPDATER_AVAILABLE", "false"')

    def test_install_permission_exists_only_in_the_github_flavour(self):
        permission = "android.permission.REQUEST_INSTALL_PACKAGES"
        main = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        github = (ROOT / "app/src/github/AndroidManifest.xml").read_text(encoding="utf-8")
        fdroid = (ROOT / "app/src/fdroid/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn(permission, main)
        self.assertRegex(github, r'<uses-permission android:name="' + re.escape(permission) + r'" />')
        self.assertRegex(fdroid, re.escape(permission) + r'" tools:node="remove"')

    def test_binaries_never_name_the_github_flavour_assets(self):
        for line in self.text.splitlines():
            if "Binaries:" in line:
                self.assertNotRegex(line, r"MeshLive-%v-[a-z0-9-]+\.apk", line)
                self.assertNotIn("MeshLive.apk", line)

    def test_no_updater_class_lives_in_the_fdroid_source_set(self):
        fdroid_sources = sorted(p.name for p in (ROOT / "app/src/fdroid").rglob("*.kt"))
        # FlavourBattery.kt is the battery-exemption prompt every flavour defines (github and play have their own);
        # it holds no network, installer or scheduler code, which the forbidden-token check below enforces on both.
        self.assertEqual(fdroid_sources, ["FlavourBattery.kt", "FlavourUpdater.kt"])
        for name in fdroid_sources:
            text = (ROOT / "app/src/fdroid/java/com/example/bluetoothtest" / name).read_text(encoding="utf-8")
            for forbidden in ("HttpURLConnection", "PackageInstaller", "URL(", "JobScheduler"):
                self.assertNotIn(forbidden, text, name)


if __name__ == "__main__":
    unittest.main()
