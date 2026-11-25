#!/usr/bin/env bash
# Run inside the Android emulator action as one shell so the test exit status
# survives logging and cleanup.
set -euo pipefail

mkdir -p native-instrumentation-diagnostics
adb logcat -c || true
trap 'adb logcat -b all -d -v threadtime > native-instrumentation-diagnostics/logcat.txt 2>&1 || true; adb shell getprop > native-instrumentation-diagnostics/getprop.txt 2>&1 || true; adb shell dumpsys package com.mbaliga.alloy > native-instrumentation-diagnostics/package.txt 2>&1 || true' EXIT

set +e
gradle :app:connectedReleaseAndroidTest -PalloyNativeEngine=true -PalloyCiDebugSign=true -PalloyAdbTimeoutMs=900000 --no-daemon --stacktrace > native-instrumentation-diagnostics/gradle-connected-test.log 2>&1
test_status=$?
set -e
cat native-instrumentation-diagnostics/gradle-connected-test.log
exit "$test_status"
