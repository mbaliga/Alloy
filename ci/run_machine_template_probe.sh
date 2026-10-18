#!/usr/bin/env bash
# Run the native Bambu template probe independently of the broad test suite.
set -euo pipefail

output_dir=path-b-template-probe
output_dir=${ALLOY_TEMPLATE_PROBE_OUTPUT_DIR:-$output_dir}
device_output=/sdcard/Android/data/com.mbaliga.alloy/files/alloy-bambu-template-probe
finalized=0

collect_diagnostics() {
  mkdir -p "$output_dir/rendered"
  adb logcat -b all -d -v threadtime > "$output_dir/logcat-tail.txt" 2>&1 \
    || printf 'adb logcat failed with exit code %s\n' "$?" >> "$output_dir/logcat-tail.txt"
  adb pull "$device_output/." "$output_dir/rendered/" >> "$output_dir/adb-pull.txt" 2>&1
  local pull_status=$?
  printf 'adb pull exit code: %s\n' "$pull_status" >> "$output_dir/adb-pull.txt"
  return 0
}

finish_probe() {
  local original_status=$?
  trap - EXIT
  if [ "$finalized" -eq 1 ]; then
    exit "$original_status"
  fi
  if [ "$original_status" -eq 0 ]; then
    original_status=1
  fi
  set +e
  collect_diagnostics
  python3 ci/machine_template_probe_attempt.py "$output_dir" failed \
    --exit-code "$original_status" >> "$output_dir/attempt-record.txt" 2>&1
  exit "$original_status"
}

mkdir -p "$output_dir"
trap finish_probe EXIT
python3 ci/machine_template_probe_attempt.py "$output_dir" running
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w -e class com.mbaliga.alloy.BambuMachineTemplateProbeTest \
  com.mbaliga.alloy.test/androidx.test.runner.AndroidJUnitRunner \
  > "$output_dir/instrumentation.txt" 2>&1
if ! grep -Eq '^OK \([0-9]+ tests?\)$' "$output_dir/instrumentation.txt"; then
  exit 1
fi

mkdir -p "$output_dir/rendered"
adb logcat -b all -d -v threadtime > "$output_dir/logcat-tail.txt" 2>&1 || true
adb pull "$device_output/." "$output_dir/rendered/" > "$output_dir/adb-pull.txt" 2>&1
python3 ci/validate_machine_template_probe_output.py "$output_dir/rendered"
python3 ci/machine_template_probe_attempt.py "$output_dir" validated --exit-code 0
finalized=1
