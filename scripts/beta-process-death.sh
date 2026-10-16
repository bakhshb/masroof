#!/usr/bin/env bash
# Isolated Beta test APK only; no production database or real device inbox.
set -euo pipefail
APP_ID="com.baraa.masroof.lab.beta.test"
RUNNER="${APP_ID}/androidx.test.runner.AndroidJUnitRunner"
CLASS="com.baraa.masroof.lab.beta.processdeath.BetaHistoryProcessDeathTest"
instrument() {
  local output
  output=$(adb shell am instrument -w -r -e betaResume "$2" -e class "${CLASS}#$1" "$RUNNER")
  printf '%s\n' "$output"
  case "$output" in *"OK (1 test)"*) ;; *) exit 1 ;; esac
}
journey() {
  adb shell pm clear "$APP_ID"
  instrument "$1" false
  adb shell am force-stop "$APP_ID"
  instrument "$2" true
}
# Permission reset happens before instrumentation: revoking its own UID would kill a test.
adb shell pm clear "$APP_ID"
adb shell pm revoke "$APP_ID" android.permission.READ_SMS || true
adb shell pm clear-permission-flags "$APP_ID" android.permission.READ_SMS user-set user-fixed
mkdir -p beta-permission-diagnostics
adb shell dumpsys package "$APP_ID" | grep 'android.permission.READ_SMS' > beta-permission-diagnostics/permission-state-before.txt || true
permission_status=0
permission_output=$(adb shell am instrument -w -r -e class "com.baraa.masroof.lab.beta.processdeath.BetaOnboardingPermissionTest" "$RUNNER") || permission_status=$?
printf '%s\n' "$permission_output"
adb pull "/sdcard/Android/data/$APP_ID/files/beta-permission-diagnostics/." beta-permission-diagnostics || true
if [ "$permission_status" -ne 0 ]; then exit "$permission_status"; fi
case "$permission_output" in *"OK (1 test)"*) ;; *) exit 1 ;; esac

journey armDuringComparison resumeDuringComparison
journey armAfterComparisonBeforeCheckpoint resumeAfterComparisonBeforeCheckpoint

# The ledger phases are excluded from emulator-smoke and run exactly once here.
CLASS="com.baraa.masroof.lab.beta.processdeath.BetaLedgerProcessDeathTest"
journey armBeforeCommit resumeBeforeCommit
journey armAfterCommit resumeAfterCommit
echo "Beta permission dialog, evidence and durable-ledger process-death journeys passed"
