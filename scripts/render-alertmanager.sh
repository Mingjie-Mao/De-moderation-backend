#!/bin/sh
# Fills in deploy/observability/alertmanager.yml from its template and checks it.
#
# Alertmanager is the one component here that does not read environment
# variables from its own config, so this substitution has to happen before the
# container starts. Doing it in a script rather than by hand means the rendered
# file cannot drift from the template, and means a missing variable stops the
# release instead of shipping the literal string "${ALERT_TO}" as an address.
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEMPLATE="$ROOT_DIR/deploy/observability/alertmanager.yml.template"
OUTPUT="$ROOT_DIR/deploy/observability/alertmanager.yml"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  . "$ROOT_DIR/.env"
  set +a
fi

REQUIRED="ALERT_SMTP_HOST ALERT_SMTP_PORT ALERT_SMTP_USERNAME ALERT_SMTP_PASSWORD ALERT_FROM ALERT_TO"
MISSING=""
for name in $REQUIRED; do
  eval "value=\${$name:-}"
  if [ -z "$value" ]; then
    MISSING="$MISSING $name"
  fi
done
if [ -n "$MISSING" ]; then
  echo "Cannot render alertmanager.yml; these are unset:$MISSING" >&2
  echo "Set them in .env (see .env.example) and run this again." >&2
  exit 2
fi

# Critical alerts default to the same address as everything else. Splitting them
# is worth doing once there is somebody separate to wake up; until then, one
# address that works beats two where one is a placeholder.
ALERT_TO_CRITICAL=${ALERT_TO_CRITICAL:-$ALERT_TO}

# sed rather than envsubst: envsubst ships with gettext, which is not present on
# a stock macOS or on a slim CI image, and this substitution is six variables.
#
# Two escapings, in this order, because every value passes through two parsers on
# its way to Alertmanager. YAML is the second one and has to be prepared for
# first: the template quotes each value, and inside a double-quoted scalar a
# backslash opens an escape sequence and a quote closes the string. Without this
# an SMTP password containing either is rejected by amtool with "found unknown
# escape character" and a line number in a file the operator did not write, which
# is a long way from "your password has a backslash in it". sed is the first
# parser and the last escaping: in replacement text & means the whole match, a
# backslash escapes, and | is this script's own delimiter.
escape() {
  printf '%s' "$1" \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    | sed -e 's/[&|\\]/\\&/g'
}

sed \
  -e "s|\${ALERT_SMTP_HOST}|$(escape "$ALERT_SMTP_HOST")|g" \
  -e "s|\${ALERT_SMTP_PORT}|$(escape "$ALERT_SMTP_PORT")|g" \
  -e "s|\${ALERT_SMTP_USERNAME}|$(escape "$ALERT_SMTP_USERNAME")|g" \
  -e "s|\${ALERT_SMTP_PASSWORD}|$(escape "$ALERT_SMTP_PASSWORD")|g" \
  -e "s|\${ALERT_FROM}|$(escape "$ALERT_FROM")|g" \
  -e "s|\${ALERT_TO_CRITICAL}|$(escape "$ALERT_TO_CRITICAL")|g" \
  -e "s|\${ALERT_TO}|$(escape "$ALERT_TO")|g" \
  "$TEMPLATE" > "$OUTPUT.tmp"

# The file holds an SMTP password, so it is never world-readable, not even for
# the moment between writing and moving it.
chmod 600 "$OUTPUT.tmp"

# Checked by Alertmanager's own tool rather than by eye. A malformed receiver is
# the kind of mistake that is only discovered by the incident it fails to report.
ALERTMANAGER_IMAGE=${ALERTMANAGER_IMAGE:-prom/alertmanager:v0.28.1}
if command -v docker >/dev/null 2>&1; then
  if ! docker run --rm --entrypoint amtool \
      -v "$OUTPUT.tmp:/tmp/alertmanager.yml:ro" \
      "$ALERTMANAGER_IMAGE" check-config /tmp/alertmanager.yml; then
    rm -f "$OUTPUT.tmp"
    echo "Rendered config was rejected by amtool; nothing was written." >&2
    exit 1
  fi
else
  echo "docker not found; skipping the amtool check on the rendered config." >&2
fi

mv "$OUTPUT.tmp" "$OUTPUT"
echo "Wrote $OUTPUT (mode 600). Restart alertmanager to pick it up."
