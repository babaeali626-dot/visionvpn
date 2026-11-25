import unittest
from scripts.verify_16kb_alignment import PAGE_SIZE, PAGE_SIZE_ABIS, Segment, parse_program_headers, validate_segments

class ParseTests(unittest.TestCase):
    def test_split_flags_and_relro(self):
        text = """
  LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x004000 R E 0x4000
  LOAD 0x004000 0x0000000000004000 0x0000000000004000 0x000800 0x00c000 RW 0x4000
  GNU_RELRO 0x004000 0x0000000000004000 0x0000000000004000 0x000800 0x00c000 R 0x1
"""
        s = parse_program_headers(text)
        self.assertEqual([x.kind for x in s], ["LOAD", "LOAD", "GNU_RELRO"])
        self.assertEqual(s[0].align, PAGE_SIZE)
        self.assertEqual(s[1].flags, "RW")
        self.assertEqual(s[2].memsz, 0xC000)
    def test_combined_flags(self):
        s = parse_program_headers(" LOAD 0x0 0x4000 0x4000 0x1000 0x1000 RWE 0x4000")
        self.assertEqual(s[0].flags, "RWE")
    def test_missing_load(self):
        with self.assertRaisesRegex(ValueError, "No PT_LOAD"): parse_program_headers("No headers")
    def test_malformed_load(self):
        with self.assertRaisesRegex(ValueError, "Malformed LOAD"): parse_program_headers("LOAD 0x0 0x0")

class AlignmentTests(unittest.TestCase):
    def test_accepts_aligned_loads_and_relro(self):
        s=[Segment("LOAD",0,0,0x4000,0x4000,0x4000,"RE"),
           Segment("LOAD",0x4000,0x4000,0x8000,0xC000,0x4000,"RW"),
           Segment("GNU_RELRO",0x4000,0x4000,0x8000,0xC000,1,"R")]
        self.assertEqual(validate_segments(s,"good.so","arm64-v8a"),[])
    def test_rejects_4k_load_for_64bit(self):
        s=[Segment("LOAD",0,0,0x1000,0x1000,0x1000,"RE")]
        self.assertTrue(any("requires at least" in e for e in validate_segments(s,"bad.so","arm64-v8a")))
    def test_skips_32bit_elf_page_alignment(self):
        s=[Segment("LOAD",0,0,0x1000,0x1000,0x1000,"RE"),Segment("GNU_RELRO",0,0x1000,0x800,0x800,1,"R")]
        self.assertEqual(validate_segments(s,"arm32.so","armeabi-v7a"),[])
        self.assertNotIn("armeabi-v7a",PAGE_SIZE_ABIS)
    def test_rejects_offset_vaddr_mismatch(self):
        s=[Segment("LOAD",0x1000,0x2000,0x1000,0x1000,0x4000,"RE")]
        self.assertTrue(any("congruence" in e for e in validate_segments(s,"bad.so","arm64-v8a")))
    def test_accepts_relro_with_16k_end_address(self):
        s=[Segment("LOAD",0,0,0x1000,0x1000,0x4000,"RW"),Segment("GNU_RELRO",0x4000,0x4000,0x1000,0x4000,1,"R")]
        self.assertEqual(validate_segments(s,"system.so","arm64-v8a"),[])
    def test_accepts_relro_that_covers_writable_load_tail(self):
        s=[Segment("LOAD",0,0,0x4000,0x4000,0x4000,"RE"),
           Segment("LOAD",0x4000,0x4000,0x3000,0x5000,0x4000,"RW"),
           Segment("GNU_RELRO",0x4000,0x4000,0x3000,0x5000,1,"R")]
        self.assertEqual(validate_segments(s,"tail.so","x86_64"),[])
    def test_rejects_relro_with_writable_data_after_it(self):
        s=[Segment("LOAD",0,0,0x4000,0x4000,0x4000,"RE"),
           Segment("LOAD",0x4000,0x4000,0x2000,0x8000,0x4000,"RW"),
           Segment("GNU_RELRO",0x4000,0x4000,0x1000,0x1000,1,"R")]
        self.assertTrue(any("not 16 KiB safe" in e for e in validate_segments(s,"bad-relro.so","arm64-v8a")))
    def test_accepts_relro_when_only_readonly_data_follows(self):
        s=[Segment("LOAD",0x4000,0x4000,0x1000,0x4000,0x4000,"R"),
           Segment("GNU_RELRO",0x4000,0x4000,0x1000,0x1000,1,"R")]
        self.assertEqual(validate_segments(s,"readonly-tail.so","arm64-v8a"),[])
    def test_rejects_relro_not_contained_by_any_load(self):
        s=[Segment("LOAD",0,0,0x4000,0x4000,0x4000,"RW"),
           Segment("GNU_RELRO",0x3000,0x3000,0x1000,0x3000,1,"R")]
        self.assertTrue(any("no PT_LOAD contains" in e
                            for e in validate_segments(s,"orphan-relro.so","arm64-v8a")))
    def test_relro_optional(self):
        s=[Segment("LOAD",0,0,0x4000,0x4000,0x4000,"RE")]
        self.assertEqual(validate_segments(s,"no-relro.so","arm64-v8a"),[])

if __name__ == "__main__": unittest.main()
