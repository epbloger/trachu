"""Exercise the actual notification permission dialog on a disposable CI emulator.

No permission is granted/revoked by adb: both decisions are made through the
system PermissionController UI. Only a small synthetic verdict is retained.
"""
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PACKAGE = 'ru.epbloger.trachu'
PERMISSION = 'android.permission.POST_NOTIFICATIONS'
PREFS = 'files/datastore/trachu_preferences.preferences_pb'
UI_DUMP = '/sdcard/trachu-notification-smoke.xml'
CONTROLLERS = {'com.android.permissioncontroller', 'com.google.android.permissioncontroller'}
APP_PROMPT = 'Включить напоминания?'
ONBOARDING = 'Начнём с вашего счёта'
KEYS = {b'notifications_enabled_v1', b'onboarding_completed_v1'}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def adb(*args, binary=False, check=True):
    result = subprocess.run(['adb', *args], capture_output=True, check=check, timeout=45)
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')


def wait_for(description, probe, timeout=45):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            result = probe()
            if result:
                return result
        except (ET.ParseError, subprocess.SubprocessError, ValueError):
            # Do not print raw UI dumps or preferences, including on failure.
            pass
        time.sleep(.5)
    raise AssertionError(f'Timed out: {description}')


def tree():
    # Never reuse a previous dump if the accessibility window is transitioning.
    adb('shell', 'rm', '-f', UI_DUMP)
    adb('shell', 'uiautomator', 'dump', UI_DUMP)
    try:
        return ET.fromstring(adb('exec-out', 'cat', UI_DUMP, binary=True))
    finally:
        adb('shell', 'rm', '-f', UI_DUMP, check=False)


def label(node, value):
    return node.get('text') == value or node.get('content-desc') == value


def app_button(root):
    return next((node for node in root.iter('node')
                 if node.get('package') == PACKAGE and label(node, 'Разрешить')), None)


def permission_button(root, allow):
    suffix = ':id/permission_allow_button' if allow else ':id/permission_deny_button'
    return next((node for node in root.iter('node')
                 if node.get('package') in CONTROLLERS
                 and node.get('resource-id', '').endswith(suffix)
                 and node.get('enabled') == 'true'), None)


def tap(node):
    bounds = tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    require(len(bounds) == 4, 'Button has no valid bounds')
    x1, y1, x2, y2 = bounds
    require(x2 > x1 and y2 > y1, 'Button is not visible')
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def permission_state():
    # Inspect the installed app's actual runtime permission, not the app switch.
    dump = adb('shell', 'dumpsys', 'package', PACKAGE)
    match = re.search(r'^\s*' + re.escape(PERMISSION)
                      + r':\s*granted=(true|false),\s*flags=\[([^\]]*)\]', dump, re.M)
    require(match is not None, 'Runtime notification permission not found')
    return match[1] == 'true', 'USER_SET' in re.findall(r'[A-Z_]+', match[2])


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError('Truncated protobuf varint')
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError('Oversized protobuf varint')


def fields(data):
    """Read standard protobuf wire fields without an extra CI dependency."""
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError('Invalid protobuf field')
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire in (1, 2, 5):
            if wire == 2:
                length, offset = varint(data, offset)
            else:
                length = 8 if wire == 1 else 4
            end = offset + length
            if end > len(data):
                raise ValueError('Truncated protobuf field')
            value, offset = data[offset:end], end
        else:
            raise ValueError('Unsupported protobuf wire type')
        yield number, wire, value


def preferences(data):
    # AndroidX Preferences DataStore: PreferenceMap.preferences = field 1;
    # each map entry has key = 1 and Value = 2; Value.boolean = field 1.
    result = {}
    for number, wire, entry in fields(data):
        if (number, wire) != (1, 2):
            continue
        parts = {(n, w): v for n, w, v in fields(entry)}
        key = parts.get((1, 2))
        if key not in KEYS:
            continue
        if key in result or (2, 2) not in parts:
            raise ValueError('Invalid preference entry')
        value = list(fields(parts[2, 2]))
        if len(value) != 1 or value[0][:2] != (1, 0) or value[0][2] not in (0, 1):
            raise ValueError('Expected a boolean preference')
        result[key] = bool(value[0][2])
    return result


def expected_preferences(allow):
    data = adb('exec-out', 'run-as', PACKAGE, 'cat', PREFS, binary=True)
    return preferences(data) == {b'notifications_enabled_v1': allow,
                                 b'onboarding_completed_v1': False}


def uninstall():
    if adb('shell', 'pm', 'path', PACKAGE, check=False).strip():
        require('Success' in adb('uninstall', PACKAGE), 'Could not uninstall test app')
    require(not adb('shell', 'pm', 'path', PACKAGE, check=False).strip(),
            'Test app still installed')


def start():
    require('Status: ok' in adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity'),
            'MainActivity did not start')


def still_onboarding():
    nodes = list(tree().iter('node'))
    own = [n for n in nodes if n.get('package') == PACKAGE]
    require(not any(label(n, 'Обзор') for n in own), 'Onboarding was skipped')
    if any(n.get('package') in CONTROLLERS for n in nodes):
        return False
    if any(label(n, APP_PROMPT) or label(n, 'Не сейчас') for n in own):
        return False
    return any(label(n, ONBOARDING) for n in own) and any(
        label(n, 'Далее') and n.get('enabled') == 'true' for n in own)


def scenario(apk, allow):
    uninstall()
    require('Success' in adb('install', str(apk)), 'Could not install test APK')
    require('Success' in adb('shell', 'pm', 'clear', PACKAGE), 'Could not clear test app')
    require(permission_state() == (False, False), 'Expected fresh undecided OS permission')
    start()

    def first_prompt():
        root = tree()
        if any(n.get('package') == PACKAGE and label(n, APP_PROMPT) for n in root.iter('node')):
            # ElementTree nodes are falsey if they have no children; return a tuple.
            button = app_button(root)
            return (button,) if button is not None else None

    tap(wait_for('first-launch app permission prompt', first_prompt)[0])

    def system_prompt():
        root = tree()
        # Both buttons must belong to Android PermissionController, not our dialog.
        yes, no = permission_button(root, True), permission_button(root, False)
        if yes is not None and no is not None:
            return (yes if allow else no,)

    tap(wait_for('real Android notification permission dialog', system_prompt)[0])
    wait_for('OS notification permission decision', lambda: permission_state() == (allow, True))
    wait_for('persisted app permission and incomplete onboarding', lambda: expected_preferences(allow))
    wait_for('onboarding visible after system decision', still_onboarding)
    adb('shell', 'am', 'force-stop', PACKAGE)
    require(not adb('shell', 'pidof', PACKAGE, check=False).strip(), 'App process did not stop')
    start()
    # Observe multiple fresh trees to catch a reappearing prompt after state load.
    wait_for('onboarding visible after cold restart without prompt', still_onboarding)
    for _ in range(3):
        require(still_onboarding(), 'Permission prompt returned or onboarding was skipped')
        time.sleep(.5)
    require(permission_state() == (allow, True), 'OS permission changed after cold restart')
    require(expected_preferences(allow), 'App preference changed after cold restart')
    print(f"PASS: {'allow' if allow else 'deny'} via Android permission UI; persisted and restarted")


def main():
    require(len(sys.argv) == 3 and sys.argv[2] in ('33', '36'), 'Usage: notification-smoke.py APK 33|36')
    apk, api = pathlib.Path(sys.argv[1]), sys.argv[2]
    require(apk.is_file(), 'APK missing')
    require(adb('shell', 'getprop', 'ro.build.version.sdk').strip() == api, 'Wrong emulator SDK')
    require(any(adb('shell', 'getprop', prop).strip() == '1'
                for prop in ('ro.kernel.qemu', 'ro.boot.qemu')), 'Only run on a disposable emulator')
    out = pathlib.Path('qa') / f'notification-api{api}'
    out.mkdir(parents=True, exist_ok=True)
    result = out / 'result.txt'
    result.unlink(missing_ok=True)
    try:
        scenario(apk, True)
        scenario(apk, False)
    finally:
        adb('shell', 'rm', '-f', UI_DUMP, check=False)
        uninstall()
    verdict = (f'PASS: API {api} Android notification allow and deny, OS permission and app preference '
               'persisted, cold restart without prompt, onboarding preserved.')
    result.write_text(verdict + '\n', encoding='utf-8')
    print(verdict)


if __name__ == '__main__':
    main()
