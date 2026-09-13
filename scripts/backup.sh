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

# --- media, only where this script can see it ----------------------------
#
# The tarball covers /data/media inside the backend container, which is the
# whole of the media only while MEDIA_BACKEND is FILESYSTEM. Under S3 the images
# are in a bucket this script has no credentials for, and /data/media is an empty
# mounted volume: tarring it would succeed, produce a 100-byte archive, and leave
# a file named campusguard-media-<stamp>.tar.gz next to the dump. That file is
# the failure this script exists to prevent — a backup that reports success and
# stores nothing — so it is not written at all, and the reason is said out loud.
#
# Held as positional parameters rather than in one space-separated string, so a
# backup directory with a space in its path stays one path.
set -- "$DB_FILE"
if [ "${MEDIA_BACKEND:-FILESYSTEM}" = "FILESYSTEM" ]; then
  docker compose -f "$ROOT_DIR/docker-compose.prod.yml" exec -T backend \
    tar -C /data -czf - media > "$MEDIA_FILE"
  set -- "$@" "$MEDIA_FILE"
else
  echo "MEDIA_BACKEND=${MEDIA_BACKEND}: media lives in a bucket and is NOT in this backup." >&2
  echo "Cover it at the bucket: turn on object versioning and a lifecycle rule, or sync the" >&2
  echo "media prefix to a second bucket on a schedule. This script backs up the database only." >&2
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$@" > "$BACKUP_DIR/campusguard-$STAMP.sha256"
else
  shasum -a 256 "$@" > "$BACKUP_DIR/campusguard-$STAMP.sha256"
fi
# --- Off-site copy -------------------------------------------------------
#
# A backup on the machine being backed up is a copy, not a backup: it survives a
# dropped table and nothing else. The disk, the volume, the host and the account
# are all single points of failure that this loop is meant to remove, and none of
# them is removed by writing to ./backups.
#
# Optional, because it needs a bucket and credentials, and a local-only backup is
# still better than none. Set OFFSITE_BUCKET to turn it on.
if [ -n "${OFFSITE_BUCKET:-}" ]; then
  : "${OFFSITE_ACCESS_KEY:?OFFSITE_ACCESS_KEY is required when OFFSITE_BUCKET is set}"
  : "${OFFSITE_SECRET_KEY:?OFFSITE_SECRET_KEY is required when OFFSITE_BUCKET is set}"

  # The CLI runs in a container so the host needs no aws installation, and the
  # same command reaches AWS, R2 or any other S3-compatible endpoint.
  OFFSITE_ARGS=""
  if [ -n "${OFFSITE_ENDPOINT:-}" ]; then
    OFFSITE_ARGS="--endpoint-url $OFFSITE_ENDPOINT"
  fi

  for file in "$@" "$BACKUP_DIR/campusguard-$STAMP.sha256"; do
    docker run --rm \
      -e AWS_ACCESS_KEY_ID="$OFFSITE_ACCESS_KEY" \
      -e AWS_SECRET_ACCESS_KEY="$OFFSITE_SECRET_KEY" \
      -e AWS_DEFAULT_REGION="${OFFSITE_REGION:-auto}" \
      -v "$file:/upload/$(basename "$file"):ro" \
      "${AWS_CLI_IMAGE:-amazon/aws-cli:2.31.9}" \
      $OFFSITE_ARGS s3 cp "/upload/$(basename "$file")" \
      "s3://$OFFSITE_BUCKET/${OFFSITE_PREFIX:-campusguard}/$(basename "$file")"
  done

  # Read back what was written. An upload that reported success and stored
  # nothing is the failure mode that makes people think they have backups.
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="$OFFSITE_ACCESS_KEY" \
    -e AWS_SECRET_ACCESS_KEY="$OFFSITE_SECRET_KEY" \
    -e AWS_DEFAULT_REGION="${OFFSITE_REGION:-auto}" \
    "${AWS_CLI_IMAGE:-amazon/aws-cli:2.31.9}" \
    $OFFSITE_ARGS s3 ls "s3://$OFFSITE_BUCKET/${OFFSITE_PREFIX:-campusguard}/campusguard-db-$STAMP.dump" \
    | grep -q "campusguard-db-$STAMP.dump" \
    || { echo "Off-site copy is not readable back; treat this backup as local only." >&2; exit 1; }

  echo "Off-site copy verified in s3://$OFFSITE_BUCKET/${OFFSITE_PREFIX:-campusguard}/"
fi

find "$BACKUP_DIR" -type f -name 'campusguard-*' -mtime "+$RETENTION_DAYS" -delete
echo "Backup completed: $STAMP"
echo "A backup nobody has restored is a hypothesis. Run scripts/restore-drill.sh to test it."
