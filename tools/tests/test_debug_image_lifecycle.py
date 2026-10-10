"""Offline safety and consumption checks for explicitly owned debug images."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from debug_image_lifecycle import DebugImages, main

IMAGE = b"\x89PNG\r\n\x1a\nexplicitly owned offline fixture"


class DebugImagesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name).resolve()
        self.images = DebugImages(self.output)

    def image(self, name="setup.png"):
        path = self.images.reserve(name, "Review temporary UI setup")
        path.write_bytes(IMAGE)
        self.images.register(name)
        return path

    def index(self):
        return json.loads(self.images.index.read_text())["images"]

    def test_zero_captures_creates_no_files_or_folders_and_does_not_remove_unowned_folder(self):
        with self.images:
            pass
        self.assertEqual([], list(self.output.iterdir()))
        self.images.folder.mkdir()
        self.images.finalize()
        self.assertTrue(self.images.folder.exists())
        self.assertFalse(self.images.index.exists())

    def test_successful_review_deletes_bytes_and_keeps_small_hash_purpose_receipt(self):
        path = self.image()
        with self.images.review("setup.png") as reviewed:
            self.assertEqual(path, reviewed)
            self.assertEqual(IMAGE, reviewed.read_bytes())
        self.assertFalse(path.exists())
        self.assertFalse(self.images.folder.exists())
        item = self.index()["setup.png"]
        self.assertEqual("DELETED", item["state"])
        self.assertEqual(len(IMAGE), item["bytes"])
        self.assertEqual(64, len(item["sha256"]))
        self.assertEqual("Review temporary UI setup", item["purpose"])

    def test_failed_and_interrupted_reviews_remain_pending(self):
        for index, failure in enumerate((RuntimeError("consumer failed"), KeyboardInterrupt())):
            name = f"pending-{index}.png"
            path = self.image(name)
            with self.assertRaises(type(failure)), self.images:
                with self.images.review(name):
                    raise failure
            self.assertEqual(IMAGE, path.read_bytes())
            self.assertEqual("PENDING", self.index()[name]["state"])

    def test_already_consumed_bytes_stay_deleted_when_later_review_fails(self):
        first, second = self.image("first.png"), self.image("second.png")
        with self.assertRaises(RuntimeError), self.images:
            with self.images.review("first.png"):
                pass
            with self.images.review("second.png"):
                raise RuntimeError("second consumer failed")
        self.assertFalse(first.exists())
        self.assertTrue(second.exists())

    def test_explicit_retention_during_review_needs_reason_and_can_be_released(self):
        path = self.image()
        with self.assertRaises(ValueError):
            self.images.retain("setup.png", " ")
        with self.images.review("setup.png"):
            self.images.retain("setup.png", "Compare this unresolved framing defect against the next build")
        self.images.finalize()
        self.assertTrue(path.exists())
        self.assertEqual("RETAINED", self.index()["setup.png"]["state"])
        self.images.consume("setup.png")
        self.assertFalse(path.exists())

    def test_changed_image_blocks_deletion_until_explicit_registration_refresh(self):
        path = self.image()
        path.write_bytes(IMAGE + b"replacement")
        with self.assertRaisesRegex(ValueError, "changed after registration"):
            self.images.consume("setup.png")
        self.assertTrue(path.exists())
        self.assertEqual("PENDING", self.index()["setup.png"]["state"])
        self.images.register("setup.png")
        with self.images.review("setup.png"):
            pass
        self.assertFalse(path.exists())

    def test_registration_and_review_do_not_take_ownership_of_existing_or_unreserved_images(self):
        with self.assertRaises(ValueError):
            self.images.reserve("setup.png", "")
        reference = self.output / "reference.png"
        reference.write_bytes(IMAGE)
        self.images.folder.mkdir()
        unowned = self.images.folder / "unowned.png"
        unowned.write_bytes(IMAGE)
        with self.assertRaises(ValueError):
            self.images.reserve("unowned.png", "A name does not prove ownership")
        with self.assertRaises(ValueError):
            self.images.register("unowned.png")
        with self.assertRaises(ValueError):
            self.images.consume("unowned.png")
        path = self.image()
        with self.images.review("setup.png"):
            pass
        self.assertFalse(path.exists())
        self.assertEqual(IMAGE, reference.read_bytes())
        self.assertEqual(IMAGE, unowned.read_bytes())

    def test_missing_capture_and_non_file_capture_cannot_be_consumed(self):
        path = self.images.reserve("missing.png", "Temporary image not yet created")
        with self.assertRaises(ValueError):
            self.images.register("missing.png")
        with self.assertRaises(ValueError):
            self.images.consume("missing.png")
        path.mkdir()
        with self.assertRaises(ValueError):
            self.images.register("missing.png")
        self.images.finalize()
        self.assertTrue(path.is_dir())

    def test_exact_image_names_reject_traversal_absolute_paths_and_other_assets(self):
        outside = self.output / "outside.png"
        outside.write_bytes(IMAGE)
        for name in ("../outside.png", "..\\outside.png", str(outside), "sub/capture.png", "sub\\capture.png",
                     "log.json", "source.mp4", "reference.i420", "CON.png", "LPT1.jpg", "capture.png:stream",
                     "capture.png "):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.images.reserve(name, "Must not accept an arbitrary asset path")
        self.assertEqual(IMAGE, outside.read_bytes())

    def test_windows_junction_or_reparse_attribute_blocks_access_before_deletion(self):
        path = self.image()
        original = Path.lstat

        def reparse(item):
            info = original(item)
            if item == self.images.folder:
                return type("Reparse", (), {"st_mode": info.st_mode, "st_file_attributes": 1024})()
            return info

        with patch.object(Path, "lstat", reparse), self.assertRaisesRegex(ValueError, "link or junction"):
            self.images.consume("setup.png")
        self.assertEqual(IMAGE, path.read_bytes())

    def test_symbolic_image_cannot_delete_target(self):
        outside = self.output / "outside.png"
        outside.write_bytes(IMAGE)
        path = self.images.reserve("linked.png", "A link cannot be a disposable image")
        try:
            os.symlink(outside, path)
        except OSError as error:
            self.skipTest(f"Host does not allow creating a symbolic link: {error}")
        with self.assertRaisesRegex(ValueError, "link or junction"):
            self.images.register("linked.png")
        self.assertEqual(IMAGE, outside.read_bytes())

    def test_pending_cleanup_retry_verifies_bytes_and_deletes_only_exact_consumed_file(self):
        path = self.image()
        with patch.object(self.images, "finalize"):
            self.images.consume("setup.png")
        self.assertEqual("CONSUMED", self.index()["setup.png"]["state"])
        path.write_bytes(IMAGE + b"modified before retry")
        with self.assertRaisesRegex(ValueError, "changed after registration"):
            DebugImages(self.output).finalize()
        self.assertTrue(path.exists())
        path.write_bytes(IMAGE)
        DebugImages(self.output).finalize()
        self.assertFalse(path.exists())

    def test_cli_reservation_registration_and_consumption_work_across_tool_calls(self):
        args = ["--output", str(self.output), "--name", "cli.png"]
        main(["reserve", *args, "--purpose", "Read a temporary agent contact sheet"])
        path = self.images.folder / "cli.png"
        path.write_bytes(IMAGE)
        main(["register", *args])
        main(["consume", *args])
        main(["finalize", "--output", str(self.output)])
        self.assertFalse(path.exists())
        self.assertEqual("DELETED", self.index()["cli.png"]["state"])


if __name__ == "__main__":
    unittest.main()
