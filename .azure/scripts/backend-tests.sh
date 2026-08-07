#!/usr/bin/env bash
set -euo pipefail

# Throwaway Postgres container for Quarkus test suite — never touches the real Neon database.
CONTAINER_NAME="backend-tests-db-$$"
VOLUME_NAME="backend-tests-vol-$$"
IMAGE="docker.io/library/postgres:17-alpine"

# Retry pull separately — docker run never retries on its own, and Docker Hub's anonymous rate
# limit is the most likely failure on a shared CI IP.
for attempt in 1 2 3 4 5; do
  if docker pull "$IMAGE"; then
    break
  fi
  if [ "$attempt" = 5 ]; then
    echo "Failed to pull ${IMAGE} after 5 attempts." >&2
    exit 1
  fi
  sleep $((attempt * 5))
done

# --network=host avoids rootless Podman's slirp4netns requirement (missing on this agent).
# A named volume is used so it can be explicitly removed in cleanup — anonymous volumes
# survive container removal and cause Flyway checksum mismatches on subsequent runs.
docker volume create "$VOLUME_NAME" >/dev/null
docker run -d --name "$CONTAINER_NAME" \
  --network=host \
  -v "$VOLUME_NAME:/var/lib/postgresql/data" \
  -e POSTGRES_USER=test \
  -e POSTGRES_PASSWORD=test \
  -e POSTGRES_DB=changelog \
  "$IMAGE" >/dev/null

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# TCP probe from host, not pg_isready — Postgres image starts an init-only instance that accepts
# Unix sockets before TCP is up, and pg_isready reports ready prematurely (caused a real failure).
echo "Waiting for Postgres to accept TCP connections..."
ready=false
for _ in $(seq 1 30); do
  if (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; then
    exec 3<&- 3>&-
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != "true" ]; then
  echo "Postgres never started accepting TCP connections." >&2
  exit 1
fi

export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:5432/changelog"
export QUARKUS_DATASOURCE_USERNAME=test
export QUARKUS_DATASOURCE_PASSWORD=test
# application.properties doesn't exist in CI — without this, Flyway never runs.
export QUARKUS_FLYWAY_MIGRATE_AT_START=true
# Self-heal checksum mismatches when migration files are modified after first application.
export QUARKUS_FLYWAY_REPAIR_AT_START=true
# Same reason: resource classes no longer hardcode /api in their own @Path (see Dockerfile), so
# this has to be restored here too or every "/api/..." test URL 404s.
export QUARKUS_REST_PATH=/api

cd service
./mvnw test
