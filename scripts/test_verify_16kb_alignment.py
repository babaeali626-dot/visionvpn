import unittest

from scripts.verify_16kb_alignment import (
    PAGE_SIZE, Segment, parse_program_headers, validate_segments
)


class ParseProgramHeadersTests(unittest.TestCase):
    def test_parses_split_flags_and_gnu_relro(self):
        output = """
Program Headers:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flags  Align
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x4000
  LOAD           0x004000 0x0000000000004000 0x0000000000004000 0x000800 0x001000 RW  0x4000
  GNU_RELRO      0x004000 0x0000000000004000 0x0000000000004000 0x000800 0x000800 R 0x1
"""
        segments = parse_program_headers(output)
        self.assertEqual([s.kind for s in segments], ["LOAD", "LOAD", "GNU_RELRO"])
        self.assertEqual(segments[0].align, PAGE_SIZE)
        self.assertEqual(segments[2].memsz, 0x800)

    def test_parses_combined_flags(self):
        output = " LOAD 0x000000 0x00004000 0x00004000 0x001000 0x001000 RWE 0x4000\n"
        self.assertEqual(parse_program_headers(output)[0].vaddr, 0x4000)

    def test_rejects_missing_load(self):
        with self.assertRaisesRegex(ValueError, "No PT_LOAD"):
            parse_program_headers("There are no program headers in this file.\n")

    def test_rejects_malformed_load(self):
        with self.assertRaisesRegex(ValueError, "Malformed LOAD"):
            parse_program_headers("LOAD 0x0 0x0\n")


class ValidateSegmentsTests(unittest.TestCase):
    def test_accepts_16kb_and_relro_inside_load(self):
        segments = [
            Segment("LOAD", 0, 0, 0x4000, 0x4000, 0x4000),
            Segment("LOAD", 0x4000, 0x4000, 0x1000, 0x2000, 0x4000),
            Segment("GNU_RELRO", 0x4000, 0x4000, 0x800, 0x800, 1),
        ]
        self.assertEqual(validate_segments(segments, "libsample.so"), [])

    def test_rejects_alignment_below_16kb(self):
        segments = [Segment("LOAD", 0, 0, 0x1000, 0x1000, 0x1000)]
        self.assertTrue(any("requires at least" in e for e in validate_segments(segments, "libbad.so")))

    def test_rejects_offset_vaddr_congruence_mismatch(self):
        segments = [Segment("LOAD", 0x1000, 0x2000, 0x1000, 0x1000, 0x4000)]
        self.assertTrue(any("congruence" in e for e in validate_segments(segments, "libbad.so")))

    def test_rejects_relro_outside_load(self):
        segments = [
            Segment("LOAD", 0, 0, 0x1000, 0x1000, 0x4000),
            Segment("GNU_RELRO", 0x4000, 0x4000, 0x800, 0x800, 1),
        ]
        self.assertTrue(any("outside every PT_LOAD" in e for e in validate_segments(segments, "libbad.so")))

    def test_relro_is_optional_for_elfs_without_it(self):
        segments = [Segment("LOAD", 0, 0, 0x4000, 0x4000, 0x4000)]
        self.assertEqual(validate_segments(segments, "lib-no-relro.so"), [])


if __name__ == "__main__":
    unittest.main()
