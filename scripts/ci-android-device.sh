#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/../MiaoYanAndroid"

./gradlew :app:connectedDebugAndroidTest :app:assembleLocalRelease
adb install -r app/build/outputs/apk/localRelease/app-localRelease.apk
adb logcat -c
adb shell am start -W -n com.tw93.miaoyan.android/.MainActivity
sleep 5
adb shell pidof com.tw93.miaoyan.android

crash_log="$(adb logcat -d -s AndroidRuntime:E)"
if [[ "$crash_log" == *"FATAL EXCEPTION"* ]]; then
    printf '%s\n' "$crash_log" >&2
    exit 1
fi
