#!/usr/bin/env bash
set -euo pipefail

# Runs on `main` AFTER a successful deploy + healthcheck. Production success first, release
# version second — a failed deploy never touches main's version.
#
# This is the ONLY place semver changes. It derives the next version from the highest existing
# `## vX.Y.Z` heading in CHANGELOG.md (falling back to pom.xml on the first release), writes a
# release entry whose body is the raw dev changelog for the promoted run (extracted from
# origin/dev by the workflow — no service round-trip), and commits to main.
#
# Env: RUN_NUMBER (from the promoted build tag), RELEASE_BODY (raw dev changelog for that run),
#      GIT_PUSH_PAT (to push the release commit to main).

git config user.email "github-actions[bot]@users.noreply.github.com"
git config user.name "github-actions[bot]"

RUN_NUMBER="${RUN_NUMBER:?RUN_NUMBER must be set}"
RELEASE_BODY="${RELEASE_BODY:-}"
GIT_PUSH_PAT="${GIT_PUSH_PAT:?GIT_PUSH_PAT must be set}"

# Highest existing release version, newest-first (entries are prepended), e.g. 1.4.2.
LATEST=$(grep -oP '^## v[0-9]+\.[0-9]+\.[0-9]+' CHANGELOG.md | head -1 | sed 's/## v//')
if [ -z "$LATEST" ]; then
  RAW=$(grep -m1 -oP '<version>\K[^<]+' service/pom.xml)
  LATEST="${RAW%%-*}"
fi

# Patch bump, rolling into minor at 100 / major at 10 (same rules as the old Azure script).
IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST"
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

ENTRY="## v${VERSION} — $(date -u +%Y-%m-%d) (promoted build.${RUN_NUMBER})"
if [ -n "$RELEASE_BODY" ]; then
  ENTRY="${ENTRY}

${RELEASE_BODY}"
fi

if [ -f CHANGELOG.md ]; then
  { printf '%s\n\n' "$ENTRY"; cat CHANGELOG.md; } > CHANGELOG.md.new
else
  printf '%s\n' "$ENTRY" > CHANGELOG.md.new
fi
mv CHANGELOG.md.new CHANGELOG.md

git add service/pom.xml web-view/package.json CHANGELOG.md
git commit -q -m "chore: release v${VERSION} (build.${RUN_NUMBER}) ***NO_CI***"

echo "GIT_PUSH_PAT is ${#GIT_PUSH_PAT} characters long (a real PAT should be ~52)."
BASE_URL=$(git remote get-url origin | sed 's#https://[^@]*@#https://#')
PUSH_URL=$(echo "$BASE_URL" | sed "s#https://#https://${GIT_PUSH_PAT}@#")
git -c http.extraheader= push "$PUSH_URL" HEAD:main

echo "Released v${VERSION} on main."