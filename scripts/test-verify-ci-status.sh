#!/usr/bin/env bash
# Exercises the CI gate used by /release and /nightly.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${ROOT}/scripts/verify-ci-status.sh"
SHA="2fc669e4925223de92cfb29d2d839c6ae3c8a083"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

PASSED=0
FAILED=0

assert_eq() {
  local test_name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  [PASS] $test_name"
    PASSED=$((PASSED + 1))
  else
    echo "  [FAIL] $test_name: expected '$expected', got '$actual'"
    FAILED=$((FAILED + 1))
  fi
}

TEST_TMP=$(mktemp -d)
trap 'rm -rf "$TEST_TMP"' EXIT
mkdir -p "$TEST_TMP/bin"
export PATH="$TEST_TMP/bin:$PATH"
export GITHUB_REPOSITORY="bakhshb/masroof"
export CI_CHECK_WAIT_SECONDS=2
export CI_CHECK_POLL_SECONDS=0

write_checks() {
  local payload="$1"
  printf '%s\n' "$payload" > "$TEST_TMP/checks.json"
}

cat > "$TEST_TMP/bin/gh" << 'EOF'
#!/usr/bin/env bash
if [[ "$1" == "api" && "$2" == *check-runs* ]]; then
  state_file="$(dirname "$0")/../state"
  count=0
  if [[ -f "$state_file" ]]; then
    count=$(cat "$state_file")
  fi
  count=$((count + 1))
  echo "$count" > "$state_file"
  phase_file="$(dirname "$0")/../phase-${count}.json"
  if [[ -f "$phase_file" ]]; then
    cat "$phase_file"
    exit 0
  fi
  cat "$(dirname "$0")/../checks.json"
  exit 0
fi
echo "unexpected gh call: $*" >&2
exit 1
EOF
chmod +x "$TEST_TMP/bin/gh"

run_gate() {
  local log="$1"
  shift
  set +e
  GITHUB_REPOSITORY="bakhshb/masroof" \
    CI_CHECK_WAIT_SECONDS="${CI_CHECK_WAIT_SECONDS}" \
    CI_CHECK_POLL_SECONDS="${CI_CHECK_POLL_SECONDS}" \
    "$SCRIPT" "$SHA" >"$log" 2>&1
  echo $?
  set -e
}

echo "=== verify-ci-status ==="

write_checks "$(cat <<EOF
{"check_runs":[
  {"name":"unit-test","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://unit"},
  {"name":"static-analysis","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://static"}
]}
EOF
)"
rm -f "$TEST_TMP/state"
code=$(run_gate "$TEST_TMP/success.log")
assert_eq "green checks pass immediately" "0" "$code"

write_checks "$(cat <<EOF
{"check_runs":[
  {"name":"unit-test","status":"completed","conclusion":"failure","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://unit"},
  {"name":"static-analysis","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://static"}
]}
EOF
)"
rm -f "$TEST_TMP/state"
code=$(run_gate "$TEST_TMP/failed.log")
assert_eq "failed check blocks publish" "1" "$code"
grep -q "concluded with 'failure'" "$TEST_TMP/failed.log"
echo "  [PASS] failed check names the conclusion"

cat > "$TEST_TMP/phase-1.json" <<EOF
{"check_runs":[
  {"name":"unit-test","status":"in_progress","conclusion":null,"started_at":"${NOW}","completed_at":null,"html_url":"http://unit"},
  {"name":"static-analysis","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://static"}
]}
EOF
cat > "$TEST_TMP/phase-2.json" <<EOF
{"check_runs":[
  {"name":"unit-test","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://unit"},
  {"name":"static-analysis","status":"completed","conclusion":"success","started_at":"${NOW}","completed_at":"${NOW}","html_url":"http://static"}
]}
EOF
rm -f "$TEST_TMP/state"
code=$(run_gate "$TEST_TMP/wait.log")
assert_eq "in-progress check is waited on, then accepted" "0" "$code"
grep -q "still in_progress" "$TEST_TMP/wait.log"
echo "  [PASS] wait log mentions the running check"

rm -f "$TEST_TMP"/phase-*.json
write_checks "$(cat <<EOF
{"check_runs":[
  {"name":"unit-test","status":"in_progress","conclusion":null,"started_at":"${NOW}","completed_at":null,"html_url":"http://unit"}
]}
EOF
)"
rm -f "$TEST_TMP/state"
CI_CHECK_WAIT_SECONDS=0
code=$(run_gate "$TEST_TMP/timeout.log")
CI_CHECK_WAIT_SECONDS=2
assert_eq "still-running check times out" "1" "$code"
grep -q "Timed out" "$TEST_TMP/timeout.log"
echo "  [PASS] timeout is reported"

echo "=== ${PASSED} passed, ${FAILED} failed ==="
if [[ "$FAILED" -gt 0 ]]; then
  exit 1
fi
