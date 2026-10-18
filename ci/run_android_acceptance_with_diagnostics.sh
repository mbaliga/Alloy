#!/usr/bin/env bash
set -euo pipefail

report_dir="${ALLOY_ANDROID_ACCEPTANCE_REPORT_DIR:-app/build/reports/androidTests/connected}"
mkdir -p "$report_dir"
log_file="$report_dir/gradle-acceptance.log"

if gradle :app:connectedReleaseAndroidTest -PalloyCiDebugSign=true --no-daemon --stacktrace >"$log_file" 2>&1; then
  status=0
else
  status=$?
fi

# Keep dependency-resolution and Java compilation failures as artifacts too;
# test-result XML/HTML reports do not exist when Gradle fails before execution.
cat "$log_file"
exit "$status"
