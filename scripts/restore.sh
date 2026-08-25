#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$1" != "--confirm-replace-database" ]; then
  echo "Usage: $0 --confirm-replace-database /absolute/path/to/backup.dump [media.tar.gz]" >&2
  exit 2
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DB_FILE=$2
MEDIA_FILE=${3:-}
test -f "$DB_FILE"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  . "$ROOT_DIR/.env"
  set +a
fi

docker compose -f "$ROOT_DIR/docker-compose.prod.yml" stop backend
docker compose -f "$ROOT_DIR/docker-compose.prod.yml" exec -T postgres \
  pg_restore -U "${DB_USER:-campusguard}" -d "${DB_NAME:-campusguard}" \
  --clean --if-exists --no-owner --no-privileges < "$DB_FILE"

if [ -n "$MEDIA_FILE" ]; then
  test -f "$MEDIA_FILE"
  docker compose -f "$ROOT_DIR/docker-compose.prod.yml" run --rm -T --no-deps \
    --entrypoint sh backend \
    -c 'find /data/media -mindepth 1 -delete && tar -C /data -xzf -' < "$MEDIA_FILE"
fi

docker compose -f "$ROOT_DIR/docker-compose.prod.yml" start backend
echo "Restore completed. Verify /actuator/health/readiness before reopening traffic."
