"""Verify the real screenshots and release evidence, without rebuilding Android.

Screens are taken by the instrumentation suite, not resized or generated here.
Only static 16-KiB APK/ELF compatibility is checked, not device execution.
"""
import argparse
import hashlib
import pathlib
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
import zlib

SCREENS = ('dashboard', 'operations', 'split_entry')
SCREENS_V11 = SCREENS + ('onboarding', 'drawer', 'converter', 'quick_entry', 'dashboard_dark')
SCREENS_GUIDED = SCREENS_V11 + ('tutorial',)
SCREENS_V12 = SCREENS_GUIDED + ('analytics_expense', 'analytics_income')
PLANNER_SCREENS_V12 = ('planner_calendar', 'goals', 'goal_funding', 'goal_history', 'debts', 'debt_payment')
SCREENS_V12 += PLANNER_SCREENS_V12 + ('entry_edit',)
PLANNER_UX_V12 = ('groupedNavigationAndGoalAdjustments', 'principalAndInterestAreRecordedExactlyOnce')
NOTIFICATION_OPT_OUT_V12 = 'persistedPinRateLimitConsentAndBudgetThreshold'
NOTIFICATION_RESULT_V12 = 'notificationChoiceAndPermissionResultSurviveRecreation'
PAGE = 16 * 1024


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def verify_screens(directory, names=SCREENS):
    sizes, hashes = set(), set()
    for screen in names:
        path = directory / (screen + '.png')
        data = path.read_bytes()
        require(len(data) <= 3_000_000, f'{screen}: exceeds phone image size limit')
        require(data.startswith(b'\x89PNG\r\n\x1a\n'), f'{screen}: not PNG')
        offset, dimensions, ended, pixels = 8, None, False, False
        while offset + 12 <= len(data):
            length, tag = struct.unpack_from('>I4s', data, offset)
            end = offset + 8 + length
            require(end + 4 <= len(data), f'{screen}: truncated PNG')
            crc = struct.unpack_from('>I', data, end)[0]
            require(zlib.crc32(data[offset+4:end]) == crc, f'{screen}: bad PNG CRC')
            if tag == b'IHDR':
                require(offset == 8 and length == 13, f'{screen}: bad IHDR')
                dimensions = struct.unpack_from('>II', data, offset + 8)
            if tag == b'IDAT':
                pixels = True
            offset = end + 4
            if tag == b'IEND':
                require(length == 0, f'{screen}: bad IEND')
                ended = True
                break
        require(ended and pixels and offset == len(data) and dimensions is not None,
                f'{screen}: incomplete PNG')
        width, height = dimensions
        # UiAutomation captures the compositor output, which may differ from
        # adb wm size overrides. Check the actual image against the quality
        # target and RuStore mobile limits, not the requested emulator size.
        require(1080 <= width <= 2160 and 1920 <= height <= 3840 and width < height,
                f'{screen}: unsuitable portrait dimensions {width}x{height}')
        sizes.add(dimensions)
        hashes.add(hashlib.sha256(data).hexdigest())
        print(f'PASS screen {screen}: {width}x{height}, {len(data)} bytes')
    require(len(sizes) == 1 and len(hashes) == len(names),
            'Screens must be distinct and use consistent portrait dimensions')


def provenance(root, commit):
    require((root/'qa/source-commit.txt').read_text().strip() == commit,
            f'{root}: unexpected source commit')
    return dict((path, digest) for digest, path in
                (line.split(maxsplit=1) for line in (root/'qa/SHA256SUMS').read_text().splitlines()))


def elf_loads(data, name):
    require(data[:4] == b'\x7fELF' and data[4] in (1, 2) and data[5] in (1, 2),
            f'{name}: invalid ELF')
    endian = '<' if data[5] == 1 else '>'
    wide = data[4] == 2
    table = struct.unpack_from(endian+('Q' if wide else 'I'), data, 32 if wide else 28)[0]
    stride, count = struct.unpack_from(endian+'HH', data, 54 if wide else 42)
    loads = 0
    require(stride >= (56 if wide else 32) and table+stride*count <= len(data),
            f'{name}: invalid program headers')
    for index in range(count):
        start = table+index*stride
        if struct.unpack_from(endian+'I', data, start)[0] != 1:
            continue
        loads += 1
        off, addr = struct.unpack_from(endian+('QQ' if wide else 'II'), data, start+(8 if wide else 4))
        align = struct.unpack_from(endian+('Q' if wide else 'I'), data, start+(48 if wide else 28))[0]
        require(align >= PAGE and align & (align-1) == 0 and off % PAGE == addr % PAGE,
                f'{name}: incompatible LOAD segment: alignment {align}')
    require(loads > 0, f'{name}: no LOAD segments')


def verify_binary(root, extension, commit, expected):
    require(provenance(root, commit) == expected, f'{extension}: inconsistent artifact manifest')
    relative = [p for p in expected if p.endswith('.'+extension) and '/release/' in p]
    require(len(relative) == 1, f'{extension}: expected exactly one release file')
    file = root/relative[0]
    require(hashlib.sha256(file.read_bytes()).hexdigest() == expected[relative[0]],
            f'{extension}: SHA-256 mismatch')
    with zipfile.ZipFile(file) as archive, file.open('rb') as raw:
        require(archive.testzip() is None, f'{extension}: ZIP CRC mismatch')
        libraries = [i for i in archive.infolist() if i.filename.endswith('.so')]
        for lib in libraries:
            elf_loads(archive.read(lib), lib.filename)
            if extension == 'apk':
                raw.seek(lib.header_offset)
                header = raw.read(30)
                require(header[:4] == b'PK\x03\x04', 'Invalid ZIP local header')
                name_size, extra_size = struct.unpack_from('<HH', header, 26)
                position = lib.header_offset + 30 + name_size + extra_size
                require(lib.compress_type == zipfile.ZIP_STORED and position % PAGE == 0,
                        f'{lib.filename}: APK native library not stored at 16-KiB boundary')
        print(f'PASS {extension.upper()} SHA-256 and ZIP CRC; {len(libraries)} ELF libraries checked')


def verify_tests(root, pattern, minimum, critical=()):
    files = list(root.glob(pattern))
    require(files, f'{pattern}: missing test reports')
    cases = set()
    for file in files:
        suite = ET.parse(file).getroot()
        require(suite.tag == 'testsuite', f'{file}: unexpected report format')
        for attr in ('failures', 'errors', 'skipped'):
            require(int(suite.get(attr, '0')) == 0, f'{file}: {attr}')
        children = suite.findall('testcase')
        require(len(children) == int(suite.attrib['tests']), f'{file}: incomplete test cases')
        for case in children:
            identity = (case.attrib['classname'], case.attrib['name'])
            require(identity not in cases and all(case.find(tag) is None for tag in ('failure','error','skipped')),
                    f'{file}: duplicate or unsuccessful case {identity}')
            cases.add(identity)
    count = len(cases)
    require(count >= minimum, f'{pattern}: incomplete coverage ({count})')
    missing = set(critical) - {name for _, name in cases}
    require(not missing, f'{pattern}: missing critical tests {sorted(missing)}')
    print(f'PASS {pattern}: {count} tests')
    return count


def verify_badging(text, variant, version=('3', '1.2.0')):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    require(package is not None and package.group(1) == 'ru.epbloger.trachu' and
            (version is None or package.groups()[1:] == version),
            f'{variant}: unexpected package, versionCode or versionName')
    require("sdkVersion:'26'" in text and "targetSdkVersion:'36'" in text,
            f'{variant}: unexpected Android SDK versions')


def device_test_requirements(device, require_ux_v11=False, require_guided_v11=False, require_ux_v12=False):
    """Return the minimum report count and required cases for one device tier."""
    require_guided_v11 = require_guided_v11 or require_ux_v12
    require_ux_v11 = require_ux_v11 or require_guided_v11
    storage = ('legacyEncryptedArchiveWithMultiMegabyteReceiptRestoresAtomically',
               'transactionRollbackAndRestore', 'concurrentDueProcessingAndCorruptImportAreAtomic',
               'migrationPreservesLegacyRowsAndReopen', 'keystorePinAndTampering')
    editors = ('repeatExpenseRequiresSaveAndDoesNotCopyOriginalIdentityOrReceipt',
               'accountDraftSurvivesRecreationAndCancelDiscardsIt',
               'entryDraftAndPendingReceiptSurviveRecreation',
               'staleEditorCannotUndoBackgroundAutoPosting',
               'corruptedLedgerCanBeRecoveredFromBackupOnlyAfterUnlock',
               'selectedFileWaitsForUnlockAndExportsOnlyOnce',
               'completedScheduleCanBeEditedButCannotBeEnabledPastEnd')
    setup = ('actualFirstSetupSurvivesRecreationAndNewActivity', 'actualLegacyPreferencesAndExistingLedgerArePreserved',
             'firstLaunchRestoreChecksPasswordAndCompletesEvenForEmptyBackup',
             'protectedEmptyLedgerCannotEnterSetupOrRestoreBeforePin', 'recordExpenseAndNavigateAfterRotation')
    if require_ux_v11 and device != 'store-phone':
        editors += ('createCategoryFromEntryKeepsDraftAndSelectsIt',
                    'searchCurrencyAndConvertOnlyAfterConfirmation')
    critical = (('recordExpenseAndNavigateAfterRotation',) if device == 'store-phone' else
                storage if device == 'api26' else storage + editors)
    if require_ux_v11 and device != 'store-phone':
        critical += setup
    if require_guided_v11 and device != 'store-phone':
        critical += ('converterFetchesAutomaticallyAndPreservesManualRate',
                     'onboardingStartsRealWalkthroughAndReplayCanBeSkippedWithoutEntries',
                     'lockingDuringReplayRemovesTheGuideAndNeverBypassesPin')
        if device != 'api26':
            critical += ('newCategoryEditorUsesDistinctPaletteColor',
                         'categoryEditorPreservesAnExistingCustomColor',
                         'eachNewSplitPartRequiresItsOwnCategory',
                         'editingEntryKeepsItsOriginalCategorySelected')
    if require_ux_v12 and device != 'store-phone':
        critical += PLANNER_UX_V12 + (NOTIFICATION_OPT_OUT_V12, NOTIFICATION_RESULT_V12)

    minimums = {
        'phone': 17, 'tablet': 17, 'store-phone': 1, 'api26': 9, 'api33': 16,
    }
    if require_ux_v11:
        minimums.update(phone=23, tablet=23, api26=14, api33=23)
    if require_ux_v12:
        minimums.update(phone=33, tablet=33, api26=20, api33=33)
    require(device in minimums, f'Unknown device tier: {device}')
    return minimums[device], critical


def verify_release(qa, apk, aab, commit, require_ux_v11=False, require_guided_v11=False, require_ux_v12=False):
    require_guided_v11 = require_guided_v11 or require_ux_v12
    require_ux_v11 = require_ux_v11 or require_guided_v11
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Expected full Git SHA')
    expected = provenance(qa, commit)
    verify_binary(apk, 'apk', commit, expected)
    verify_binary(aab, 'aab', commit, expected)
    jvm = (
        'stagedBackupDoesNotOpenDestinationOnGenerationFailure',
        'backupRetainsLegacyEnvelopeAndAuthenticatesEntireFragmentedInput',
        'csvRoundTripAndIdempotence', 'statementRepeatedImportDoesNotDuplicate',
        'historicalRatesAndCrossCurrencyTransfer', 'refundAllocatesHistoricalAmountsExactly',
        'singleFuturePaymentPostsOnlyWhenDueAndOnlyOnce', 'monthlyAnchorAndIdempotentGeneration')
    if require_ux_v11:
        jvm += ('crossCurrencyUsesHistoricalRateAndMinorUnits', 'nonRubBaseAndSameCurrency',
                'roundsHalfEvenAcrossZeroAndThreeDecimalCurrencies', 'rejectsInvalidRateNegativeAndOverflow',
                'storedCrossRateRoundsOnlyOnceAtExactHalf', 'pristineDatabaseInEitherCategoryOrderNeedsSetup',
                'existingFinancialDataAndCustomizedCategoriesAreNeverReinitialized',
                'firstAccountUsesChosenCurrencyAndExactOpeningWithoutIncome')
    if require_guided_v11:
        jvm += ('newCategoriesReceiveDistinctColorsAndExplicitColorsAreNotChanged',
                'legacyDuplicateColorsHaveStableDistinctChartColors')
    count = verify_tests(qa, 'app/build/test-results/testDebugUnitTest/TEST-*.xml',
                         43 if require_ux_v11 else 35, jvm)
    for device in ('phone', 'tablet', 'store-phone', 'api26', 'api33'):
        minimum, critical = device_test_requirements(device, require_ux_v11, require_guided_v11, require_ux_v12)
        count += verify_tests(qa, f'qa/{device}/androidTest-results/connected/debug/TEST-*.xml', minimum, critical)
    if require_ux_v12:
        count += verify_tests(qa, 'qa/store-phone-planner/androidTest-results/connected/debug/TEST-*.xml', 2, PLANNER_UX_V12)
    for variant in ('debug', 'release'):
        lint = ET.parse(qa/f'app/build/reports/lint-results-{variant}.xml').getroot()
        require(not any(i.get('severity', '').lower() in ('error', 'fatal') for i in lint.findall('issue')),
                f'Lint {variant}: errors')
    for variant, report in (('debug', 'apk-badging.txt'), ('release', 'release-apk-badging.txt')):
        verify_badging((qa/'qa'/report).read_text(encoding='utf-8'), variant,
                       ('3', '1.2.0') if require_ux_v12 else None)
    for report in ('apk-permissions.txt', 'release-apk-permissions.txt'):
        permissions = (qa/'qa'/report).read_text(encoding='utf-8')
        require(not re.search(r'permission\.(AD_ID|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION|READ_CONTACTS|READ_SMS|RECORD_AUDIO)\b',
                              permissions), f'{report}: unexpected permission')
    offline = qa/'qa/offline'
    require((offline/'result.txt').read_text().strip() ==
            'PASS: offline account and expense, cold restart, persisted exact 4250 minor units, no default network before/after.',
            'Missing successful offline persistence evidence')
    for stage in ('before', 'after'):
        require(re.search(r'Active default network:\s*(none|null)\s*$',
                          (offline/f'connectivity-{stage}.txt').read_text(), re.I|re.M),
                f'Offline {stage}: default network exists')
    if require_ux_v12:
        for api in (33, 36):
            require((qa/f'qa/notification-api{api}/result.txt').read_text(encoding='utf-8').strip() ==
                    f'PASS: API {api} Android notification allow and deny, OS permission and app preference persisted, cold restart without prompt, onboarding preserved.',
                    f'Missing real notification permission evidence on API {api}')
        for directory, baseline, sha_file, sha in (
            ('upgrade', '1.0.0', 'upgrade-baseline-commit.txt', '55a0d207e709d6319426772c1bd46d70943b1062'),
            ('upgrade-v11', '1.1.0', 'upgrade-v11-baseline-commit.txt', '7e75bea159803e06d7473d8c4203e72f0b823bea')):
            require((qa/'qa'/sha_file).read_text().strip() == sha, f'Incorrect {baseline} baseline')
            require(re.fullmatch(r'PASS: '+re.escape(baseline)+r' -> 1\.2\.0, no clear or onboarding, [1-9][0-9]* identical ledger records, 4250 minor units; SHA256 [0-9a-f]{64}',
                                 (qa/'qa'/directory/'result.txt').read_text().strip()),
                    f'Missing successful in-place upgrade from {baseline}')
        require(re.fullmatch(r'PASS: Android HTTPS CBR feed, [1-9][0-9]* positive rates, publication day \d+, requested day \d+',
                             (qa/'qa/tablet/live-cbr.txt').read_text().strip()), 'No successful Android HTTPS CBR probe')
        require((qa/'qa/ads-config.txt').read_text().strip() == 'Debug: official demo only\nRelease: R-M-20514950-1; consent required',
                'Unexpected advertising configuration for test delivery')
        require('CN=Android Debug' in (qa/'qa/apk-signature.txt').read_text() and
                'DOES NOT VERIFY' in (qa/'qa/release-apk-unsigned.txt').read_text() and
                'jar is unsigned.' in (qa/'qa/release-aab-unsigned.txt').read_text(), 'Missing debug/unsigned signature evidence')
    screens = SCREENS_V12 if require_ux_v12 else SCREENS_GUIDED if require_guided_v11 else SCREENS_V11 if require_ux_v11 else SCREENS
    verify_screens(qa/'qa/store-phone', screens)
    print(f'PASS {count} tests, lint, exact offline amount and release artifact checks for {commit}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    screens = sub.add_parser('screens')
    screens.add_argument('directory', type=pathlib.Path)
    screens.add_argument('--require-ux-v11', action='store_true')
    screens.add_argument('--require-guided-v11', action='store_true')
    screens.add_argument('--require-ux-v12', action='store_true')
    release = sub.add_parser('release')
    release.add_argument('--qa', required=True, type=pathlib.Path)
    release.add_argument('--apk', required=True, type=pathlib.Path)
    release.add_argument('--aab', required=True, type=pathlib.Path)
    release.add_argument('--commit', required=True)
    release.add_argument('--require-ux-v11', action='store_true')
    release.add_argument('--require-guided-v11', action='store_true')
    release.add_argument('--require-ux-v12', action='store_true')
    args = parser.parse_args()
    if args.mode == 'screens':
        names = SCREENS_V12 if args.require_ux_v12 else SCREENS_GUIDED if args.require_guided_v11 else SCREENS_V11 if args.require_ux_v11 else SCREENS
        verify_screens(args.directory, names)
    else:
        verify_release(args.qa, args.apk, args.aab, args.commit, args.require_ux_v11, args.require_guided_v11, args.require_ux_v12)
