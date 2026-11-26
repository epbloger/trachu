"""Regression checks for evidence validation; synthetic fixtures are never store artwork."""
import contextlib
import importlib.util
import io
import pathlib
import struct
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('verify_evidence', pathlib.Path(__file__).with_name('verify-evidence.py'))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def png(width, height, pixel):
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag+data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 0, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress((b'\0'+bytes([pixel])*width)*height)) + chunk(b'IEND', b''))


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for index, name in enumerate(verify.SCREENS):
            (self.root/(name+'.png')).write_bytes(png(1080, 1920, index))

    def screens(self):
        with contextlib.redirect_stdout(io.StringIO()):
            verify.verify_screens(self.root)

    def test_actual_supported_resolution(self):
        self.screens()

    def test_v11_requires_all_new_distinct_screens(self):
        with self.assertRaises(FileNotFoundError):
            verify.verify_screens(self.root, verify.SCREENS_V11)
        for index, name in enumerate(verify.SCREENS_V11[len(verify.SCREENS):], start=3):
            (self.root/(name+'.png')).write_bytes(png(1080, 1920, index))
        verify.verify_screens(self.root, verify.SCREENS_V11)
        with self.assertRaises(FileNotFoundError):
            verify.verify_screens(self.root, verify.SCREENS_GUIDED)
        (self.root/'tutorial.png').write_bytes(png(1080, 1920, 19))
        verify.verify_screens(self.root, verify.SCREENS_GUIDED)

    def test_wrong_orientation(self):
        (self.root/'dashboard.png').write_bytes(png(1920, 1080, 0))
        with self.assertRaises(ValueError): self.screens()

    def test_mixed_dimensions(self):
        (self.root/'dashboard.png').write_bytes(png(1080, 2400, 0))
        with self.assertRaises(ValueError): self.screens()

    def test_duplicate_screen(self):
        (self.root/'operations.png').write_bytes((self.root/'dashboard.png').read_bytes())
        with self.assertRaises(ValueError): self.screens()

    def test_truncated_png(self):
        path = self.root/'dashboard.png'
        path.write_bytes(path.read_bytes()[:-6])
        with self.assertRaises(ValueError): self.screens()

    def test_invalid_crc(self):
        path = self.root/'dashboard.png'
        data = bytearray(path.read_bytes()); data[20] ^= 1; path.write_bytes(data)
        with self.assertRaises(ValueError): self.screens()

    def test_test_report_must_contain_critical_case(self):
        (self.root/'TEST.xml').write_text('<testsuite tests="1" failures="0" errors="0"><testcase classname="Example" name="other"/></testsuite>')
        with self.assertRaises(ValueError): verify.verify_tests(self.root, 'TEST.xml', 1, ('required',))

    def test_summary_cannot_hide_failure(self):
        (self.root/'TEST.xml').write_text('<testsuite tests="1" failures="0" errors="0"><testcase classname="Example" name="required"><failure/></testcase></testsuite>')
        with self.assertRaises(ValueError): verify.verify_tests(self.root, 'TEST.xml', 1, ('required',))


if __name__ == '__main__':
    unittest.main()
