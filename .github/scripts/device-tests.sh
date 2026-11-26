#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "api26" ]]; then
  mkdir -p qa/api26
  status=0
  gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.epbloger.trachu.data.LedgerStoreTest,ru.epbloger.trachu.security.DeviceSecurityTest,ru.epbloger.trachu.data.PdfReportTest || status=1
  cp -a app/build/outputs/androidTest-results qa/api26/ || true
  exit "$status"
fi
mkdir -p qa/phone qa/tablet
status=0
trap 'adb shell svc wifi enable || true; adb shell svc data enable || true' EXIT
adb shell svc wifi disable
adb shell svc data disable
adb shell dumpsys connectivity > qa/phone/connectivity-before-test.txt
gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/phone/ || true
cp -a app/build/outputs/androidTest-results qa/phone/ || true
if adb shell pm path ru.epbloger.trachu | grep -q '^package:'; then
  adb shell run-as ru.epbloger.trachu test -f databases/trachu.db || status=1
  adb install -r app/build/outputs/apk/debug/app-debug.apk | tee qa/phone/reinstall.txt
  adb shell run-as ru.epbloger.trachu test -f databases/trachu.db || status=1
  adb shell am force-stop ru.epbloger.trachu
  adb shell am start -W -n ru.epbloger.trachu/.MainActivity | tee -a qa/phone/reinstall.txt
  adb shell pm clear ru.epbloger.trachu
else
  status=1
fi
adb shell svc wifi enable
adb shell svc data enable
adb shell wm size 1280x800
adb shell wm density 160
adb shell settings put system font_scale 1.3
gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/tablet/ || true
cp -a app/build/outputs/androidTest-results qa/tablet/ || true
if [[ "$status" == 0 ]]; then
  adb shell wm size reset
  adb shell wm density reset
  adb shell settings put system font_scale 1.0
  TRACHU_OFFLINE_QA_DIR=qa/offline python3 .github/scripts/offline-smoke.py app/build/outputs/apk/debug/app-debug.apk || status=1
fi
exit "$status"
