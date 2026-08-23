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

# SSH options:
#   ConnectTimeout=15      — fail fast if the VM is unreachable instead of hanging for hours
#   BatchMode=yes          — never sit at an interactive prompt (password/host-key) in CI
#   ServerAliveInterval=30 — keepalive traffic every 30s so Azure's ~4min NAT idle timeout
#                            can't silently reset the connection mid docker-pull (the cause
#                            of "client_loop: send disconnect: Broken pipe" exit 255)
#   ServerAliveCountMax=10 — tolerate ~5min of total silence before giving up
#   Retry (3 attempts)     — a dropped connection resumes cheaply: docker keeps completed
#                            layers, so a retry continues the pull instead of restarting it.
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
  -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=10)

ATTEMPT=1
MAX_ATTEMPTS=3
while [ "$ATTEMPT" -le "$MAX_ATTEMPTS" ]; do
  echo "::group::Deploy attempt ${ATTEMPT}/${MAX_ATTEMPTS}"
  if ssh "${SSH_OPTS[@]}" "${VM_USER}@${VM_HOST}" "bash -s" <<REMOTE_EOF2
${REMOTE_SCRIPT}
REMOTE_EOF2
  then
    echo "::endgroup::"
    echo "Deploy succeeded on attempt ${ATTEMPT}."
    exit 0
  fi
  echo "::endgroup::"
  echo "::warning::SSH deploy attempt ${ATTEMPT} failed — retrying in 10s (docker layer cache makes the retry resume the pull)."
  ATTEMPT=$((ATTEMPT + 1))
  sleep 10
done

echo "Deploy failed after ${MAX_ATTEMPTS} attempts." >&2
exit 1