#!/usr/bin/env bash
set -euo pipefail

# Manual fallback when builder-pool is unavailable. Same checks as CI, then pushes to the same
# registry. No --network=host (that's a rootless-Podman workaround, not needed with normal
# Docker). Aborts on any failure (set -e) — this is the only gate, not a shortcut.

cd "$(git rev-parse --show-toplevel)"

echo "=============================="
echo "Running backend tests..."
echo "=============================="
bash .azure/scripts/backend-tests.sh

echo "=============================="
echo "Running frontend lint/test/build..."
echo "=============================="
bash .azure/scripts/frontend-checks.sh

: "${IMAGE_REPOSITORY:=changelog-composer}"
: "${REGISTRY_HOST:=registry.datasabai.lan}"

if [ -z "${REGISTRY_USER:-}" ]; then
  read -rp "Registry username: " REGISTRY_USER
fi
if [ -z "${REGISTRY_PASSWORD:-}" ]; then
  read -rsp "Registry password: " REGISTRY_PASSWORD
  echo
fi

SHA_TAG=$(git rev-parse --short HEAD)
echo "All checks passed — building ${IMAGE_REPOSITORY}:${SHA_TAG} from $(git log -1 --oneline)"

docker build -t "${IMAGE_REPOSITORY}:${SHA_TAG}" .
docker tag "${IMAGE_REPOSITORY}:${SHA_TAG}" "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${SHA_TAG}"
docker tag "${IMAGE_REPOSITORY}:${SHA_TAG}" "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:latest"

echo "${REGISTRY_PASSWORD}" | docker login "${REGISTRY_HOST}" -u "${REGISTRY_USER}" --password-stdin

docker push "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${SHA_TAG}"
docker push "${REGISTRY_HOST}/${IMAGE_REPOSITORY}:latest"

echo "Pushed ${REGISTRY_HOST}/${IMAGE_REPOSITORY}:${SHA_TAG} and :latest"
echo "Note: does not move 'last-image-build' tag — next CI run will rebuild this commit once."
