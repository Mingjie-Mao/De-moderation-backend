#!/bin/sh
# Restores a backup into a throwaway database and checks that what came back is
# usable. Never touches the running deployment.
#
# The gap this closes. scripts/backup.sh has been producing dumps, and
# scripts/restore.sh can put one back — over the live database, which means it
# has never been run except in an emergency, which means it has never been run.
# An untested backup is a belief about a file. This script turns the belief into
# a result, and it is safe to run on a Tuesday afternoon because it restores into
# a container it creates and destroys.
#
# Usage: scripts/restore-drill.sh [/path/to/campusguard-db-*.dump]
#        with no argument, the newest dump in ./backups
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BACKUP_DIR=${BACKUP_DIR:-"$ROOT_DIR/backups"}
IMAGE=${DRILL_IMAGE:-postgres:16.10-alpine}
CONTAINER=campusguard-restore-drill
DRILL_DB=drill
DRILL_USER=drill
DRILL_PASSWORD=drill

FAILURES=0
note() { printf '  %s\n' "$1"; }
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# --- pick the dump -------------------------------------------------------
if [ "$#" -ge 1 ]; then
  DUMP=$1
else
  DUMP=$(ls -t "$BACKUP_DIR"/campusguard-db-*.dump 2>/dev/null | head -1 || true)
fi
if [ -z "${DUMP:-}" ] || [ ! -f "$DUMP" ]; then
  echo "No dump to drill. Pass one as an argument, or run scripts/backup.sh first." >&2
  exit 2
fi

echo "Restore drill"
echo "============="
note "dump:  $DUMP"
note "size:  $(wc -c < "$DUMP" | tr -d ' ') bytes"
echo

# --- checksum, when the backup recorded one ------------------------------
STAMP=$(basename "$DUMP" | sed -e 's/^campusguard-db-//' -e 's/\.dump$//')
SUMS="$BACKUP_DIR/campusguard-$STAMP.sha256"
if [ -f "$SUMS" ]; then
  # Only this dump's line, because the media tarball may legitimately be gone
  # after the retention sweep while the dump is still here.
  if grep -F "$(basename "$DUMP")" "$SUMS" > /tmp/drill-sums.$$ 2>/dev/null; then
    if (cd "$(dirname "$DUMP")" && \
        { command -v sha256sum >/dev/null 2>&1 && sha256sum -c /tmp/drill-sums.$$ || shasum -a 256 -c /tmp/drill-sums.$$; }) >/dev/null 2>&1; then
      pass "checksum matches what the backup recorded"
    else
      fail "checksum does not match; the dump is corrupt or was rewritten"
    fi
  fi
  rm -f /tmp/drill-sums.$$
else
  note "no checksum file for this stamp; skipping that check"
fi

# --- a database nobody is using -----------------------------------------
cleanup
docker run -d --name "$CONTAINER" \
  -e POSTGRES_DB="$DRILL_DB" \
  -e POSTGRES_USER="$DRILL_USER" \
  -e POSTGRES_PASSWORD="$DRILL_PASSWORD" \
  "$IMAGE" >/dev/null

printf 'waiting for the drill database'
ready=0
i=0
while [ "$i" -lt 60 ]; do
  if docker exec "$CONTAINER" pg_isready -U "$DRILL_USER" -d "$DRILL_DB" >/dev/null 2>&1; then
    ready=1
    break
  fi
  printf '.'
  sleep 1
  i=$((i + 1))
done
printf '\n'
if [ "$ready" -ne 1 ]; then
  fail "drill database never became ready"
  exit 1
fi

sql() {
  docker exec -i "$CONTAINER" psql -U "$DRILL_USER" -d "$DRILL_DB" -tAc "$1" 2>/dev/null | tr -d ' '
}

# --- the restore itself --------------------------------------------------
#
# The dump is owner-agnostic (backup.sh passes --no-owner --no-privileges), so a
# different role name here is the point: a restore that only works as the
# production user is a restore that needs production to exist.
if docker exec -i "$CONTAINER" pg_restore -U "$DRILL_USER" -d "$DRILL_DB" \
    --no-owner --no-privileges < "$DUMP" > /tmp/drill-restore.$$ 2>&1; then
  pass "pg_restore completed"
else
  fail "pg_restore reported errors"
  sed -n '1,20p' /tmp/drill-restore.$$ | sed 's/^/      /'
fi
rm -f /tmp/drill-restore.$$

# --- is the schema the one this code expects? ---------------------------
RESTORED_VERSION=$(sql "select max(version::numeric) from flyway_schema_history where success" || true)
REPO_VERSION=$(ls "$ROOT_DIR"/src/main/resources/db/migration/V*__*.sql 2>/dev/null \
  | sed -e 's#.*/V##' -e 's#__.*##' | sort -n | tail -1)

if [ -z "$RESTORED_VERSION" ]; then
  fail "no flyway_schema_history in the restored database"
elif [ "$RESTORED_VERSION" = "$REPO_VERSION" ]; then
  pass "schema version $RESTORED_VERSION matches the migrations in this checkout"
else
  # Not a failure. A backup taken before the last release is older than the
  # code by design, and Flyway will migrate it forward on startup. It is worth
  # saying out loud, because it is the difference between a restore that serves
  # traffic immediately and one that runs a migration first.
  note "restored schema is V$RESTORED_VERSION, this checkout is V$REPO_VERSION"
  pass "schema version present; Flyway will migrate V$RESTORED_VERSION -> V$REPO_VERSION on startup"
fi

FAILED_MIGRATIONS=$(sql "select count(*) from flyway_schema_history where not success" || true)
if [ "$FAILED_MIGRATIONS" = "0" ]; then
  pass "no failed migrations recorded in the backup"
elif [ -z "$FAILED_MIGRATIONS" ]; then
  # An unanswerable query is a different problem from a bad answer, and saying
  # "contains  failed migrations" would report the first as the second.
  fail "could not read flyway_schema_history from the restored database"
else
  fail "the backup contains $FAILED_MIGRATIONS failed migration(s)"
fi

# --- is the data there? -------------------------------------------------
#
# Counts rather than a comparison against production: this script has no access
# to production, and should not. What it can establish is that the tables the
# application cannot start without are present and that the schema is coherent.
for table in users posts comments reports moderation_cases audit_log moderation_rules; do
  COUNT=$(sql "select count(*) from $table" || true)
  if [ -z "$COUNT" ]; then
    fail "table $table is missing from the restored database"
  else
    note "$table: $COUNT row(s)"
  fi
done

RULES=$(sql "select count(*) from moderation_rules" || echo 0)
if [ "${RULES:-0}" -ge 1 ]; then
  pass "moderation rules restored ($RULES)"
else
  fail "no moderation rules in the restored database; the rule engine would pass everything"
fi

# Constraints and indexes are the part of a dump most likely to be quietly
# missing, and the part nothing notices until two rows collide months later.
CONSTRAINTS=$(sql "select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid join pg_namespace n on n.oid = t.relnamespace where n.nspname = 'public'" || echo 0)
if [ "${CONSTRAINTS:-0}" -ge 1 ]; then
  pass "constraints restored ($CONSTRAINTS)"
else
  fail "no constraints in the restored database"
fi

ORPHAN_CASES=$(sql "select count(*) from moderation_cases mc where not exists (select 1 from reports r where r.case_id = mc.id)" || true)
if [ "$ORPHAN_CASES" = "0" ]; then
  pass "every restored case still has the reports that opened it"
elif [ -z "$ORPHAN_CASES" ]; then
  fail "could not run the referential check; the tables it needs are not there"
else
  # Not a failure on its own: a case whose reports were hard-deleted is a
  # historical record, not a corrupt restore. Worth seeing.
  note "cases with no report: $ORPHAN_CASES"
  pass "referential check ran"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "Drill passed. This dump restores into an empty database and the result is coherent."
  echo "What it does not prove: that the media tarball matches it. Restore that alongside"
  echo "the dump in a real recovery, and check a post's image renders."
  exit 0
fi

echo "Drill failed with $FAILURES problem(s). Do not rely on this backup."
exit 1
