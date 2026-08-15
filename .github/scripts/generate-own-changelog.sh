#!/usr/bin/env bash
set -euo pipefail

# Dev-side self-changelog for this repo (GitHub Actions version).
#
# Called on push/merge to `dev`, before the Docker build. It dogfoods this project's own
# `POST /api/github/pipeline/generate` endpoint: the service fetches this run's own commits/PRs
# from GitHub (no git scraping here), builds a raw non-AI Developer changelog, and persists a
# snapshot on `recorded_pipeline_run` keyed by (provider='github', project, repo, run_id).
#
# Design rules (settled with the team):
#   - NO version bump. dev never changes pom.xml/package.json versions — semver only changes on
#     a successful production promotion (see .github/workflows/deploy-to-vm.yml).
#   - NO AI. The pipeline endpoint is deliberately AI-free by construction.
#   - Changelog is informational, NOT a release gate. If the service is unreachable we log a
#     warning and exit 0 — the Docker image still gets built.
#   - The entry heading carries the run number so the release workflow can later map
#     `build.{run}-{sha}` -> the dev entry WITHOUT a service round-trip or a "builds <= N" query.

git config user.email "github-actions[bot]@users.noreply.github.com"
git config user.name "github-actions[bot]"

RUN_ID="${GITHUB_RUN_ID:?GITHUB_RUN_ID must be set}"
RUN_NUMBER="${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER must be set}"
OWNER="${GITHUB_REPOSITORY_OWNER:?GITHUB_REPOSITORY_OWNER must be set}"
REPO="${GITHUB_REPOSITORY#*/}"
BRANCH="${GITHUB_REF_NAME:-dev}"

CHANGELOG=""
if [ -z "${CHANGELOG_SERVICE_URL:-}" ] || [ -z "${CHANGELOG_API_KEY:-}" ]; then
  echo "CHANGELOG_SERVICE_URL / CHANGELOG_API_KEY not configured — skipping changelog entry (build proceeds)."
else
  # GitHub-side pipeline ingest. No -f: we capture status and body separately — -f swallows both
  # on error, leaving no clue.
  RAW=$(curl -s -w '\n%{http_code}' -X POST "${CHANGELOG_SERVICE_URL}/github/pipeline/generate" \
    -H "Authorization: Bearer ${CHANGELOG_API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "{
      \"runId\": ${RUN_ID},
      \"project\": \"${OWNER}\",
      \"repo\": \"${REPO}\",
      \"branch\": \"${BRANCH}\"
    }")
  HTTP_STATUS=$(echo "$RAW" | tail -1)
  RESPONSE=$(echo "$RAW" | sed '$d')

  if [ "$HTTP_STATUS" -ge 400 ]; then
    echo "Pipeline call failed with HTTP ${HTTP_STATUS}:" >&2
    echo "$RESPONSE" >&2
    echo "API error — skipping changelog entry (build proceeds)." >&2
  else
    CHANGELOG=$(echo "$RESPONSE" | jq -r '.changelog // empty')
  fi
fi

if [ -z "$CHANGELOG" ]; then
  echo "No changelog text came back — skipping changelog entry (build proceeds)."
  exit 0
fi

echo "=============================="
echo "GENERATED CHANGELOG (run ${RUN_NUMBER})"
echo "=============================="
echo "$CHANGELOG"
echo "=============================="

# The `(run ${RUN_NUMBER})` marker is the join key the release workflow uses. Dev history stays
# in CHANGELOG.md forever (auditable) — a release never deletes or replaces these entries.
ENTRY="## $(date -u +%Y-%m-%d) — dev (run ${RUN_NUMBER})

${CHANGELOG}"

if [ -f CHANGELOG.md ]; then
  { printf '%s\n\n' "$ENTRY"; cat CHANGELOG.md; } > CHANGELOG.md.new
else
  printf '%s\n' "$ENTRY" > CHANGELOG.md.new
fi
mv CHANGELOG.md.new CHANGELOG.md

# Retry loop: concurrent dev merges race on the same file — the loser re-syncs to origin/dev
# and recomputes, serializing contention instead of failing.
MAX_ATTEMPTS=5
for ATTEMPT in $(seq 1 "$MAX_ATTEMPTS"); do
  if [ "$ATTEMPT" -gt 1 ]; then
    echo "Push rejected (attempt $((ATTEMPT - 1))/${MAX_ATTEMPTS}) — another merge's changelog "
         "landed first. Re-syncing to origin/dev and retrying." >&2
    git fetch origin dev
    git reset --hard origin/dev
    if [ -f CHANGELOG.md ]; then
      { printf '%s\n\n' "$ENTRY"; cat CHANGELOG.md; } > CHANGELOG.md.new
    else
      printf '%s\n' "$ENTRY" > CHANGELOG.md.new
    fi
    mv CHANGELOG.md.new CHANGELOG.md
  fi

  git add CHANGELOG.md
  git commit -q -m "docs: append dev changelog entry (run ${RUN_NUMBER}) ***NO_CI***"

  echo "GIT_PUSH_PAT is ${#GIT_PUSH_PAT} characters long (a real PAT should be ~52)."
  BASE_URL=$(git remote get-url origin | sed 's#https://[^@]*@#https://#')
  PUSH_URL=$(echo "$BASE_URL" | sed "s#https://#https://${GIT_PUSH_PAT}@#")
  if git -c http.extraheader= push "$PUSH_URL" HEAD:dev; then
    echo "Committed dev changelog entry (run ${RUN_NUMBER})."
    exit 0
  fi
done

echo "Failed to push changelog after ${MAX_ATTEMPTS} attempts — too much concurrent contention on dev." >&2
exit 1