#!/usr/bin/env bash
set -euo pipefail

# Production promotion deploy. NEVER rebuilds the app — pulls the exact image (digest-pinned when
# available) that the dev pipeline already pushed to GHCR, restarts the container via
# docker-compose.prod.yml on the VM, and gates on the app's /q/health before declaring success.
#
# Env (all required except VM_GHCR_USER/VM_GHCR_TOKEN, which are only needed for a private package):
#   IMAGE_REF        e.g. ghcr.io/<owner>/changelog-composer@sha256:... or :<tag>
#   VM_HOST, VM_USER, VM_COMPOSE_DIR, SSH_PRIVATE_KEY
#   VM_GHCR_USER, VM_GHCR_TOKEN  (optional; enables docker login ghcr.io on the VM)

IMAGE_REF="${IMAGE_REF:?IMAGE_REF must be set}"
VM_HOST="${VM_HOST:?VM_HOST must be set}"
VM_USER="${VM_USER:?VM_USER must be set}"
VM_COMPOSE_DIR="${VM_COMPOSE_DIR:?VM_COMPOSE_DIR must be set}"
SSH_PRIVATE_KEY="${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY must be set}"
VM_GHCR_USER="${VM_GHCR_USER:-}"
VM_GHCR_TOKEN="${VM_GHCR_TOKEN:-}"

SSH_KEY=$(mktemp)
echo "$SSH_PRIVATE_KEY" > "$SSH_KEY"
chmod 600 "$SSH_KEY"
trap 'rm -f "$SSH_KEY"' EXIT

# Single-quote escape so values survive into the remote shell safely.
q() { printf '%s' "$1" | sed "s/'/'\\\\''/g"; }

# Quoted heredoc: nothing expands locally — ${IMAGE}, $ATTEMPTS etc. stay literal and are expanded
# by the REMOTE shell. The VM_* / IMAGE_REF values are injected via placeholders below.
REMOTE_TEMPLATE=$(cat <<'REMOTE_EOF'
set -euo pipefail
cd '__VM_COMPOSE_DIR__'

if [ -n '__VM_GHCR_USER__' ] && [ -n '__VM_GHCR_TOKEN__' ]; then
  echo '__VM_GHCR_TOKEN__' | docker login ghcr.io -u '__VM_GHCR_USER__' --password-stdin
fi

export IMAGE='__IMAGE_REF__'
echo "Pulling ${IMAGE}"
docker compose -f docker-compose.prod.yml pull

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
        -e "s|__VM_GHCR_USER__|'$(q "$VM_GHCR_USER")'|g" \
        -e "s|__VM_GHCR_TOKEN__|'$(q "$VM_GHCR_TOKEN")'|g" \
        -e "s|__IMAGE_REF__|'$(q "$IMAGE_REF")'|g")

ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no \
  "${VM_USER}@${VM_HOST}" "bash -s" <<REMOTE_EOF2
${REMOTE_SCRIPT}
REMOTE_EOF2