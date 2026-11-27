#!/usr/bin/env bash
set -euo pipefail
mkdir -p store-media/phone store-media/tablet
classes=ru.epbloger.trachu.ui.NavigationTest,ru.epbloger.trachu.ui.PlannerUxTest
run_capture() {
  local out="$1"
  gradle --no-daemon connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$classes" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
  adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. "$out/"
  python3 - "$out" <<'PY'
from pathlib import Path
import sys, struct
p=Path(sys.argv[1])
for f in p.iterdir():
    if f.suffix.lower()!='.png':
        f.unlink()
images=list(p.glob('*.png'))
assert len(images)>=3, 'Insufficient real device captures'
for f in images:
    with f.open('rb') as s:
        header=s.read(24)
    assert header[:8]==b'\x89PNG\r\n\x1a\n', f
    w,h=struct.unpack('>II',header[16:24])
    assert min(w,h)>=320, (f,w,h)
    print(f,w,h)
PY
}
adb shell cmd connectivity airplane-mode disable
adb shell svc wifi enable
adb shell svc data enable
run_capture store-media/phone
adb shell pm clear ru.epbloger.trachu
adb shell wm size 1280x800
adb shell wm density 160
adb shell settings put system font_scale 1.3
run_capture store-media/tablet
cp store/rustore/trachu-icon-512.png store-media/trachu-icon-512.png
printf '%s\n' 'caa5fe0724282d3163b22610a847e24136e19bf6' > store-media/source-commit.txt
