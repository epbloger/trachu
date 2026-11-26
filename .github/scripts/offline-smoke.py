"""Verify the exact previously built APK with no default network (API 36)."""
import json
import os
import pathlib
import re
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

package = 'ru.epbloger.trachu'
out = pathlib.Path(os.environ.get('TRACHU_OFFLINE_QA_DIR', 'offline-qa'))
out.mkdir(exist_ok=True)

def adb(*args, binary=False, check=True):
    result = subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=check, timeout=45)
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/trachu-qa.xml')
    data = adb('exec-out', 'cat', '/sdcard/trachu-qa.xml')
    (out / 'last-screen.xml').write_text(data, encoding='utf-8')
    return ET.fromstring(data)

def wait_node(predicate, description):
    until = time.monotonic() + 45
    while time.monotonic() < until:
        try:
            node = next((n for n in tree().iter('node') if predicate(n)), None)
            if node is not None:
                return node
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise AssertionError(f'UI element unavailable: {description}')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def text(value):
    return wait_node(lambda n: n.get('text') == value or n.get('content-desc') == value, value)

def enter(value):
    tap(wait_node(lambda n: n.get('class') == 'android.widget.EditText', 'first editable field'))
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', '66')

def wait_closed(value):
    until = time.monotonic() + 30
    while time.monotonic() < until:
        try:
            if not any(n.get('text') == value or n.get('content-desc') == value for n in tree().iter('node')):
                return
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise AssertionError(f'Form still open: {value}')

def assert_offline(label):
    for _ in range(30):
        state = adb('shell', 'dumpsys', 'connectivity')
        if re.search(r'Active default network:\s*(none|null)\s*$', state, re.I | re.M):
            (out / f'connectivity-{label}.txt').write_text(state, encoding='utf-8')
            assert adb('shell', 'settings', 'get', 'global', 'airplane_mode_on').strip() == '1'
            return
        time.sleep(1)
    (out / f'connectivity-{label}.txt').write_text(state, encoding='utf-8')
    raise AssertionError('Emulator still has a default network')

def start(quick=False):
    args = ['shell', 'am', 'start', '-W', '-n', f'{package}/.MainActivity']
    if quick:
        args += ['--es', 'quick', 'EXPENSE']
    result = adb(*args)
    assert 'Status: ok' in result, result

try:
    adb('install', '-r', sys.argv[1])
    assert 'Success' in adb('shell', 'pm', 'clear', package)
    adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    assert_offline('before')
    start(quick=True)
    text('Новый счёт')
    enter('OfflineQA')
    tap(text('Сохранить'))
    wait_closed('Новый счёт')
    wait_node(lambda n: 'OfflineQA' in (n.get('text','') + n.get('content-desc','')), 'saved account')
    adb('shell', 'am', 'force-stop', package)
    start(quick=True)
    text('Новая операция')
    enter('42.50')
    tap(text('Сохранить'))
    wait_closed('Новая операция')
    adb('shell', 'am', 'force-stop', package)
    start()
    text('Обзор')
    assert_offline('after')
    (out / 'offline-dashboard.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    adb('shell', 'am', 'force-stop', package)
    for name in ['trachu.db', 'trachu.db-wal', 'trachu.db-shm']:
        data = adb('exec-out', 'run-as', package, 'cat', 'databases/' + name, binary=True, check=False)
        if data:
            (out / name).write_bytes(data)
    assert (out / 'trachu.db').is_file(), 'Database not found after cold restart'
    with sqlite3.connect(out / 'trachu.db') as db:
        rows = [(kind, json.loads(payload)) for kind, payload in db.execute("SELECT kind,payload FROM ledger_records WHERE kind IN ('account','entry')")]
    accounts = [a for kind, a in rows if kind == 'account' and a.get('name') == 'OfflineQA']
    assert len(accounts) == 1, accounts
    entries = [e for kind, e in rows if kind == 'entry' and e.get('accountId') == accounts[0]['id']]
    assert len(entries) == 1 and entries[0]['kind'] == 'EXPENSE' and entries[0]['minor'] == 4250, entries
    assert entries[0].get('status', 'POSTED') == 'POSTED', entries
    (out / 'result.txt').write_text('PASS: offline account and expense, cold restart, persisted exact 4250 minor units, no default network before/after.\n')
    print((out / 'result.txt').read_text())
finally:
    for name in ['trachu.db', 'trachu.db-wal', 'trachu.db-shm']:
        (out / name).unlink(missing_ok=True)
    adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable', check=False)
    adb('shell', 'svc', 'wifi', 'enable', check=False)
    adb('shell', 'svc', 'data', 'enable', check=False)
