#!/usr/bin/env bash
# Real SQLite restoration barriers on the emulator, after the process-death suite.
set -euo pipefail
output=$(adb shell am instrument -w -r -e class com.baraa.masroof.instrumentation.RestoreConcurrencyTest com.baraa.masroof.test/com.baraa.masroof.instrumentation.MasroofAndroidTestRunner)
printf '%s\n' "$output"
case "$output" in
  *"OK (2 tests)"*) ;;
  *) echo "Restore concurrency regressions failed" >&2; exit 1 ;;
esac
echo "Restore concurrency barriers passed on API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
