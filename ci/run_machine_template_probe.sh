#!/usr/bin/env bash
# Run the native Bambu template probe independently of the broad test suite.
set -euo pipefail

output_dir=path-b-template-probe
python3 ci/machine_template_probe_attempt.py "$output_dir" running
set +e
adb install -r app/build/outputs/apk/release/app-release.apk
install_status=$?
if [ "$install_status" -eq 0 ]; then
  adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
  install_status=$?
fi
if [ "$install_status" -eq 0 ]; then
  adb shell am instrument -w -e class com.mbaliga.alloy.BambuMachineTemplateProbeTest \
    com.mbaliga.alloy.test/androidx.test.runner.AndroidJUnitRunner \
    > "$output_dir/instrumentation.txt" 2>&1
  install_status=$?
fi
set -e

adb logcat -b all -d -v threadtime 2>/dev/null | tail -n 3000 > "$output_dir/logcat-tail.txt" || true
if [ "$install_status" -ne 0 ]; then
  python3 ci/machine_template_probe_attempt.py "$output_dir" failed --exit-code "$install_status"
  exit "$install_status"
fi
if ! grep -Eq '^OK \([0-9]+ tests?\)$' "$output_dir/instrumentation.txt"; then
  python3 ci/machine_template_probe_attempt.py "$output_dir" failed --exit-code 1
  exit 1
fi

mkdir -p "$output_dir/rendered"
adb pull /sdcard/Android/data/com.mbaliga.alloy/files/alloy-bambu-template-probe/. "$output_dir/rendered/"
python3 ci/validate_machine_template_probe_output.py "$output_dir/rendered"
python3 ci/machine_template_probe_attempt.py "$output_dir" validated --exit-code 0
