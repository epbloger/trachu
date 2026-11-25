#!/usr/bin/env bash
set -eu
mkdir -p qa/phone qa/tablet
status=0
gradle --no-daemon --stacktrace connectedDebugAndroidTest || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/phone/ || true
cp -a app/build/outputs/androidTest-results qa/phone/ || true
adb shell pm clear ru.epbloger.trachu
adb shell wm size 1280x800
adb shell wm density 160
adb shell settings put system font_scale 1.3
gradle --no-daemon --stacktrace connectedDebugAndroidTest || status=1
adb pull /sdcard/Android/data/ru.epbloger.trachu/files/qa/. qa/tablet/ || true
cp -a app/build/outputs/androidTest-results qa/tablet/ || true
exit "$status"
