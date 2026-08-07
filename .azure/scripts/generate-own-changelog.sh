#!/usr/bin/env bash
set -euo pipefail

git config user.email "azure-pipelines@datasabai.lan"
git config user.name "Azure Pipelines"

# Single commit per dev merge: bumps version (service/pom.xml + web-view/package.json) and,
# when available, prepends a CHANGELOG.md entry. Version bump always succeeds independently
# so PublishImage always gets a fresh Nexus tag even if the changelog API is unreachable.
#
# Retry loop: concurrent merges race on the same version — the loser re-syncs to origin/dev
# and recomputes, serializing contention instead of failing.
MAX_ATTEMPTS=5
for ATTEMPT in $(seq 1 "$MAX_ATTEMPTS"); do
  if [ "$ATTEMPT" -gt 1 ]; then
    echo "Push rejected (attempt $((ATTEMPT - 1))/${MAX_ATTEMPTS}) — another merge's bump landed" \
         "first. Re-syncing to origin/dev and retrying." >&2
    git fetch origin dev
    git reset --hard origin/dev
  fi

  # -m1/\K: first <version> match only (the project's own, not a pinned dependency).
  RAW_VERSION=$(grep -m1 -oP '<version>\K[^<]+' service/pom.xml)
  # Strips "-SNAPSHOT" suffix if present. Patch rolls into minor at 100, minor into major at 10.
  CURRENT_VERSION="${RAW_VERSION%%-*}"
  IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
  PATCH=$((PATCH + 1))
  if [ "$PATCH" -ge 100 ]; then
    PATCH=0
    MINOR=$((MINOR + 1))
  fi
  if [ "$MINOR" -ge 10 ]; then
    MINOR=0
    MAJOR=$((MAJOR + 1))
  fi
  VERSION="${MAJOR}.${MINOR}.${PATCH}"

  sed -i "0,/<version>[^<]*<\/version>/s//<version>${VERSION}<\/version>/" service/pom.xml
  jq --arg v "$VERSION" '.version = $v' web-view/package.json > web-view/package.json.tmp
  mv web-view/package.json.tmp web-view/package.json

  CHANGELOG_ADDED=false
  if [ -z "${CHANGELOG_SERVICE_URL:-}" ]; then
    echo "CHANGELOG_SERVICE_URL isn't set yet — bumping version only, no changelog entry."
  else
    # $BUILD_BUILDID (Azure DevOps' auto-mapped env var for the predefined $(Build.BuildId))
    # lets the Composer fetch this run's own commits/work items/PRs straight from Azure DevOps'
    # Build API itself — no more git log scraping or work-item regex needed here.
    # No -f: we capture status and body separately — -f swallows both on error, leaving no clue.
    RAW=$(curl -s -w '\n%{http_code}' -X POST "$CHANGELOG_SERVICE_URL" \
      -H "Authorization: Bearer $CHANGELOG_API_KEY" \
      -H 'Content-Type: application/json' \
      -d "{
        \"project\": \"$SYSTEM_TEAMPROJECT\",
        \"repo\": \"$BUILD_REPOSITORY_NAME\",
        \"version\": \"$VERSION\",
        \"stage\": \"prerelease\",
        \"buildId\": $BUILD_BUILDID
      }")
    HTTP_STATUS=$(echo "$RAW" | tail -1)
    RESPONSE=$(echo "$RAW" | sed '$d')

    if [ "$HTTP_STATUS" -ge 400 ]; then
      echo "Pipeline call failed with HTTP $HTTP_STATUS:" >&2
      echo "$RESPONSE" >&2
      echo "API error — bumping version only, no changelog entry." >&2
      CHANGELOG=""
    else
      CHANGELOG=$(echo "$RESPONSE" | jq -r '.changelog // empty')
    fi
    if [ -z "$CHANGELOG" ]; then
      echo "No changelog text came back — bumping version only, no changelog entry."
    else
      echo "=============================="
      echo "GENERATED CHANGELOG (v${VERSION})"
      echo "=============================="
      echo "$CHANGELOG"
      echo "=============================="

      # Prepend the new entry above whatever's already there (creates the file on the first run).
      ENTRY="## v${VERSION} — $(date -u +%Y-%m-%d)

${CHANGELOG}"

      if [ -f CHANGELOG.md ]; then
        { printf '%s\n\n' "$ENTRY"; cat CHANGELOG.md; } > CHANGELOG.md.new
      else
        printf '%s\n' "$ENTRY" > CHANGELOG.md.new
      fi
      mv CHANGELOG.md.new CHANGELOG.md
      CHANGELOG_ADDED=true
    fi
  fi

  git add service/pom.xml web-view/package.json
  if [ "$CHANGELOG_ADDED" = "true" ]; then
    git add CHANGELOG.md
    git commit -q -m "chore: bump version to ${VERSION} + update CHANGELOG.md ***NO_CI***"
  else
    git commit -q -m "chore: bump version to ${VERSION} ***NO_CI***"
  fi

  # Logs PAT length only — never the value itself — so misconfiguration is diagnosable without
  # leaking the secret.
  echo "GIT_PUSH_PAT is ${#GIT_PUSH_PAT} characters long (a real PAT should be ~52)."

  # Uses GIT_PUSH_PAT instead of Build Service identity (lacks "Contribute" permission).
  # -c http.extraheader= clears persistCredentials' auth header — sending both is rejected.
  BASE_URL=$(git remote get-url origin | sed 's#https://[^@]*@#https://#')
  PUSH_URL=$(echo "$BASE_URL" | sed "s#https://#https://${GIT_PUSH_PAT}@#")
  if git -c http.extraheader= push "$PUSH_URL" HEAD:dev; then
    echo "Bumped version to ${VERSION}"
    exit 0
  fi
done

echo "Failed to push version bump after ${MAX_ATTEMPTS} attempts — too much concurrent contention on dev." >&2
exit 1
