#!/usr/bin/env bash
set -eu
if [[ "${1:-}" == "api26" ]]; then
  mkdir -p qa/api26
  status=0
  gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.epbloger.trachu.data.LedgerStoreTest,ru.epbloger.trachu.security.DeviceSecurityTest || status=1
  cp -a app/build/outputs/androidTest-results qa/api26/ || true
  exit "$status"
fi
mkdir -p qa/phone qa/tablet
status=0
gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/phone/ || true
cp -a app/build/outputs/androidTest-results qa/phone/ || true
if adb shell pm path ru.epbloger.trachu | grep -q '^package:'; then
  adb shell pm clear ru.epbloger.trachu
fi
adb shell wm size 1280x800
adb shell wm density 160
adb shell settings put system font_scale 1.3
gradle --no-daemon --stacktrace connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/tablet/ || true
cp -a app/build/outputs/androidTest-results qa/tablet/ || true
exit "$status"
