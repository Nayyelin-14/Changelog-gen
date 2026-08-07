#!/usr/bin/env bash
set -euo pipefail

# Skip build if only docs/config changed since last successful build. Diffing against
# last-image-build (not HEAD~1) prevents a backlog of skipped builds from collapsing into
# a single rebuild on the next unrelated commit.
#
# Deliberately NOT a real tag (refs/tags/...): a normal tag syncs into every developer's local
# clone via plain git clone/fetch/pull, and this ref is force-moved on every single pipeline run
# — that combination guarantees a "conflicting tag(s)" prompt for anyone who's ever fetched it,
# forever. refs/ci-cache/... is outside the ref namespaces plain git tooling fetches by default,
# so this never reaches (or bothers) a developer's machine at all.
REF="refs/ci-cache/last-image-build"
git fetch origin "+${REF}:${REF}" >/dev/null 2>&1 || true

if git rev-parse "$REF" >/dev/null 2>&1; then
  CHANGED=$(git diff --name-only "$REF" HEAD)
  if echo "$CHANGED" | grep -qE '^(service/|web-view/|Dockerfile$|\.dockerignore$)'; then
    RELEVANT=true
  else
    RELEVANT=false
  fi
else
  # First-ever run — build to be safe.
  RELEVANT=true
fi

if [ "$RELEVANT" != "true" ]; then
  echo "No service/web-view/Dockerfile changes since the last built image — skipping image build."
  exit 0
fi

SHA_TAG=$(git rev-parse --short HEAD)

# Read version from origin/dev (not local checkout) — the Changelog stage may have pushed its
# bump commit after this job's checkout. The version tag + SHA make Nexus artifacts traceable.
git fetch origin dev
VERSION=$(git show origin/dev:service/pom.xml | grep -m1 -oP '<version>\K[^<]+')

echo "Building ${IMAGE_REPOSITORY}:${VERSION} (commit ${SHA_TAG})"

# --network=host avoids rootless Podman's slirp4netns requirement (missing on this agent).
docker build --network=host -t "${IMAGE_REPOSITORY}:${VERSION}" .
docker tag "${IMAGE_REPOSITORY}:${VERSION}" "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${VERSION}"
docker tag "${IMAGE_REPOSITORY}:${VERSION}" "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${SHA_TAG}"
docker tag "${IMAGE_REPOSITORY}:${VERSION}" "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:latest"

echo "${REGISTRY_PASSWORD}" | docker login "${REGISTRY_HOST}" -u "${REGISTRY_USER}" --password-stdin
docker push "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${VERSION}"
docker push "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${SHA_TAG}"
docker push "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:latest"

# Advance the last-image-build marker after a successful push. Best-effort: failure means next
# run re-diffs from the old marker (redundant rebuild, not broken).
echo "GIT_PUSH_PAT is ${#GIT_PUSH_PAT} characters long (a real PAT should be ~52)."
# Uses GIT_PUSH_PAT — Build Service identity lacks "Contribute". -c http.extraheader= prevents
# sending both persistCredentials' and the PAT's auth (some servers reject that).
git update-ref "$REF" HEAD
  BASE_URL=$(git remote get-url origin | sed 's#https://[^@]*@#https://#')
  PUSH_URL=$(echo "$BASE_URL" | sed "s#https://#https://${GIT_PUSH_PAT}@#")
git -c http.extraheader= push "$PUSH_URL" "${REF}:${REF}" --force \
  || echo "Warning: could not push ${REF} even with GIT_PUSH_PAT — check the PAT's scope/expiry." >&2
