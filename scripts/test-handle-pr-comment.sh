#!/usr/bin/env bash
# Tests for PR slash command handling logic.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${ROOT}/scripts/handle-pr-comment.sh"

PASSED=0
FAILED=0

assert_eq() {
  local test_name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  [PASS] $test_name: $actual"
    PASSED=$((PASSED + 1))
  else
    echo "  [FAIL] $test_name: expected '$expected', got '$actual'"
    FAILED=$((FAILED + 1))
  fi
}

echo "=== Running PR Slash Command Test Suite ==="

TEST_TMP=$(mktemp -d)
trap 'rm -rf "$TEST_TMP"' EXIT

mkdir -p "$TEST_TMP/bin"
export PATH="$TEST_TMP/bin:$PATH"

MERGED_TO_MAIN_JSON='{"state":"MERGED","mergedAt":"2026-08-31T00:00:00Z","mergeCommit":{"oid":"sha-merge-12345"},"headRefOid":"abc1234","baseRefName":"main"}'

# Test 1: Invalid command syntax should exit with non-zero
echo "Test 1: Invalid command syntax rejected"
set +e
OUT=$(COMMENT_BODY="/release unknown" PR_NUMBER="10" COMMENT_ID="123" COMMENT_AUTHOR="alice" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Invalid command exit code" "1" "$EXIT_CODE"

# Test 1b: Invalid command reply has real line breaks, not a literal \n
echo "Test 1b: Invalid command reply body is multi-line"
cat > "$TEST_TMP/bin/gh" << EOF
#!/usr/bin/env bash
if [[ "\$1" == "pr" && "\$2" == "comment" ]]; then
  printf '%s' "\$5" > "$TEST_TMP/invalid-body.txt"
fi
EOF
chmod +x "$TEST_TMP/bin/gh"
set +e
COMMENT_BODY="/release unknown" PR_NUMBER="10" COMMENT_ID="123" COMMENT_AUTHOR="alice" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" >/dev/null 2>&1
set -e
assert_eq "Invalid command reply line count" "5" "$(wc -l < "$TEST_TMP/invalid-body.txt" | tr -d ' ')"
if grep -qF '\n' "$TEST_TMP/invalid-body.txt"; then
  assert_eq "Invalid command reply has no literal backslash-n" "absent" "present"
fi

# Test 2: Unauthorized user rejected
echo "Test 2: Unauthorized user rejected"
cat > "$TEST_TMP/bin/gh" << 'EOF'
#!/usr/bin/env bash
if [[ "$1" == "api" && "$2" == *"collaborators"* ]]; then
  echo '{"permission":"read"}'
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="10" COMMENT_ID="123" COMMENT_AUTHOR="stranger" COMMENT_AUTHOR_ASSOCIATION="NONE" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Unauthorized exit code" "1" "$EXIT_CODE"

# Test 2b: Read-only org member rejected
echo "Test 2b: Read-only org member rejected"
cat > "$TEST_TMP/bin/gh" << 'EOF'
#!/usr/bin/env bash
if [[ "$1" == "api" && "$2" == *"collaborators"* ]]; then
  echo '{"permission":"read"}'
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="10" COMMENT_ID="123" COMMENT_AUTHOR="member-user" COMMENT_AUTHOR_ASSOCIATION="MEMBER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Read-only member exit code" "1" "$EXIT_CODE"

# Test 2c: Write collaborator accepted
echo "Test 2c: Write collaborator accepted"
DISPATCH_ARGS_FILE="$TEST_TMP/dispatch.args"
cat > "$TEST_TMP/bin/gh" << EOF
#!/usr/bin/env bash
if [[ "\$1" == "api" && "\$2" == *"collaborators"* ]]; then
  echo '{"permission":"write"}'
  exit 0
elif [[ "\$1" == "pr" && "\$2" == "view" ]]; then
  echo '${MERGED_TO_MAIN_JSON}'
  exit 0
elif [[ "\$1" == "repo" && "\$2" == "view" ]]; then
  shift 2
  query=""
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      -q|--jq)
        query="\$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  json='{"defaultBranchRef":{"name":"main"}}'
  if [[ -n "\$query" ]]; then
    echo "\$json" | jq -r "\$query"
  else
    echo "\$json"
  fi
  exit 0
elif [[ "\$1" == "api" && "\$2" == *"/git/ref/heads/main" ]]; then
  echo '{"object":{"sha":"main-sha-99999"}}'
  exit 0
elif [[ "\$1" == "workflow" && "\$2" == "run" ]]; then
  echo "\$@" > "$DISPATCH_ARGS_FILE"
  exit 0
elif [[ "\$1" == "api" || "\$1" == "pr" ]]; then
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="writer" COMMENT_AUTHOR_ASSOCIATION="COLLABORATOR" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Write collaborator exit code" "0" "$EXIT_CODE"

# Test 2d: Maintain collaborator accepted
echo "Test 2d: Maintain collaborator accepted"
cat > "$TEST_TMP/bin/gh" << EOF
#!/usr/bin/env bash
if [[ "\$1" == "api" && "\$2" == *"collaborators"* ]]; then
  echo '{"permission":"maintain"}'
  exit 0
elif [[ "\$1" == "pr" && "\$2" == "view" ]]; then
  echo '${MERGED_TO_MAIN_JSON}'
  exit 0
elif [[ "\$1" == "repo" && "\$2" == "view" ]]; then
  shift 2
  query=""
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      -q|--jq)
        query="\$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  json='{"defaultBranchRef":{"name":"main"}}'
  if [[ -n "\$query" ]]; then
    echo "\$json" | jq -r "\$query"
  else
    echo "\$json"
  fi
  exit 0
elif [[ "\$1" == "api" && "\$2" == *"/git/ref/heads/main" ]]; then
  echo '{"object":{"sha":"main-sha-99999"}}'
  exit 0
elif [[ "\$1" == "workflow" && "\$2" == "run" ]]; then
  echo "\$@" > "$DISPATCH_ARGS_FILE"
  exit 0
elif [[ "\$1" == "api" || "\$1" == "pr" ]]; then
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="maintainer" COMMENT_AUTHOR_ASSOCIATION="COLLABORATOR" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Maintain collaborator exit code" "0" "$EXIT_CODE"

# Test 3: Unmerged PR rejected
echo "Test 3: Unmerged PR rejected"
cat > "$TEST_TMP/bin/gh" << 'EOF'
#!/usr/bin/env bash
if [[ "$1" == "pr" && "$2" == "view" ]]; then
  echo '{"state":"OPEN","mergedAt":null,"mergeCommit":null,"headRefOid":"abc1234","baseRefName":"main"}'
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="10" COMMENT_ID="123" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Unmerged PR exit code" "1" "$EXIT_CODE"

# Test 4: Merged PR accepted and triggers workflow dispatch from main tip
echo "Test 4: Merged PR with /release accepted and builds main tip"
DISPATCH_ARGS_FILE="$TEST_TMP/dispatch.args"
cat > "$TEST_TMP/bin/gh" << EOF
#!/usr/bin/env bash
if [[ "\$1" == "pr" && "\$2" == "view" ]]; then
  echo '${MERGED_TO_MAIN_JSON}'
  exit 0
elif [[ "\$1" == "repo" && "\$2" == "view" ]]; then
  shift 2
  query=""
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      -q|--jq)
        query="\$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  json='{"defaultBranchRef":{"name":"main"}}'
  if [[ -n "\$query" ]]; then
    echo "\$json" | jq -r "\$query"
  else
    echo "\$json"
  fi
  exit 0
elif [[ "\$1" == "api" && "\$2" == *"/git/ref/heads/main" ]]; then
  echo '{"object":{"sha":"main-sha-99999"}}'
  exit 0
elif [[ "\$1" == "workflow" && "\$2" == "run" ]]; then
  echo "\$@" > "$DISPATCH_ARGS_FILE"
  exit 0
elif [[ "\$1" == "api" || "\$1" == "pr" ]]; then
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Merged PR exit code" "0" "$EXIT_CODE"
DISPATCHED=$(cat "$DISPATCH_ARGS_FILE")
assert_eq "Workflow name" "release.yml" "$(echo "$DISPATCHED" | awk '{print $3}')"
assert_eq "Has type=release" "1" "$(echo "$DISPATCHED" | grep -c 'type=release' || true)"
assert_eq "Has bump=patch" "1" "$(echo "$DISPATCHED" | grep -c 'bump=patch' || true)"
assert_eq "Uses main tip SHA" "1" "$(echo "$DISPATCHED" | grep -c 'ref=main-sha-99999' || true)"

# Test 4b: Identical merge and main tip also accepted
echo "Test 4b: Merged PR with identical main tip accepted"
cat > "$TEST_TMP/bin/gh" << EOF
#!/usr/bin/env bash
if [[ "\$1" == "pr" && "\$2" == "view" ]]; then
  echo '{"state":"MERGED","mergedAt":"2026-08-31T00:00:00Z","mergeCommit":{"oid":"main-sha-99999"},"headRefOid":"abc1234","baseRefName":"main"}'
  exit 0
elif [[ "\$1" == "repo" && "\$2" == "view" ]]; then
  shift 2
  query=""
  while [[ \$# -gt 0 ]]; do
    case "\$1" in
      -q|--jq)
        query="\$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  json='{"defaultBranchRef":{"name":"main"}}'
  if [[ -n "\$query" ]]; then
    echo "\$json" | jq -r "\$query"
  else
    echo "\$json"
  fi
  exit 0
elif [[ "\$1" == "api" && "\$2" == *"/git/ref/heads/main" ]]; then
  echo '{"object":{"sha":"main-sha-99999"}}'
  exit 0
elif [[ "\$1" == "workflow" && "\$2" == "run" ]]; then
  echo "\$@" > "$DISPATCH_ARGS_FILE"
  exit 0
elif [[ "\$1" == "api" || "\$1" == "pr" ]]; then
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Identical main tip exit code" "0" "$EXIT_CODE"

# Test 5: /release minor
echo "Test 5: Merged PR with /release minor"
COMMENT_BODY="/release minor" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" >/dev/null
DISPATCHED=$(cat "$DISPATCH_ARGS_FILE")
assert_eq "Has bump=minor" "1" "$(echo "$DISPATCHED" | grep -c 'bump=minor' || true)"

# Test 6: /release major
echo "Test 6: Merged PR with /release major"
COMMENT_BODY="/release major" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" >/dev/null
DISPATCHED=$(cat "$DISPATCH_ARGS_FILE")
assert_eq "Has bump=major" "1" "$(echo "$DISPATCHED" | grep -c 'bump=major' || true)"

# Test 7: /nightly
echo "Test 7: Merged PR with /nightly"
COMMENT_BODY="/nightly" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" >/dev/null
DISPATCHED=$(cat "$DISPATCH_ARGS_FILE")
assert_eq "Has type=nightly" "1" "$(echo "$DISPATCHED" | grep -c 'type=nightly' || true)"

# Test 8: PR merged into a feature branch (not main) is rejected
echo "Test 8: PR merged into feature branch rejected"
cat > "$TEST_TMP/bin/gh" << 'EOF'
#!/usr/bin/env bash
if [[ "$1" == "pr" && "$2" == "view" ]]; then
  echo '{"state":"MERGED","mergedAt":"2026-08-31T00:00:00Z","mergeCommit":{"oid":"feature-merge-sha"},"headRefOid":"abc1234","baseRefName":"cursor/feature-branch"}'
  exit 0
elif [[ "$1" == "repo" && "$2" == "view" ]]; then
  shift 2
  query=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -q|--jq)
        query="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  json='{"defaultBranchRef":{"name":"main"}}'
  if [[ -n "$query" ]]; then
    echo "$json" | jq -r "$query"
  else
    echo "$json"
  fi
  exit 0
elif [[ "$1" == "api" || "$1" == "pr" ]]; then
  exit 0
fi
EOF
chmod +x "$TEST_TMP/bin/gh"

set +e
OUT=$(COMMENT_BODY="/release" PR_NUMBER="42" COMMENT_ID="999" COMMENT_AUTHOR="owner" COMMENT_AUTHOR_ASSOCIATION="OWNER" REPO="test/repo" "$SCRIPT" 2>&1)
EXIT_CODE=$?
set -e
assert_eq "Feature branch merge exit code" "1" "$EXIT_CODE"

echo ""
echo "=== Test Summary: $PASSED passed, $FAILED failed ==="
if [[ $FAILED -gt 0 ]]; then
  exit 1
fi
