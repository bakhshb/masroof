#!/usr/bin/env bash
# CI emulator only. Each stateful suite owns its device and keeps all phases together.
set -euo pipefail
suite="${1:-}"
phase="${2:-}"
case "$suite:$phase" in
  production:test) ;;
  *) echo "Usage: $0 production test" >&2; exit 2 ;;
esac
test "$#" -eq 2

# Timed log groups make install vs instrumentation costs visible in the job log.
run_step() {
  local label="$1" started status=0
  shift
  started=$SECONDS
  echo "::group::$label"
  "$@" || status=$?
  echo "::endgroup::"
  printf '%s: %ss (exit %s)\n' "$label" "$((SECONDS - started))" "$status"
  return "$status"
}
# Keep install failures as artifacts too, before the journey script creates its reports.
mkdir -p app/build/reports/device-ci
exec > >(tee app/build/reports/device-ci/lifecycle.log) 2>&1

case "$suite" in
  production)
    # Use the APKs already built before boot rather than invoking Gradle again.
    run_step 'Install production APK' adb install -r -t app/build/outputs/apk/debug/app-debug.apk
    run_step 'Install production test APK' adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    run_step 'Production process-death journeys' bash scripts/m13-process-death.sh
    ;;
esac
