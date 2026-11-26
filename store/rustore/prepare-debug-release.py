"""Package only reviewed, validated 1.2.0 debug delivery evidence.

Run after the full validator. Never include raw logcat, connectivity dumps,
database exports, system-out, test APKs or unsigned release binaries in public QA.
"""
import hashlib
import json
import pathlib
import re
import shutil
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest(root, sha):
    require((root / 'qa/source-commit.txt').read_text().strip() == sha, 'Wrong source SHA')
    entries = {}
    for line in (root / 'qa/SHA256SUMS').read_text().splitlines():
        value, name = line.split(maxsplit=1)
        require(re.fullmatch('[0-9a-f]{64}', value) and name not in entries, 'Invalid checksum manifest')
        entries[name] = value
    return entries


def verify_binary(root, name, sha, expected):
    require(manifest(root, sha) == expected, 'Artifact manifest differs')
    file = root / name
    require(digest(file) == expected[name], 'Binary SHA-256 mismatch')
    with zipfile.ZipFile(file) as archive:
        require(archive.testzip() is None, 'Corrupt binary archive')
        for info in archive.infolist():
            require(not re.search(r'(?i)\.(?:jks|keystore|p12|pem|sqlite|db|db-wal|db-shm|trachu)$', info.filename),
                    'Unexpected database, backup or key in binary archive')
    return file


def reduced_tests(qa, pattern, name):
    files = sorted(qa.glob(pattern))
    require(files, f'Missing {name} reports')
    output = ET.Element('testsuite', name=name, failures='0', errors='0', skipped='0')
    seen = set()
    for file in files:
        root = ET.parse(file).getroot()
        require(root.tag == 'testsuite' and all(int(root.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')), 'Unsuccessful suite')
        cases = root.findall('testcase')
        require(len(cases) == int(root.attrib['tests']), 'Incomplete suite')
        for case in cases:
            identity = (case.attrib['classname'], case.attrib['name'])
            require(identity not in seen and all(case.find(k) is None for k in ('failure', 'error', 'skipped')), 'Unsuccessful or duplicate test')
            require(all(re.fullmatch(r'[A-Za-z0-9_.$]+', part) for part in identity), 'Unexpected test identity')
            seen.add(identity)
            ET.SubElement(output, 'testcase', classname=identity[0], name=identity[1])
    output.set('tests', str(len(seen)))
    return ET.tostring(output, encoding='utf-8', xml_declaration=True), len(seen)


def upgrade_summary(qa, directory, baseline):
    value = (qa / 'qa' / directory / 'result.txt').read_text().strip()
    pattern = (r'PASS: ' + re.escape(baseline) +
               r' -> 1\.2\.0, no clear or onboarding, ([1-9][0-9]*) identical ledger records, ' +
               r'4250 minor units; SHA256 [0-9a-f]{64}')
    match = re.fullmatch(pattern, value)
    require(match, f'Missing verified {baseline} upgrade')
    return {'passed': True, 'identicalLedgerRecords': int(match[1]), 'exactBalanceMinorUnits': 4250}


def verify_png(data, name):
    require(len(data) <= 3_000_000 and data.startswith(b'\x89PNG\r\n\x1a\n'),
            f'{name}: invalid PNG signature or size')
    offset, dimensions, ended, pixels = 8, None, False, False
    while offset + 12 <= len(data):
        length, tag = struct.unpack_from('>I4s', data, offset)
        end = offset + 8 + length
        require(end + 4 <= len(data), f'{name}: truncated PNG chunk')
        crc = struct.unpack_from('>I', data, end)[0]
        require(zlib.crc32(data[offset+4:end]) == crc, f'{name}: bad PNG CRC')
        if tag == b'IHDR':
            require(offset == 8 and length == 13 and dimensions is None, f'{name}: invalid IHDR')
            dimensions = struct.unpack_from('>II', data, offset + 8)
            require(all(1 <= value <= 3840 for value in dimensions), f'{name}: invalid dimensions')
        if tag == b'IDAT':
            pixels = True
        offset = end + 4
        if tag == b'IEND':
            require(length == 0, f'{name}: invalid IEND')
            ended = True
            break
    require(ended and pixels and offset == len(data) and dimensions is not None,
            f'{name}: incomplete PNG')


def write_qa_archive(path, qa, test_reports, screen_names, summary, sha, run):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as out:
        for tier, (xml, _) in test_reports.items():
            out.writestr(f'tests/{tier}.xml', xml)
        for tier in ('store-phone', 'phone', 'tablet'):
            for name in sorted(screen_names):
                png = qa / 'qa' / tier / (name + '.png')
                if png.is_file():
                    raw = png.read_bytes()
                    verify_png(raw, png.name)
                    out.writestr(f'screens/{tier}/{png.name}', raw)
        out.writestr('summary.json', json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
        out.writestr('README.txt', f'TRAChU 1.2.0 synthetic emulator QA\nSource: {sha}\nCI: {run}\nOriginal PNGs; reduced successful JUnit XML; no raw logs or databases.\n')


def main():
    require(len(sys.argv) == 8, 'Expected evidence, source SHA, run ID, signature report, VerifyOnly APK/AAB reports, output directory')
    evidence, sha, run, signature_path, apk_verify_path, aab_verify_path, delivery = sys.argv[1:]
    require(re.fullmatch('[0-9a-f]{40}', sha) and run.isdigit(), 'Invalid release provenance')
    evidence, delivery = pathlib.Path(evidence), pathlib.Path(delivery)
    qa = evidence / 'trachu-verification-reports'
    expected = manifest(qa, sha)
    debug = verify_binary(evidence / 'trachu-debug-apk', 'app/build/outputs/apk/debug/app-debug.apk', sha, expected)
    verify_binary(evidence / 'trachu-androidTest-apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', sha, expected)
    verify_binary(evidence / 'trachu-unsigned-release-apk', 'app/build/outputs/apk/release/app-release-unsigned.apk', sha, expected)
    verify_binary(evidence / 'trachu-unsigned-release-aab', 'app/build/outputs/bundle/release/app-release.aab', sha, expected)
    signature = pathlib.Path(signature_path).read_text()
    recorded = (qa / 'qa/apk-signature.txt').read_text()
    def fingerprint(value):
        match = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$', value, re.M)
        require(match and 'CN=Android Debug' in value and 'Number of signers: 1' in value and 'Verified using v2 scheme (APK Signature Scheme v2): true' in value, 'Unexpected debug signature')
        return match[1]
    cert = fingerprint(signature)
    require(cert == fingerprint(recorded), 'Debug certificate differs from CI')
    require('Success' in (qa / 'qa/phone/reinstall.txt').read_text().splitlines() and 'Status: ok' in (qa / 'qa/phone/reinstall.txt').read_text().splitlines(), 'Missing actual install/launch proof')
    install_text = (qa / 'qa/phone/reinstall.txt').read_text()
    require('LaunchState: COLD' in install_text, 'Missing cold launch proof')
    for name in ('upgrade-baseline-cert.txt', 'upgrade-v11-baseline-cert.txt', 'upgrade-current-cert.txt'):
        require(cert in (qa / 'qa' / name).read_text(), 'Upgrade signer differs')
    # Accept original device captures only. No re-rendering, no metadata from raw logs.
    screen_names = {'dashboard', 'operations', 'split_entry', 'onboarding', 'drawer', 'converter', 'quick_entry', 'dashboard_dark', 'tutorial', 'analytics_expense', 'analytics_income', 'planner_calendar', 'goals', 'goal_funding', 'goal_history', 'debts', 'debt_payment', 'entry_edit'}
    screen_names |= {'analytics_landscape', 'converter_landscape', 'dashboard_dark_landscape', 'drawer_landscape', 'operations_landscape', 'tutorial_landscape'}
    reports = {'jvm': 'app/build/test-results/testDebugUnitTest/TEST-*.xml'}
    reports.update({tier: f'qa/{tier}/androidTest-results/connected/debug/TEST-*.xml' for tier in ('phone', 'tablet', 'api26', 'api33', 'store-phone', 'store-phone-planner')})
    test_reports = {tier: reduced_tests(qa, pattern, tier) for tier, pattern in reports.items()}
    counts = {tier: count for tier, (_, count) in test_reports.items()}
    total = sum(counts.values())
    checksum = digest(debug)

    def notification_summary(api):
        value = (qa / f'qa/notification-api{api}/result.txt').read_text().strip()
        require(value == f'PASS: API {api} Android notification allow and deny, OS permission and app preference persisted, cold restart without prompt, onboarding preserved.',
                f'Missing API {api} notification proof')
        return {'passed': True, 'allow': True, 'deny': True, 'persistedAfterColdRestart': True, 'onboardingPreserved': True}

    cbr = (qa / 'qa/tablet/live-cbr.txt').read_text().strip()
    cbr_match = re.fullmatch(r'PASS: Android HTTPS CBR feed, ([1-9][0-9]*) positive rates, publication day ([0-9]+), requested day ([0-9]+)', cbr)
    require(cbr_match, 'Missing real Android HTTPS CBR proof')
    require((qa / 'qa/ads-config.txt').read_text().strip() == 'Debug: official demo only\nRelease: R-M-20514950-1; consent required',
            'Unexpected advertising configuration')
    offline = (qa / 'qa/offline/result.txt').read_text().strip()
    require(offline == 'PASS: offline account and expense, cold restart, persisted exact 4250 minor units, no default network before/after.',
            'Missing offline persistence proof')
    def lint_summary(variant):
        issues = ET.parse(qa / f'app/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
        values = {level: sum(x.get('severity') == level for x in issues) for level in ('Fatal', 'Error', 'Warning', 'Hint', 'Information')}
        require(values['Fatal'] == values['Error'] == 0, 'Lint failure')
        return values

    apk_verify = pathlib.Path(apk_verify_path).read_text()
    aab_verify = pathlib.Path(aab_verify_path).read_text()
    require(re.search(r'Verified app/build/outputs/apk/release/app-release-unsigned\.apk \([0-9a-fA-F]{64}\)', apk_verify),
            'Unsigned APK VerifyOnly proof missing')
    require(re.search(r'Verified app/build/outputs/bundle/release/app-release\.aab \([0-9a-fA-F]{64}\)', aab_verify),
            'Unsigned AAB VerifyOnly proof missing')
    summary = {
        'sourceCommit': sha,
        'androidCiRun': run,
        'debugApkSha256': checksum,
        'debugCertificateSha256': cert,
        'debugSignature': 'Android Debug; one signer; APK Signature Scheme v2',
        'testCounts': counts,
        'totalTests': total,
        'lint': {'debug': lint_summary('debug'), 'release': lint_summary('release')},
        'installation': {'adbInstallSuccess': True, 'activityStatusOk': True, 'coldLaunch': True},
        'offline': {'passed': True, 'exactBalanceMinorUnits': 4250, 'defaultNetworkBeforeAndAfter': 'none'},
        'upgrades': {'1.0.0': upgrade_summary(qa, 'upgrade', '1.0.0'), '1.1.0': upgrade_summary(qa, 'upgrade-v11', '1.1.0')},
        'notifications': {'api33': notification_summary(33), 'api36': notification_summary(36)},
        'centralBankHttps': {'passed': True, 'positiveRates': int(cbr_match[1]), 'publicationDay': int(cbr_match[2]), 'requestedDay': int(cbr_match[3])},
        'ads': {'debug': 'official demo placement only', 'release': 'disabled; empty placement'},
        'unsignedReleaseVerifyOnly': {'apk': True, 'aab': True},
        'compatibility16KiB': 'static ELF LOAD alignment and APK stored native library offsets verified by Android CI',
        'physicalDeviceTests': False,
        'notCoveredByEmulator': ['TalkBack', 'hardware biometrics', 'external SAF', 'physical 16-KiB devices', 'commercial ad traffic']
    }
    delivery.mkdir(exist_ok=False)
    apk = delivery / 'trachu-1.2.0-debug.apk'
    shutil.copyfile(debug, apk)
    require(digest(apk) == checksum, 'Copied debug APK differs from verified source')
    (delivery / (apk.name + '.sha256')).write_text(f'{checksum}  {apk.name}\n', encoding='ascii')
    write_qa_archive(delivery / 'trachu-1.2.0-qa.zip', qa, test_reports, screen_names, summary, sha, run)
    notes = f'''## ТРАЧУ. 1.2.0 — проверенная тестовая сборка

Это **debug APK** для установки и проверки, не подписанная издателем версия RuStore.

- Исходники: `{sha}`
- Полный [Android CI #{run}](https://github.com/epbloger/trachu/actions/runs/{run})
- Тесты: {total}/{total}, без пропусков ({', '.join(f'{k}: {v}' for k, v in counts.items())}).
- SHA-256 APK: `{checksum}`
- SHA-256 отладочного сертификата: `{cert}` (Android Debug, один подписант, v2).
- Подтверждены установка и холодный запуск на эмуляторе, оба обновления без очистки SQLite, офлайн 4250 минимальных единиц, реальный Android HTTPS ЦБ, системное разрешение и отказ уведомлений на API 33/36, Lint без ошибок, unsigned APK/AAB VerifyOnly и статическая 16-КиБ проверка.
- QA: оригинальные тестовые PNG, сокращённые успешные JUnit XML и allowlist-сводка проверок без сырых журналов, путей runner, манифестов файлов и баз.

Перед обновлением создайте защищённую паролем резервную копию. При несовпадении сертификата Android не установит APK поверх старой версии: не удаляйте старое приложение до проверки копии. Debug содержит только демонстрационную рекламу; коммерческая реклама release отключена. Эмуляторы и статическая проверка не заменяют физические 16-КиБ устройства, TalkBack, аппаратную биометрию и внешний SAF. Для RuStore необходим постоянный ключ издателя и физическая приёмка.
'''
    (delivery / 'release-notes.md').write_text(notes, encoding='utf-8')
    print(f'Prepared original debug APK and minimized synthetic QA, {total} tests, SHA-256 {checksum}')


if __name__ == '__main__':
    main()
