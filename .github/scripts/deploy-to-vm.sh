#!/usr/bin/env bash
set -euo pipefail

# Production promotion deploy. NEVER rebuilds the app and — importantly — the VM NEVER pulls from
# GHCR anymore. The burstable B2ats VM drained its entire CPU credit battery decompressing image
# layers on every pull (freezing mid-deploy, "client_loop: send disconnect", SSH banner timeouts).
#
# New flow: the GitHub RUNNER pulls the image from GHCR (fast), saves it to a tarball, and streams
# it over the existing SSH connection into `docker load` on the VM — which is almost pure disk
# I/O, nearly zero VM CPU. Then compose up + healthcheck as before.
#
# Env (all required except VM_GHCR_USER/VM_GHCR_TOKEN, which are unused since the VM no longer
# touches GHCR — kept so old secret configs don't break the script):
#   IMAGE_REF        e.g. ghcr.io/<owner>/changelog-composer@sha256:... or :<tag>
#   IMAGE_TAR        path to the gzipped docker-save tarball produced on the runner
#   VM_HOST, VM_USER, VM_COMPOSE_DIR, SSH_PRIVATE_KEY
#   VM_GHCR_USER, VM_GHCR_TOKEN  (optional, ignored)

IMAGE_REF="${IMAGE_REF:?IMAGE_REF must be set}"
IMAGE_TAR="${IMAGE_TAR:?IMAGE_TAR must be set}"
VM_HOST="${VM_HOST:?VM_HOST must be set}"
VM_USER="${VM_USER:?VM_USER must be set}"
VM_COMPOSE_DIR="${VM_COMPOSE_DIR:?VM_COMPOSE_DIR must be set}"
SSH_PRIVATE_KEY="${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY must be set}"
VM_GHCR_USER="${VM_GHCR_USER:-}"
VM_GHCR_TOKEN="${VM_GHCR_TOKEN:-}"

if [ ! -s "$IMAGE_TAR" ]; then
  echo "Image tarball $IMAGE_TAR is missing or empty" >&2
  exit 1
fi

SSH_KEY=$(mktemp)
echo "$SSH_PRIVATE_KEY" > "$SSH_KEY"
chmod 600 "$SSH_KEY"
trap 'rm -f "$SSH_KEY"' EXIT

# SSH options:
#   ConnectTimeout=15      — fail fast if the VM is unreachable instead of hanging for hours
#   BatchMode=yes          — never sit at an interactive prompt (password/host-key) in CI
#   ServerAliveInterval=30 — keepalive traffic every 30s so Azure's ~4min NAT idle timeout
#                            can't silently reset the connection mid-transfer
#   ServerAliveCountMax=10 — tolerate ~5min of total silence before giving up
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
  -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=10)

# Single-quote escape so values survive into the remote shell safely.
q() { printf '%s' "$1" | sed "s/'/'\\\\''/g"; }

REMOTE_TEMPLATE=$(cat <<'REMOTE_EOF'
set -euo pipefail
cd '__VM_COMPOSE_DIR__'

# Image was streamed in via `docker load` just before this script runs — no registry contact.
export IMAGE='__IMAGE_REF__'
echo "Loaded image: $(docker image inspect --format '{{.Id}}' '__IMAGE_REF__' 2>/dev/null | head -c 30)..."

echo "Starting containers"
docker compose -f docker-compose.prod.yml up -d --force-recreate

echo "Waiting for /q/health ..."
ATTEMPTS=0
until curl -sf http://localhost:8080/q/health >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS + 1))
  if [ "$ATTEMPTS" -ge 30 ]; then
    echo "Health check failed after 30 attempts (~150s)" >&2
    docker compose -f docker-compose.prod.yml ps >&2 || true
    exit 1
  fi
  sleep 5
done
echo "Healthy."
REMOTE_EOF
)

REMOTE_SCRIPT=$(printf '%s' "$REMOTE_TEMPLATE" \
  | sed -e "s|__VM_COMPOSE_DIR__|'$(q "$VM_COMPOSE_DIR")'|g" \
        -e "s|__IMAGE_REF__|'$(q "$IMAGE_REF")'|g")

# Retry wrapper — a dropped connection resumes cheaply: docker load is idempotent, and once the
# tarball is fully transferred the layers are already on the VM.
ATTEMPT=1
MAX_ATTEMPTS=3
while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
  echo "::group::Deploy attempt ${ATTEMPT}/${MAX_ATTEMPTS} — streaming image to VM"

  # 1. Stream the image tarball into docker load on the VM (near-zero VM CPU: gunzip + disk write).
  if ! cat "$IMAGE_TAR" | ssh "${SSH_OPTS[@]}" "${VM_USER}@${VM_HOST}" "docker load"; then
    echo "::endgroup::"
    echo "::warning::docker load attempt ${ATTEMPT} failed — retrying in 10s."
    ATTEMPT=$((ATTEMPT + 1))
    sleep 10
    continue
  fi

  # 2. Recreate containers + healthcheck.
  if ssh "${SSH_OPTS[@]}" "${VM_USER}@${VM_HOST}" "bash -s" <<REMOTE_EOF2
${REMOTE_SCRIPT}
REMOTE_EOF2
  then
    echo "::endgroup::"
    echo "Deploy succeeded on attempt ${ATTEMPT}."
    exit 0
  fi
  echo "::endgroup::"
  echo "::warning::Remote deploy attempt ${ATTEMPT} failed — retrying in 10s."
  ATTEMPT=$((ATTEMPT + 1))
  sleep 10
done

echo "Deploy failed after ${MAX_ATTEMPTS} attempts." >&2
exit 1
