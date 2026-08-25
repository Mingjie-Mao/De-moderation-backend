#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BACKUP_DIR=${BACKUP_DIR:-"$ROOT_DIR/backups"}
RETENTION_DAYS=${RETENTION_DAYS:-14}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$BACKUP_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  . "$ROOT_DIR/.env"
  set +a
fi

DB_FILE="$BACKUP_DIR/campusguard-db-$STAMP.dump"
MEDIA_FILE="$BACKUP_DIR/campusguard-media-$STAMP.tar.gz"

docker compose -f "$ROOT_DIR/docker-compose.prod.yml" exec -T postgres \
  pg_dump -U "${DB_USER:-campusguard}" -d "${DB_NAME:-campusguard}" \
  --format=custom --compress=9 --no-owner --no-privileges > "$DB_FILE"
docker compose -f "$ROOT_DIR/docker-compose.prod.yml" exec -T backend \
  tar -C /data -czf - media > "$MEDIA_FILE"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$DB_FILE" "$MEDIA_FILE" > "$BACKUP_DIR/campusguard-$STAMP.sha256"
else
  shasum -a 256 "$DB_FILE" "$MEDIA_FILE" > "$BACKUP_DIR/campusguard-$STAMP.sha256"
fi
find "$BACKUP_DIR" -type f -name 'campusguard-*' -mtime "+$RETENTION_DAYS" -delete
echo "Backup completed: $STAMP"
