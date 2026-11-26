"""Package only reviewed, validated 1.2.0 debug delivery evidence.

Run after the full validator. Never include raw logcat, connectivity dumps,
database exports, system-out, test APKs or unsigned release binaries in public QA.
"""
import hashlib
import pathlib
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile


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


def safe_text(value):
    require(not re.search(r'(?i)(?:[a-z]:\\Users\\|/home/runner/|/mnt/data/|BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{35})', value),
            'Potentially sensitive text in public evidence')
    return value


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


def main():
    require(len(sys.argv) == 6, 'Expected evidence, source SHA, run ID, signature report, output directory')
    evidence, sha, run, signature_path, delivery = sys.argv[1:]
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
    for name in ('upgrade-baseline-cert.txt', 'upgrade-v11-baseline-cert.txt', 'upgrade-current-cert.txt'):
        require(cert in (qa / 'qa' / name).read_text(), 'Upgrade signer differs')
    # Accept original device captures only. No re-rendering, no metadata from raw logs.
    screen_names = {'dashboard', 'operations', 'split_entry', 'onboarding', 'drawer', 'converter', 'quick_entry', 'dashboard_dark', 'tutorial', 'analytics_expense', 'analytics_income', 'planner_calendar', 'goals', 'goal_funding', 'goal_history', 'debts', 'debt_payment', 'entry_edit'}
    screen_names |= {'analytics_landscape', 'converter_landscape', 'dashboard_dark_landscape', 'drawer_landscape', 'operations_landscape', 'tutorial_landscape'}
    reports = {'jvm': 'app/build/test-results/testDebugUnitTest/TEST-*.xml'}
    reports.update({tier: f'qa/{tier}/androidTest-results/connected/debug/TEST-*.xml' for tier in ('phone', 'tablet', 'api26', 'api33', 'store-phone', 'store-phone-planner')})
    counts = {}
    delivery.mkdir(exist_ok=False)
    apk = delivery / 'trachu-1.2.0-debug.apk'
    shutil.copyfile(debug, apk)
    checksum = digest(apk)
    (delivery / (apk.name + '.sha256')).write_text(f'{checksum}  {apk.name}\n', encoding='ascii')
    with zipfile.ZipFile(delivery / 'trachu-1.2.0-qa.zip', 'w', compression=zipfile.ZIP_DEFLATED) as out:
        for tier, pattern in reports.items():
            xml, counts[tier] = reduced_tests(qa, pattern, tier)
            out.writestr(f'tests/{tier}.xml', xml)
        for tier in ('store-phone', 'phone', 'tablet'):
            for name in sorted(screen_names):
                png = qa / 'qa' / tier / (name + '.png')
                if png.is_file():
                    raw = png.read_bytes()
                    require(raw.startswith(b'\x89PNG\r\n\x1a\n'), 'Not an original PNG')
                    out.writestr(f'screens/{tier}/{png.name}', raw)
        proofs = ('source-commit.txt', 'SHA256SUMS', 'ads-config.txt', 'apk-signature.txt', 'current-evidence.txt',
                  'phone/reinstall.txt', 'offline/result.txt', 'upgrade/result.txt', 'upgrade-v11/result.txt',
                  'notification-api33/result.txt', 'notification-api36/result.txt', 'tablet/live-cbr.txt',
                  'signing-verify-only.txt')
        for name in proofs:
            value = (qa / 'qa' / name).read_text(encoding='utf-8-sig')
            out.writestr('proof/' + name, safe_text(value))
        for variant in ('debug', 'release'):
            issues = ET.parse(qa / f'app/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
            severities = {level: sum(x.get('severity') == level for x in issues) for level in ('Fatal', 'Error', 'Warning', 'Hint', 'Information')}
            require(severities['Fatal'] == severities['Error'] == 0, 'Lint failure')
            out.writestr(f'proof/lint-{variant}.txt', '\n'.join(f'{k}: {v}' for k, v in severities.items()) + '\n')
        out.writestr('README.txt', f'TRAChU 1.2.0 synthetic emulator QA\nSource: {sha}\nCI: {run}\nOriginal PNGs; reduced successful JUnit XML; no raw logs or databases.\n')
    total = sum(counts.values())
    notes = f'''## ТРАЧУ. 1.2.0 — проверенная тестовая сборка

Это **debug APK** для установки и проверки, не подписанная издателем версия RuStore.

- Исходники: `{sha}`
- Полный [Android CI #{run}](https://github.com/epbloger/trachu/actions/runs/{run})
- Тесты: {total}/{total}, без пропусков ({', '.join(f'{k}: {v}' for k, v in counts.items())}).
- SHA-256 APK: `{checksum}`
- SHA-256 отладочного сертификата: `{cert}` (Android Debug, один подписант, v2).
- Подтверждены установка и холодный запуск на эмуляторе, оба обновления без очистки SQLite, офлайн 4250 минимальных единиц, реальный Android HTTPS ЦБ, системное разрешение и отказ уведомлений на API 33/36, Lint без ошибок, unsigned APK/AAB VerifyOnly и статическая 16-КиБ проверка.
- QA: оригинальные тестовые PNG, сокращённые успешные JUnit XML и отдельные доказательства без сырых журналов и баз.

Перед обновлением создайте защищённую паролем резервную копию. При несовпадении сертификата Android не установит APK поверх старой версии: не удаляйте старое приложение до проверки копии. Debug содержит только демонстрационную рекламу; коммерческая реклама release отключена. Эмуляторы и статическая проверка не заменяют физические 16-КиБ устройства, TalkBack, аппаратную биометрию и внешний SAF. Для RuStore необходим постоянный ключ издателя и физическая приёмка.
'''
    (delivery / 'release-notes.md').write_text(notes, encoding='utf-8')
    print(f'Prepared original debug APK and minimized synthetic QA, {total} tests, SHA-256 {checksum}')


if __name__ == '__main__':
    main()
