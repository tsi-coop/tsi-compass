#!/usr/bin/env bash
# TSI Compass restore script.
#
# Default (safe) mode: restores the given backup into a throwaway, isolated
# Postgres container, runs sanity checks against it, then discards it.
# Your running deployment is never touched. Use this to verify a backup
# is good, e.g. as part of a periodic restore drill.
#
# --live mode: restores the given backup into the real running deployment
# (drops and recreates all objects, since backups are made with
# `pg_dump --clean --if-exists`). Destructive — requires typed confirmation.
#
# Usage:
#   ./scripts/restore.sh <backup_file.sql.gz>              # verify only (default)
#   ./scripts/restore.sh <backup_file.sql.gz> --live        # restore into the live deployment
#
# Configure via environment variables, or scripts/backup.env (auto-loaded if present).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/backup.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/backup.env"
  set +a
fi

BACKUP_FILE="${1:-}"
MODE="${2:-}"

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "Usage: $0 <backup_file.sql.gz> [--live]" >&2
  exit 1
fi

DB_CONTAINER="${DB_CONTAINER:-tsi_compass_db}"
POSTGRES_DB="${POSTGRES_DB:-tsi_compass}"
POSTGRES_USER="${POSTGRES_USER:-tsi_admin}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:15-alpine}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# Runs a handful of sanity checks against a target container/db/user and
# prints the results: table count, row counts for a few key tables.
run_checks() {
  local container="$1" db="$2" user="$3"
  log "Checking restored data in '$container'..."
  docker exec -u postgres "$container" psql -U "$user" -d "$db" -t -c \
    "SELECT 'tables: ' || count(*) FROM information_schema.tables WHERE table_schema='public';"
  for t in users system_audit_trail helpdesk_tickets risks assets; do
    docker exec -u postgres "$container" psql -U "$user" -d "$db" -t -c \
      "SELECT '$t: ' || count(*) || ' rows' FROM $t;" 2>/dev/null || echo "  $t: table not found"
  done
}

if [ "$MODE" = "--live" ]; then
  echo "!! This will DROP and RECREATE all objects in the LIVE '$POSTGRES_DB' database"
  echo "!! in container '$DB_CONTAINER', replacing them with the contents of:"
  echo "!!   $BACKUP_FILE"
  read -r -p "Type RESTORE to continue: " CONFIRM
  if [ "$CONFIRM" != "RESTORE" ]; then
    echo "Aborted."
    exit 1
  fi

  if ! docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" >/dev/null 2>&1; then
    echo "ERROR: container '$DB_CONTAINER' is not running." >&2
    exit 1
  fi

  log "Restoring into live database '$POSTGRES_DB'..."
  gunzip -c "$BACKUP_FILE" | docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"

  run_checks "$DB_CONTAINER" "$POSTGRES_DB" "$POSTGRES_USER"
  log "Live restore complete."

else
  CHECK_CONTAINER="tsi_compass_restore_check"
  docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true

  log "Starting throwaway Postgres container '$CHECK_CONTAINER' for verification..."
  docker run -d --name "$CHECK_CONTAINER" \
    -e POSTGRES_DB=verify -e POSTGRES_USER=verify -e POSTGRES_PASSWORD=verify \
    "$POSTGRES_IMAGE" >/dev/null

  cleanup() { docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true; }
  trap cleanup EXIT

  log "Waiting for it to become ready..."
  for _ in $(seq 1 30); do
    if docker exec "$CHECK_CONTAINER" pg_isready -U verify -d verify >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done

  log "Restoring '$BACKUP_FILE' into it..."
  gunzip -c "$BACKUP_FILE" | docker exec -i "$CHECK_CONTAINER" psql -U verify -d verify >/dev/null

  run_checks "$CHECK_CONTAINER" verify verify

  log "Verification complete — backup is restorable. Throwaway container discarded, live deployment untouched."
fi
