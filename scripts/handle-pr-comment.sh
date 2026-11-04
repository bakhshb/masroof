#!/usr/bin/env bash
# Handles PR slash commands (/release, /release minor, /release major, /nightly).
# Validates authorization and PR merge status, then triggers release.yml.
set -euo pipefail

COMMENT_BODY="${COMMENT_BODY:-${1:-}}"
PR_NUMBER="${PR_NUMBER:-${2:-}}"
COMMENT_ID="${COMMENT_ID:-${3:-}}"
COMMENT_AUTHOR="${COMMENT_AUTHOR:-${4:-}}"
COMMENT_AUTHOR_ASSOCIATION="${COMMENT_AUTHOR_ASSOCIATION:-${5:-}}"
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"

if [[ -z "$COMMENT_BODY" || -z "$PR_NUMBER" ]]; then
  echo "Usage: COMMENT_BODY='...' PR_NUMBER='...' $0" >&2
  exit 1
fi

# 1. Parse slash command
CLEAN_BODY="$(echo "$COMMENT_BODY" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
FIRST_LINE="$(echo "$CLEAN_BODY" | head -n 1)"

TYPE=""
BUMP="patch"

case "$FIRST_LINE" in
  "/nightly"*)
    TYPE="nightly"
    ;;
  "/release"|"/release patch")
    TYPE="release"
    BUMP="patch"
    ;;
  "/release minor")
    TYPE="release"
    BUMP="minor"
    ;;
  "/release major")
    TYPE="release"
    BUMP="major"
    ;;
  *)
    echo "Unrecognized release command: '$FIRST_LINE'" >&2
    if [[ -n "${COMMENT_ID:-}" && -n "$REPO" ]] && command -v gh >/dev/null 2>&1; then
      gh api "repos/${REPO}/issues/comments/${COMMENT_ID}/reactions" -f content="-1" 2>/dev/null || true
      gh pr comment "$PR_NUMBER" --body "❌ **Invalid command:** \`${FIRST_LINE}\`."$'\nSupported commands:\n- `/nightly`\n- `/release` (or `/release patch`)\n- `/release minor`\n- `/release major`' 2>/dev/null || true
    fi
    exit 1
    ;;
esac

# 2. Check authorization (admin/maintain/write access required)
IS_AUTHORIZED=false
if [[ "$COMMENT_AUTHOR_ASSOCIATION" == "OWNER" ]]; then
  IS_AUTHORIZED=true
elif command -v gh >/dev/null 2>&1 && [[ -n "$REPO" && -n "$COMMENT_AUTHOR" ]]; then
  PERM=$(gh api "repos/${REPO}/collaborators/${COMMENT_AUTHOR}/permission" 2>/dev/null | jq -r '.permission' || true)
  if [[ "$PERM" == "admin" || "$PERM" == "maintain" || "$PERM" == "write" ]]; then
    IS_AUTHORIZED=true
  fi
fi

if [[ "$IS_AUTHORIZED" != "true" ]]; then
  echo "User '$COMMENT_AUTHOR' ($COMMENT_AUTHOR_ASSOCIATION) is not authorized." >&2
  if [[ -n "${COMMENT_ID:-}" && -n "$REPO" ]] && command -v gh >/dev/null 2>&1; then
    gh api "repos/${REPO}/issues/comments/${COMMENT_ID}/reactions" -f content="-1" 2>/dev/null || true
    gh pr comment "$PR_NUMBER" --body "❌ **Permission denied.** Only repository owners and users with maintain or write access can trigger release builds." 2>/dev/null || true
  fi
  exit 1
fi

# 3. Verify PR is merged into the default branch (main)
if command -v gh >/dev/null 2>&1 && [[ -n "$REPO" ]]; then
  PR_JSON=$(gh pr view "$PR_NUMBER" --repo "$REPO" --json state,mergedAt,mergeCommit,headRefOid,baseRefName 2>&1) || {
    echo "Could not fetch PR #$PR_NUMBER details: ${PR_JSON}" >&2
    exit 1
  }

  DEFAULT_BRANCH=$(gh repo view "$REPO" --json defaultBranchRef -q '.defaultBranchRef.name' 2>/dev/null || echo "main")
  BASE_REF=$(echo "$PR_JSON" | jq -r '.baseRefName // empty')

  IS_MERGED=$(echo "$PR_JSON" | jq -r 'if .state == "MERGED" then "true" else "false" end')
  if [[ "$IS_MERGED" != "true" ]]; then
    echo "PR #$PR_NUMBER is not merged." >&2
    if [[ -n "${COMMENT_ID:-}" ]]; then
      gh api "repos/${REPO}/issues/comments/${COMMENT_ID}/reactions" -f content="-1" 2>/dev/null || true
    fi
    gh pr comment "$PR_NUMBER" --repo "$REPO" --body "❌ **Release commands are only allowed on merged PRs.** Please merge this PR before running \`/${TYPE}\`." 2>/dev/null || true
    exit 1
  fi

  if [[ -z "$BASE_REF" || "$BASE_REF" != "$DEFAULT_BRANCH" ]]; then
    echo "PR #$PR_NUMBER was merged into '${BASE_REF:-unknown}', not '${DEFAULT_BRANCH}'." >&2
    if [[ -n "${COMMENT_ID:-}" ]]; then
      gh api "repos/${REPO}/issues/comments/${COMMENT_ID}/reactions" -f content="-1" 2>/dev/null || true
    fi
    gh pr comment "$PR_NUMBER" --repo "$REPO" --body "❌ **Cannot publish from this PR.** It was merged into \`${BASE_REF:-unknown}\`, not \`${DEFAULT_BRANCH}\`. Open a PR targeting \`${DEFAULT_BRANCH}\`, merge it, then run \`/${TYPE}\` on that merged PR." 2>/dev/null || true
    exit 1
  fi

  MAIN_SHA=$(gh api "repos/${REPO}/git/ref/heads/${DEFAULT_BRANCH}" 2>/dev/null | jq -r '.object.sha' || true)
  if [[ -z "$MAIN_SHA" || "$MAIN_SHA" == "null" ]]; then
    echo "Could not resolve current ${DEFAULT_BRANCH} branch tip" >&2
    exit 1
  fi

  # Always build from the current default-branch tip so releases never omit newer merged commits.
  TARGET_SHA="$MAIN_SHA"
  MERGE_SHA=$(echo "$PR_JSON" | jq -r '.mergeCommit.oid // empty')
  MAIN_AHEAD_OF_PR=$([[ -n "$MERGE_SHA" && "$MERGE_SHA" != "$MAIN_SHA" ]] && echo "true" || echo "false")
else
  TARGET_SHA="main"
  MAIN_AHEAD_OF_PR="false"
fi

# 4. React with 'eyes' to confirm command acceptance
if [[ -n "${COMMENT_ID:-}" && -n "$REPO" ]] && command -v gh >/dev/null 2>&1; then
  gh api "repos/${REPO}/issues/comments/${COMMENT_ID}/reactions" -f content="eyes" 2>/dev/null || true
fi

# 5. Trigger release workflow
echo "Triggering release workflow: type=$TYPE, bump=$BUMP, ref=$TARGET_SHA, pr=$PR_NUMBER"

if command -v gh >/dev/null 2>&1 && [[ -n "$REPO" ]]; then
  gh workflow run release.yml \
    --repo "$REPO" \
    -f type="$TYPE" \
    -f bump="$BUMP" \
    -f ref="$TARGET_SHA" \
    -f pr_number="$PR_NUMBER" \
    -f comment_id="${COMMENT_ID:-}"

  SHORT_SHA="${TARGET_SHA:0:7}"
  if [[ "$MAIN_AHEAD_OF_PR" == "true" ]]; then
    gh pr comment "$PR_NUMBER" --repo "$REPO" --body "⏳ **Accepted \`${FIRST_LINE}\` command.** \`main\` has newer commits since this PR merged; queued \`${TYPE}\` build from current \`main\` tip \`${SHORT_SHA}\`..." 2>/dev/null || true
  else
    gh pr comment "$PR_NUMBER" --repo "$REPO" --body "⏳ **Accepted \`${FIRST_LINE}\` command.** Queued \`${TYPE}\` build for \`main\` at \`${SHORT_SHA}\`..." 2>/dev/null || true
  fi
fi

echo "Successfully triggered release for PR #$PR_NUMBER"
