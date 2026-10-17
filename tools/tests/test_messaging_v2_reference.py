"""The Messaging v2 reference (PROTOCOL "Messaging v2"): deterministic vectors that the Kotlin fixture must equal."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
SPEC = importlib.util.spec_from_file_location("messaging_v2_reference", ROOT / "tools" / "messaging_v2_reference.py")
reference = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reference)
FIXTURE = ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "bluetoothtest" / "MessagingV2Vectors.kt"


class MessagingV2ReferenceTest(unittest.TestCase):
    def test_vectors_are_deterministic(self):
        self.assertEqual(reference.vectors(), reference.vectors())

    def test_kotlin_fixture_matches_the_generator(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "MessagingV2Vectors.kt"
            self.assertEqual(reference.main(["--kotlin", str(target)]), 0)
            self.assertEqual(FIXTURE.read_bytes(), target.read_bytes(), "re-run tools/messaging_v2_reference.py --kotlin")

    def test_documented_sizes(self):
        self.assertEqual(reference.SEGMENT_DATA, 1_044_480)
        self.assertEqual(reference.segment_padded(reference.SEGMENT_DATA), 1_044_572)
        self.assertEqual(reference.chunk_count(1_044_572), 128)
        self.assertEqual(reference.chunk_count(reference.segment_padded(2_000_000 - reference.SEGMENT_DATA)), 119)
        vs = reference.vectors()
        self.assertEqual(len(bytes.fromhex(vs["bsm2_text"])), reference.BSM2_HEADER + len(b"hello v2"))
        self.assertEqual(vs["manifest_segment_chunks"], "128,119")
        self.assertEqual(len(bytes.fromhex(vs["hello_v1"])), 9 + 17)
        self.assertEqual(len(bytes.fromhex(vs["hello_v2"])), 9 + 19)
        self.assertEqual(vs["segment_key_0"], "cf6be4ac0c090df7ff506602b70a76a3177245acd216240d8cbe17824709af35")

    def test_the_bulk_bundle_is_self_consistent(self):
        vs = reference.vectors()
        header = bytes.fromhex(vs["bulk_header"])
        self.assertEqual(header[:5], b"BSB1\x03")
        self.assertEqual(header[13], 4, "class 4")
        self.assertEqual(header[20], 0, "no key slot")
        self.assertEqual(hashlib.sha256(header).hexdigest(), vs["bulk_bundle_id"])
        chunks = [bytes.fromhex(c) for c in vs["bulk_chunks"].split(",")]
        hashes = b"".join(hashlib.sha256(c).digest()[:16] for c in chunks)
        self.assertTrue(header.endswith(hashes))
        self.assertEqual(len(header), 21 + 8 + 16 * len(chunks))

    def test_padme_matches_the_documented_samples(self):
        self.assertEqual(reference.padded_length(0), 1024)
        self.assertEqual(reference.padded_length(8074 - 4), 8192)
        self.assertEqual(reference.padded_length(10 ** 9), reference.MAX_PADDED)


if __name__ == "__main__":
    unittest.main()
