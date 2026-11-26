"""Regression checks for evidence validation; synthetic fixtures are never store artwork."""
import contextlib
import importlib.util
import io
import pathlib
import struct
import tempfile
import unittest
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('verify_evidence', pathlib.Path(__file__).with_name('verify-evidence.py'))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
delivery_spec = importlib.util.spec_from_file_location('prepare_debug_release', pathlib.Path(__file__).with_name('prepare-debug-release.py'))
delivery = importlib.util.module_from_spec(delivery_spec)
delivery_spec.loader.exec_module(delivery)


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

    def test_v12_requires_both_distinct_analytics_screens(self):
        for index, name in enumerate(verify.SCREENS_GUIDED[len(verify.SCREENS):], start=3):
            (self.root/(name+'.png')).write_bytes(png(1080, 1920, index))
        with self.assertRaises(FileNotFoundError):
            verify.verify_screens(self.root, verify.SCREENS_V12)
        (self.root/'analytics_expense.png').write_bytes(png(1080, 1920, 30))
        with self.assertRaises(FileNotFoundError):
            verify.verify_screens(self.root, verify.SCREENS_V12)
        (self.root/'analytics_income.png').write_bytes(png(1080, 1920, 31))
        with self.assertRaises(FileNotFoundError):
            verify.verify_screens(self.root, verify.SCREENS_V12)
        for index, name in enumerate(verify.PLANNER_SCREENS_V12 + ('entry_edit',), start=32):
            (self.root/(name+'.png')).write_bytes(png(1080, 1920, index))
        verify.verify_screens(self.root, verify.SCREENS_V12)

    def test_v12_requires_planner_cases_on_all_android_tiers(self):
        # `phone` and `tablet` are the API 36 device runs; API 26 and 33 are
        # independently filtered compatibility runs.
        for device in ('phone', 'tablet', 'api26', 'api33'):
            minimum, critical = verify.device_test_requirements(
                device, require_ux_v12=True)
            self.assertEqual(minimum, {'phone': 33, 'tablet': 33, 'api26': 20, 'api33': 33}[device])
            self.assertTrue(set(verify.PLANNER_UX_V12 + (verify.NOTIFICATION_OPT_OUT_V12, verify.NOTIFICATION_RESULT_V12)).issubset(critical), device)
        _, store_phone = verify.device_test_requirements('store-phone', require_ux_v12=True)
        self.assertNotIn(verify.PLANNER_UX_V12[0], store_phone)
        self.assertNotIn(verify.PLANNER_UX_V12[1], store_phone)
        # Older modes retain their previous requirements and remain callable.
        old_minimum, guided = verify.device_test_requirements('api33', require_guided_v11=True)
        self.assertEqual(old_minimum, 23)
        self.assertFalse(set(verify.PLANNER_UX_V12).intersection(guided))
        self.assertNotIn(verify.NOTIFICATION_OPT_OUT_V12, guided)
        self.assertIn('createCategoryFromEntryKeepsDraftAndSelectsIt', guided)
        self.assertIn('searchCurrencyAndConvertOnlyAfterConfirmation', guided)

    def test_v12_badging_requires_exact_debug_and_release_versions(self):
        valid = ("package: name='ru.epbloger.trachu' versionCode='3' versionName='1.2.0'\n"
                 "sdkVersion:'26'\ntargetSdkVersion:'36'\n")
        verify.verify_badging(valid, 'debug')
        verify.verify_badging(valid, 'release')
        for invalid in (valid.replace("versionCode='3'", "versionCode='2'"),
                        valid.replace("versionName='1.2.0'", "versionName='1.1.0'")):
            with self.assertRaises(ValueError):
                verify.verify_badging(invalid, 'release')
        # Historical evidence replay must not pretend older binaries are v1.2.
        verify.verify_badging(valid.replace("versionCode='3'", "versionCode='2'").replace("1.2.0","1.1.0"), 'release', None)

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

    def test_public_xml_omits_logs_and_runner_metadata(self):
        (self.root/'TEST.xml').write_text('<testsuite tests="1" failures="0" errors="0" hostname="private-runner"><testcase classname="Example" name="required"/><system-out>private output</system-out></testsuite>')
        xml, count = delivery.reduced_tests(self.root, 'TEST.xml', 'phone')
        self.assertEqual(count, 1)
        self.assertIn(b'name="required"', xml)
        self.assertNotIn(b'private', xml)
        self.assertNotIn(b'system-out', xml)
        (self.root/'TEST.xml').write_text('<testsuite tests="1" failures="0" errors="0"><testcase classname="Example" name="required"><failure/></testcase></testsuite>')
        with self.assertRaises(ValueError): delivery.reduced_tests(self.root, 'TEST.xml', 'phone')

    def test_release_upgrade_summary_parses_actual_ledger_count(self):
        path = self.root/'qa'/'upgrade'
        path.mkdir(parents=True)
        (path/'result.txt').write_text(
            'PASS: 1.1.0 -> 1.2.0, no clear or onboarding, 23 identical ledger records, '
            '4250 minor units; SHA256 ' + 'b'*64)
        summary = delivery.upgrade_summary(self.root, 'upgrade', '1.1.0')
        self.assertEqual(summary['identicalLedgerRecords'], 23)
        self.assertEqual(summary['exactBalanceMinorUnits'], 4250)
        (path/'result.txt').write_text((path/'result.txt').read_text().replace('1.2.0', '1x2x0'))
        with self.assertRaises(ValueError):
            delivery.upgrade_summary(self.root, 'upgrade', '1.1.0')

    def test_public_qa_zip_contains_only_allowlisted_material(self):
        qa = self.root/'evidence'
        screenshots = qa/'qa'/'store-phone'
        screenshots.mkdir(parents=True)
        (screenshots/'dashboard.png').write_bytes(png(1080, 1920, 7))
        (qa/'qa'/'current-evidence.txt').parent.mkdir(parents=True, exist_ok=True)
        (qa/'qa'/'current-evidence.txt').write_text('/home/runner/private diagnostic')
        archive = self.root/'public-qa.zip'
        report = b'<?xml version=\"1.0\"?><testsuite name=\"phone\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"Example\" name=\"safe\"/></testsuite>'
        delivery.write_qa_archive(archive, qa, {'phone': (report, 1)}, {'dashboard'}, {'sourceCommit': 'a'*40}, 'a'*40, '123')
        with zipfile.ZipFile(archive) as result:
            names = set(result.namelist())
            self.assertEqual(names, {'tests/phone.xml', 'screens/store-phone/dashboard.png', 'summary.json', 'README.txt'})
            self.assertNotIn(b'private diagnostic', b''.join(result.read(name) for name in names))

    def test_public_qa_zip_rejects_corrupt_original_screenshot(self):
        qa = self.root/'evidence'
        screenshots = qa/'qa'/'tablet'
        screenshots.mkdir(parents=True)
        (screenshots/'dashboard.png').write_bytes(png(1280, 800, 9)[:-5])
        with self.assertRaises(ValueError):
            delivery.write_qa_archive(
                self.root/'bad-qa.zip', qa, {'phone': (b'<testsuite/>', 0)},
                {'dashboard'}, {'sourceCommit': 'a'*40}, 'a'*40, '123')


if __name__ == '__main__':
    unittest.main()
