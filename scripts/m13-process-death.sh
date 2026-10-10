#!/usr/bin/env bash
# Emulator-only M13 process death. Run from the GitHub Actions runner, not from
# inside the app process. clearPackageData stays false because each journey is
# two am instrument invocations that share the app database.
set -euo pipefail

APP_ID="com.baraa.masroof"
RUNNER="${APP_ID}.test/com.baraa.masroof.instrumentation.MasroofAndroidTestRunner"
CLASS="com.baraa.masroof.instrumentation.processdeath.M13ProcessDeathJourneyTest"

adb wait-for-device
echo "M13 emulator API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

instrument() {
  local method="$1"
  shift
  local extra="${*:-}"
  local output
  output=$(adb shell "am instrument -w -r ${extra} -e class '${CLASS}#${method}' ${RUNNER}")
  printf '%s\n' "$output"
  case "$output" in
    *"OK (1 test)"*) ;;
    *)
      echo "M13 instrumentation failed for ${method}" >&2
      exit 1
      ;;
  esac
}

grant_sms() {
  adb shell pm grant "$APP_ID" android.permission.RECEIVE_SMS
  adb shell pm grant "$APP_ID" android.permission.READ_SMS
}

journey() {
  local arm="$1"
  local resume="$2"
  echo "=== M13 ${arm} -> am force-stop -> ${resume} ==="
  adb shell pm clear "$APP_ID"
  grant_sms
  instrument "$arm"
  # Process death from this runner. am force-stop inside the app process would
  # kill the instrumentation result instead of leaving a durable checkpoint.
  adb shell am force-stop "$APP_ID"
  instrument "$resume" -e m13Resume true
}

journey armAfterCapture resumeAfterCapture
journey armAfterParsed resumeAfterParsed
journey armAfterReconciled resumeAfterReconciled
journey armAfterReview resumeAfterReview
journey armRequiredReview resumeRequiredReview
journey armUserNonFinancial resumeUserNonFinancial
journey armLiveRetry resumeLiveRetry

echo "M13 process-death journeys passed on API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
