#!/usr/bin/env bash
# Verifies that the CI workflow succeeded on the target commit before publishing.
set -euo pipefail

TARGET_REF="${1:-main}"
SKIP_CI_CHECK="${SKIP_CI_CHECK:-false}"
REPO="${GITHUB_REPOSITORY:-}"
MAX_AGE_DAYS="${CI_CHECK_MAX_AGE_DAYS:-7}"
CI_JOB_NAMES="${CI_JOB_NAMES:-unit-test static-analysis}"
# /release often lands while main CI is still running. Wait instead of failing immediately.
CI_CHECK_WAIT_SECONDS="${CI_CHECK_WAIT_SECONDS:-720}"
CI_CHECK_POLL_SECONDS="${CI_CHECK_POLL_SECONDS:-20}"

if [[ "$SKIP_CI_CHECK" == "true" ]]; then
  echo "SKIP_CI_CHECK=true — skipping CI verification."
  exit 0
fi

if [[ -z "$REPO" ]]; then
  echo "GITHUB_REPOSITORY is not set" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required to verify CI status" >&2
  exit 1
fi

resolve_sha() {
  local ref="$1"
  if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
    echo "$ref"
    return
  fi
  gh api "repos/${REPO}/git/ref/heads/${ref}" --jq '.object.sha'
}

verify_job() {
  local job_name="$1"
  local target_sha="$2"
  local check_runs_json="$3"

  echo "Verifying CI job '${job_name}' succeeded on ${target_sha:0:7}..."

  local matching_count
  matching_count=$(echo "$check_runs_json" | jq --arg name "$job_name" '[.check_runs[] | select(.name == $name)] | length')

  if [[ "$matching_count" -eq 0 ]]; then
    echo "No '${job_name}' check run yet for commit ${target_sha}." >&2
    return 2
  fi

  local latest status conclusion completed_at html_url
  latest=$(echo "$check_runs_json" | jq --arg name "$job_name" '[.check_runs[] | select(.name == $name)] | sort_by(.started_at) | last')
  status=$(echo "$latest" | jq -r '.status')
  conclusion=$(echo "$latest" | jq -r '.conclusion // empty')
  completed_at=$(echo "$latest" | jq -r '.completed_at // empty')
  html_url=$(echo "$latest" | jq -r '.html_url // empty')

  if [[ "$status" != "completed" ]]; then
    echo "CI check '${job_name}' is still ${status}: ${html_url}" >&2
    return 2
  fi

  if [[ "$conclusion" != "success" ]]; then
    echo "CI check '${job_name}' concluded with '${conclusion}'." >&2
    echo "Fix CI or re-run checks before publishing: ${html_url}" >&2
    return 1
  fi

  if [[ -n "$completed_at" && "$completed_at" != "null" ]]; then
    local completed_epoch now_epoch max_age_seconds age
    completed_epoch=$(date -d "$completed_at" +%s)
    now_epoch=$(date +%s)
    max_age_seconds=$((MAX_AGE_DAYS * 24 * 60 * 60))
    age=$((now_epoch - completed_epoch))
    if [[ "$age" -gt "$max_age_seconds" ]]; then
      echo "CI check '${job_name}' is older than ${MAX_AGE_DAYS} days (completed ${completed_at})." >&2
      echo "Re-run CI on main or merge a new commit before publishing." >&2
      return 1
    fi
  fi

  echo "CI verified: ${job_name} succeeded at ${completed_at}"
  echo "Check run: ${html_url}"
}

TARGET_SHA="$(resolve_sha "$TARGET_REF")"
deadline=$(( $(date +%s) + CI_CHECK_WAIT_SECONDS ))

while true; do
  CHECK_RUNS_JSON=$(gh api "repos/${REPO}/commits/${TARGET_SHA}/check-runs?per_page=100")
  pending=false
  for job_name in $CI_JOB_NAMES; do
    set +e
    verify_job "$job_name" "$TARGET_SHA" "$CHECK_RUNS_JSON"
    rc=$?
    set -e
    if [[ "$rc" -eq 2 ]]; then
      pending=true
    elif [[ "$rc" -ne 0 ]]; then
      exit 1
    fi
  done

  if [[ "$pending" == "false" ]]; then
    exit 0
  fi

  now=$(date +%s)
  if [[ "$now" -ge "$deadline" ]]; then
    echo "Timed out after ${CI_CHECK_WAIT_SECONDS}s waiting for CI on ${TARGET_SHA}." >&2
    echo "Retry /nightly or /release after main CI finishes." >&2
    exit 1
  fi

  echo "CI still running on ${TARGET_SHA:0:7}. Checking again in ${CI_CHECK_POLL_SECONDS}s."
  if [[ "$CI_CHECK_POLL_SECONDS" -gt 0 ]]; then
    sleep "$CI_CHECK_POLL_SECONDS"
  fi
done
