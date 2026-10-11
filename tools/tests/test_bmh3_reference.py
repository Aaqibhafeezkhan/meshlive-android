"""bmh3_reference: the executable BMH3 specification reproduces, rejects what it must and matches the Kotlin fixture."""
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

try:
    import bmh3_reference as ref
except ImportError:  # the third-party `cryptography` package is optional host tooling
    ref = None

FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/java/com/example/bluetoothtest/Bmh3Vectors.kt"


@unittest.skipIf(ref is None, "needs the cryptography package")
class Bmh3ReferenceTest(unittest.TestCase):
    def test_vectors_are_deterministic_and_have_the_specified_sizes(self):
        first = ref.build_vectors()
        second = ref.build_vectors()
        self.assertEqual(first, second)
        sizes = [v["message_lengths"] for v in first["vectors"]]
        self.assertEqual(sizes[0], {"m1": 122, "m2": 431, "m3": 334})
        self.assertEqual(sizes[1], {"m1": 122, "m2": 105, "m3": 334, "m4": 334})
        self.assertEqual(sizes[2], {"m1": 122, "m2": 431, "m3": 334})

    def test_every_negative_case_is_rejected_except_the_documented_reuse_case(self):
        results = {case["case"].split()[0]: case["result"] for case in ref.negative_cases()}
        self.assertEqual(results.pop("13"), "ACCEPTED")
        self.assertTrue(results and all(result == "rejected" for result in results.values()), results)

    def test_a_contact_tag_depends_on_the_lease_key_the_whole_msg1_and_the_advert(self):
        key = ref.contact_tag_key(ref.LEASE_KEY)
        other = ref.contact_tag_key(bytes(32))
        body = bytes(106)
        binding = ref.advert_binding(bytes(range(6)), 0, None)
        tag = ref.contact_tag(ref.PURPOSE_LINK, key, body + bytes(16), binding)
        self.assertEqual(len(tag), 16)
        self.assertNotEqual(tag, ref.contact_tag(ref.PURPOSE_LINK, other, body + bytes(16), binding))
        self.assertNotEqual(tag, ref.contact_tag(ref.PURPOSE_ENDPOINT, key, body + bytes(16), binding))
        self.assertNotEqual(tag, ref.contact_tag(ref.PURPOSE_LINK, key, bytes(105) + b"\x01" + bytes(16), binding))
        # A recorded tag does not match the responder's next advert (a new RDI), nor another wall epoch for version 2.
        self.assertNotEqual(tag, ref.contact_tag(ref.PURPOSE_LINK, key, body + bytes(16), ref.advert_binding(bytes(6), 0, None)))
        self.assertNotEqual(ref.advert_binding(bytes(6), 0, 7), ref.advert_binding(bytes(6), 0, 8))
        self.assertEqual(len(binding), ref.BINDING_BYTES)

    def test_extension_areas_are_always_112_bytes(self):
        for exts in ([], [(ref.EXT_INTENT, b"\x01")], [(ref.EXT_OPENING, ref.opening_value([ref.OPENING_ENTRY]))],
                     [(ref.EXT_OPENING, ref.opening_value([ref.OPENING_ENTRY] * 2))]):
            area = ref.ext_area(exts)
            self.assertEqual(len(area), 112)
            self.assertEqual(set(ref.parse_extensions(area)) - {ref.EXT_PAD}, {t for t, _ in exts})
        # Two opening entries and a build tag still fit; an oversize unknown extension does not.
        self.assertEqual(len(ref.ext_area([(ref.EXT_OPENING, ref.opening_value([ref.OPENING_ENTRY] * 2)), (ref.EXT_BUILD_TAG, b"\x80\x07")])), 112)
        with self.assertRaises(ValueError):
            ref.ext_area([(ref.EXT_OPENING, ref.opening_value([ref.OPENING_ENTRY] * 2)), (0x7f, bytes(40))])

    def test_the_kotlin_fixture_is_the_generators_output(self):
        self.assertEqual(
            FIXTURE.read_text(encoding="utf-8").replace("\r\n", "\n"),
            ref.kotlin_fixture(ref.build_vectors()),
            "run: python -B tools/bmh3_reference.py --kotlin app/src/test/java/com/example/bluetoothtest/Bmh3Vectors.kt",
        )


if __name__ == "__main__":
    unittest.main()
