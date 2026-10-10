import contextlib
import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from tools.score_screen_geometry import DETECTOR, VALIDATION_CHECKS, analyze, main


class ScreenGeometryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.reference = np.full((160, 240, 3), 10, dtype=np.uint8)
        self.reference[:2] = self.reference[-2:] = 150
        self.reference[:, :2] = self.reference[:, -2:] = 150
        colors = [[240,20,30], [20,240,40], [20,30,240], [235,220,20],
                  [230,20,220], [20,210,220], [235,115,20], [110,30,225]]
        points = [[20,20], [210,20], [20,130], [210,130], [65,45], [165,45], [65,105], [165,105]]
        landmarks = []
        for index, ((x, y), rgb) in enumerate(zip(points, colors)):
            self.reference[y:y+10, x:x+10] = rgb
            landmarks.append({"id": f"patch{index}", "bounds": [x,y,10,10], "rgb": rgb})
        self.reference[65:81, 120:136] = 205
        yy, xx = np.mgrid[:160, :240]
        self.reference[(xx+0.5-95)**2 + (yy+0.5-80)**2 <= 15**2] = 245
        Image.fromarray(self.reference).save(self.base / "reference.png")
        self.spec = {
            "calibration": {"width": 240, "height": 160, "pixel_aspect": [1,1],
                            "reference": self.entry("reference.png"), "border": {"thickness": 2, "rgb": [150,150,150]},
                            "landmarks": landmarks, "square": {"bounds": [120,65,16,16], "rgb": [205,205,205]},
                            "circle": {"center": [95,80], "radius": 15, "rgb": [245,245,245]}},
            "viewport": [80,120,360,420], "mapping": {"mode": "FIT", "rotation": 0, "mirror": False},
            "detector": {"color_tolerance": 8, "minimum_area": 9},
            "tolerances": {"landmark_px": 2, "scale_fraction": 0.02, "stretch_fraction": 0.02,
                           "bars_px": 2, "crop_px": 2, "orientation_degrees": 1}}
        self.refresh_validation()
        self.render()

    def entry(self, name):
        return {"path": name, "sha256": hashlib.sha256((self.base/name).read_bytes()).hexdigest()}

    def refresh_validation(self, **changes):
        receipt = {"schema": "benchmark-screen-geometry-validation-v1", "detector": DETECTOR,
                   "scope": "raster-screen-capture", "reference_sha256": self.spec["calibration"]["reference"]["sha256"],
                   "tolerances": self.spec["tolerances"], "detector_settings": self.spec["detector"],
                   "checks": {name: True for name in VALIDATION_CHECKS}, **changes}
        (self.base/"validation.json").write_text(json.dumps(receipt), encoding="utf-8")
        self.spec["validation"] = self.entry("validation.json")

    def render(self, mode=None, rotation=None, mirror=None, scale_factor=1, stretch=None, interpolation=cv2.INTER_NEAREST):
        """Independent raster construction, not the adapter's mapping helper."""
        mapping = self.spec["mapping"]
        mode = mode or mapping["mode"]
        rotation = mapping["rotation"] if rotation is None else rotation
        mirror = mapping["mirror"] if mirror is None else mirror
        native = np.rot90(self.reference, -(rotation//90))
        if mirror:
            native = native[:, ::-1]
        x, y, w, h = self.spec["viewport"]
        scale = (min if mode == "FIT" else max)(w/native.shape[1], h/native.shape[0]) * scale_factor
        sx, sy = (scale, scale) if stretch is None else stretch
        scaled = cv2.resize(native, (round(native.shape[1]*sx), round(native.shape[0]*sy)), interpolation=interpolation)
        ox, oy = x + (w-scaled.shape[1])//2, y + (h-scaled.shape[0])//2
        output = np.full((700, 520, 3), 70, dtype=np.uint8)
        output[y:y+h, x:x+w] = 0
        left, top = max(ox,x), max(oy,y)
        right, bottom = min(ox+scaled.shape[1],x+w), min(oy+scaled.shape[0],y+h)
        output[top:bottom, left:right] = scaled[top-oy:bottom-oy, left-ox:right-ox]
        self.capture = output
        self.save_capture()

    def save_capture(self):
        Image.fromarray(self.capture).save(self.base / "capture.png")
        self.spec["capture"] = self.entry("capture.png")

    def score(self):
        return analyze(self.spec, self.base)

    @staticmethod
    def measurements(result):
        return {row["name"]: row for row in result["measurements"]}

    def test_exact_fit_uniform_scale_expected_borders_and_independent_capture(self):
        result = self.score()
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual("PASS", result["verdict"])
        self.assertAlmostEqual(1.5, result["mapping"]["scale_x"], delta=0.001)
        self.assertAlmostEqual(1.5, result["mapping"]["scale_y"], delta=0.001)
        self.assertEqual([0,90,0,90], result["mapping"]["expected_bars_px"])
        self.assertNotIn("encoded_resolution", result)

    def test_fill_detects_expected_crop_without_false_missing_corner_failure(self):
        self.spec["viewport"] = [80,120,240,240]
        self.spec["mapping"]["mode"] = "FILL"
        self.render()
        result = self.score()
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual([60,0,60,0], result["mapping"]["expected_crop_screen_px"])
        outside = [row for row in result["observations"] if row["expected_visibility"] == "OUTSIDE"]
        self.assertEqual(4, len(outside))

    def test_all_quarter_rotations_with_and_without_mirroring(self):
        for rotation in (0,90,180,270):
            for mirror in (False,True):
                with self.subTest(rotation=rotation, mirror=mirror):
                    self.spec["mapping"].update(rotation=rotation, mirror=mirror)
                    self.render()
                    result = self.score()
                    self.assertEqual("PASS", result["verdict"])
                    self.assertEqual(rotation, result["mapping"]["observed_rotation"])
                    self.assertEqual(mirror, result["mapping"]["observed_mirror"])

    def test_more_black_borders_than_requested_fail_even_with_preserved_aspect(self):
        self.render(scale_factor=0.8)
        result = self.score()
        self.assertEqual("MEASURED", result["measurement_status"])
        self.assertEqual("FAIL", result["verdict"])
        measurements = self.measurements(result)
        self.assertEqual("FAIL", measurements["screen_bars_error_px"]["status"])
        self.assertEqual("PASS", measurements["screen_stretch_fraction"]["status"])

    def test_horizontal_vertical_stretch_is_a_measured_failure(self):
        self.render(stretch=(1.5,1.25))
        result = self.score()
        self.assertEqual("FAIL", self.measurements(result)["screen_stretch_fraction"]["status"])
        self.assertGreater(result["mapping"]["scale_x"], result["mapping"]["scale_y"])

    def test_wrong_orientation_and_mirror_fail_without_changing_requested_map(self):
        for rotation, mirror in ((90,False),(0,True),(180,True)):
            with self.subTest(rotation=rotation, mirror=mirror):
                self.render(rotation=rotation, mirror=mirror)
                result = self.score()
                self.assertEqual("FAIL", self.measurements(result)["screen_orientation"]["status"])
                self.assertEqual({"mode":"FIT","rotation":0,"mirror":False}, result["mapping"]["requested"])

    def test_hidden_landmark_does_not_get_invented_from_remaining_points(self):
        self.capture[240:255,110:125] = 0
        self.save_capture()
        result = self.score()
        self.assertEqual("UNMEASURED", result["measurement_status"])
        self.assertIn("occluded", result["measurements"][-1]["reason"])

    def test_duplicate_colored_patch_is_ambiguous_not_nearest_match(self):
        self.capture[125:137,90:102] = [240,20,30]
        self.save_capture()
        result = self.score()
        self.assertEqual("UNMEASURED", result["verdict"])
        self.assertIn("ambiguous captured", result["measurements"][-1]["reason"])

    def test_reference_hash_capture_hash_and_validation_hash_are_verified(self):
        for key, nested in (("reference",True),("capture",False),("validation",False)):
            with self.subTest(key=key):
                original = copy.deepcopy(self.spec)
                entry = self.spec["calibration"][key] if nested else self.spec[key]
                entry["sha256"] = "0"*64
                result = self.score()
                self.assertEqual("UNMEASURED", result["verdict"])
                reasons = " ".join(row["reason"] for row in result["measurements"])
                self.assertIn("SHA256 mismatch", reasons)
                self.spec = original

    def test_missing_calibration_viewport_and_tolerances_are_explicit_prerequisites(self):
        for key in ("calibration","viewport","tolerances"):
            with self.subTest(key=key):
                saved = self.spec.pop(key)
                result = self.score()
                self.assertEqual("UNMEASURED", result["measurement_status"])
                self.assertEqual("screen_geometry_prerequisites", result["measurements"][-1]["name"])
                self.spec[key] = saved

    def test_missing_or_mismatched_validation_keeps_numbers_without_acceptance(self):
        self.spec.pop("validation")
        result = self.score()
        self.assertEqual("UNMEASURED", result["verdict"])
        self.assertEqual(8, len(result["measurements"]))
        self.assertTrue(all(row["status"] == "UNMEASURED" for row in result["measurements"]))
        self.assertAlmostEqual(1.5, result["mapping"]["scale_x"], delta=0.001)
        self.refresh_validation(scope="photographed-screen")
        self.assertEqual("UNMEASURED", self.score()["measurement_status"])

    def test_outside_viewport_and_nonfinite_tolerances_are_rejected(self):
        self.spec["viewport"] = [500,600,360,420]
        self.assertIn("viewport exceeds image", self.score()["measurements"][-1]["reason"])
        self.spec["viewport"] = [80,120,360,420]
        self.spec["tolerances"]["landmark_px"] = float("nan")
        self.assertIn("must be finite", self.score()["measurements"][-1]["reason"])

    def test_declared_square_circle_and_border_must_match_native_reference(self):
        for key, field, value in (("square","bounds",[120,65,16,15]),("circle","radius",14),
                                  ("border","rgb",[145,145,145])):
            with self.subTest(key=key):
                saved = self.spec["calibration"][key][field]
                self.spec["calibration"][key][field] = value
                self.assertEqual("UNMEASURED", self.score()["verdict"])
                self.spec["calibration"][key][field] = saved

    def test_rgb24_reference_reads_declared_file_frame_with_exact_hash(self):
        (self.base/"reference.rgb").write_bytes(bytes(self.reference.size)+self.reference.tobytes())
        self.spec["calibration"]["reference"] = {**self.entry("reference.rgb"), "format":"RGB24", "file_frame":1}
        self.refresh_validation()
        self.assertEqual("PASS", self.score()["verdict"])
        self.spec["calibration"]["reference"]["file_frame"] = 2
        self.assertEqual("UNMEASURED", self.score()["verdict"])

    def test_bilinear_resampling_has_a_valid_bounded_detector_error(self):
        self.render(interpolation=cv2.INTER_LINEAR)
        result = self.score()
        self.assertEqual("PASS", result["verdict"])
        self.assertLessEqual(self.measurements(result)["screen_landmark_error_px"]["value"], 2)

    def test_circle_distortion_cannot_hide_behind_unchanged_patch_centers(self):
        # Keep its centroid and every other point, but turn the circle into an
        # ellipse in captured pixels. Affine point correspondence alone misses it.
        self.capture[307:353,200:245] = 10
        yy, xx = np.mgrid[:700, :520]
        self.capture[((xx+0.5-222.5)/10)**2 + ((yy+0.5-330)/22.5)**2 <= 1] = 245
        self.save_capture()
        result = self.score()
        self.assertEqual("FAIL", self.measurements(result)["screen_shape_bounds_error_px"]["status"])

    def test_duplicate_ids_and_ambiguous_color_tolerances_are_not_accepted(self):
        self.spec["calibration"]["landmarks"][1]["id"] = "patch0"
        self.assertIn("unique", self.score()["measurements"][-1]["reason"])
        self.spec["calibration"]["landmarks"][1]["id"] = "patch1"
        self.spec["detector"]["color_tolerance"] = 32
        self.assertIn("ambiguous", self.score()["measurements"][-1]["reason"])

    def test_invalid_nested_metadata_stays_unmeasured_instead_of_crashing(self):
        for key, value in (("calibration",None),("mapping",[]),("detector",False),("tolerances",3)):
            with self.subTest(key=key):
                saved = self.spec[key]
                self.spec[key] = value
                self.assertEqual("UNMEASURED", self.score()["verdict"])
                self.spec[key] = saved
        (self.base/"validation.json").write_text("[]", encoding="utf-8")
        self.spec["validation"] = self.entry("validation.json")
        self.assertEqual("UNMEASURED", self.score()["verdict"])

    def test_cli_has_explicit_output_no_input_changes_and_refuses_overwrite(self):
        spec_path, output = self.base/"spec.json", self.base/"result.json"
        spec_path.write_text(json.dumps(self.spec), encoding="utf-8")
        before = spec_path.read_bytes()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["--spec",str(spec_path),"--output",str(output)]))
        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual("benchmark-screen-geometry-v1", result["schema"])
        self.assertEqual(before, spec_path.read_bytes())
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            main(["--spec",str(spec_path),"--output",str(output)])
        self.assertEqual(result, json.loads(output.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
