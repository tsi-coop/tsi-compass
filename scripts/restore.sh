#!/usr/bin/env bash
# TSI Compass restore script.
#
# Default (safe) mode: restores the given backup into a throwaway, isolated
# Postgres container and a throwaway directory for uploaded files, runs
# sanity checks against both, then discards them. Your running deployment
# is never touched. Use this to verify a backup is good, e.g. as part of a
# periodic restore drill.
#
# --live mode: restores into the real running deployment. The database is
# dropped and recreated (backups are made with `pg_dump --clean --if-exists`).
# The uploaded-files volume (policies, evidence, ticket & change-request
# attachments, exports), if a matching archive is found or given, has its
# current contents moved aside to a timestamped '.pre-restore-<ts>' folder
# inside the same volume (not deleted) and replaced with the archive's
# contents. Destructive — requires typed confirmation.
#
# Usage:
#   ./scripts/restore.sh <backup_file.sql.gz> [uploads_file.tar.gz] [--live]
#
# If uploads_file.tar.gz is omitted, the script looks next to
# <backup_file.sql.gz> for a matching 'tsi_compass_exports_<timestamp>.tar.gz'
# (the filename backup.sh produces) and uses it automatically if found.
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

BACKUP_FILE=""
UPLOADS_FILE=""
MODE=""
for arg in "$@"; do
  case "$arg" in
    --live) MODE="--live" ;;
    *.tar.gz) UPLOADS_FILE="$arg" ;;
    *) BACKUP_FILE="$arg" ;;
  esac
done

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "Usage: $0 <backup_file.sql.gz> [uploads_file.tar.gz] [--live]" >&2
  exit 1
fi

if [ -n "$UPLOADS_FILE" ] && [ ! -f "$UPLOADS_FILE" ]; then
  echo "ERROR: uploads file '$UPLOADS_FILE' not found." >&2
  exit 1
fi

# Auto-detect the matching uploads archive next to the db backup, if not given explicitly.
if [ -z "$UPLOADS_FILE" ]; then
  BACKUP_DIR="$(cd "$(dirname "$BACKUP_FILE")" && pwd)"
  BACKUP_BASENAME="$(basename "$BACKUP_FILE")"
  if [[ "$BACKUP_BASENAME" =~ ^tsi_compass_([0-9]{8}_[0-9]{6})\.sql\.gz$ ]]; then
    TS="${BASH_REMATCH[1]}"
    CANDIDATE="$BACKUP_DIR/tsi_compass_exports_${TS}.tar.gz"
    if [ -f "$CANDIDATE" ]; then
      UPLOADS_FILE="$CANDIDATE"
      echo "Found matching uploaded-files archive: $UPLOADS_FILE"
    fi
  fi
fi

DB_CONTAINER="${DB_CONTAINER:-tsi_compass_db}"
POSTGRES_DB="${POSTGRES_DB:-tsi_compass}"
POSTGRES_USER="${POSTGRES_USER:-tsi_admin}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:15-alpine}"
EXPORTS_VOLUME="${EXPORTS_VOLUME:-tsi-compass_tsi_reports_data}"

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

# Prints file counts for a restored uploads tree, given a host directory.
run_upload_checks() {
  local dir="$1"
  log "Checking restored files in '$dir'..."
  echo "  total files: $(find "$dir" -type f | wc -l)"
  for sub in policies evidence ticket_attachments change_request_attachments; do
    local n
    n="$(find "$dir/$sub" -type f 2>/dev/null | wc -l)"
    echo "  $sub: $n files"
  done
}

if [ "$MODE" = "--live" ]; then
  echo "!! This will DROP and RECREATE all objects in the LIVE '$POSTGRES_DB' database"
  echo "!! in container '$DB_CONTAINER', replacing them with the contents of:"
  echo "!!   $BACKUP_FILE"
  if [ -n "$UPLOADS_FILE" ]; then
    echo "!!"
    echo "!! It will also REPLACE the contents of the uploaded-files volume"
    echo "!! '$EXPORTS_VOLUME' with the contents of:"
    echo "!!   $UPLOADS_FILE"
    echo "!! (the volume's current contents are moved aside into a"
    echo "!! '.pre-restore-<timestamp>' folder inside the volume, not deleted)"
  else
    echo "!!"
    echo "!! No matching uploaded-files archive was found/given — the uploaded-files"
    echo "!! volume '$EXPORTS_VOLUME' will be left untouched."
  fi
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

  if [ -n "$UPLOADS_FILE" ]; then
    if ! docker volume inspect "$EXPORTS_VOLUME" >/dev/null 2>&1; then
      echo "ERROR: volume '$EXPORTS_VOLUME' does not exist. Set EXPORTS_VOLUME to match your docker-compose project name." >&2
      exit 1
    fi
    RESTORE_TS="$(date +%Y%m%d_%H%M%S)"
    UPLOADS_ABS_DIR="$(cd "$(dirname "$UPLOADS_FILE")" && pwd)"
    UPLOADS_BASENAME="$(basename "$UPLOADS_FILE")"
    log "Moving aside current contents of '$EXPORTS_VOLUME' into '.pre-restore-${RESTORE_TS}'..."
    docker run --rm \
      -v "${EXPORTS_VOLUME}:/data" \
      alpine sh -c "
        set -e
        mkdir -p \"/data/.pre-restore-${RESTORE_TS}\"
        for f in /data/*; do
          [ -e \"\$f\" ] || continue
          case \"\$(basename \"\$f\")\" in .pre-restore-*) continue ;; esac
          mv \"\$f\" \"/data/.pre-restore-${RESTORE_TS}/\"
        done
      "
    log "Extracting uploaded-files archive into '$EXPORTS_VOLUME'..."
    docker run --rm \
      -v "${EXPORTS_VOLUME}:/data" \
      -v "${UPLOADS_ABS_DIR}:/backup:ro" \
      alpine tar xzf "/backup/${UPLOADS_BASENAME}" -C /data

    log "Checking restored files in volume '$EXPORTS_VOLUME'..."
    docker run --rm -v "${EXPORTS_VOLUME}:/data:ro" alpine sh -c '
      echo "  total files: $(find /data -type f | wc -l)"
      for sub in policies evidence ticket_attachments change_request_attachments; do
        n=$(find "/data/$sub" -type f 2>/dev/null | wc -l)
        echo "  $sub: $n files"
      done
    '
  fi

  log "Live restore complete."

else
  CHECK_CONTAINER="tsi_compass_restore_check"
  docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true

  UPLOADS_CHECK_DIR=""
  cleanup() {
    docker rm -f "$CHECK_CONTAINER" >/dev/null 2>&1 || true
    if [ -n "$UPLOADS_CHECK_DIR" ] && [ -d "$UPLOADS_CHECK_DIR" ]; then
      rm -rf "$UPLOADS_CHECK_DIR"
    fi
  }
  trap cleanup EXIT

  log "Starting throwaway Postgres container '$CHECK_CONTAINER' for verification..."
  docker run -d --name "$CHECK_CONTAINER" \
    -e POSTGRES_DB=verify -e POSTGRES_USER=verify -e POSTGRES_PASSWORD=verify \
    "$POSTGRES_IMAGE" >/dev/null

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

  if [ -n "$UPLOADS_FILE" ]; then
    UPLOADS_CHECK_DIR="$(mktemp -d)"
    log "Extracting '$UPLOADS_FILE' into a throwaway directory for verification..."
    tar xzf "$UPLOADS_FILE" -C "$UPLOADS_CHECK_DIR"
    run_upload_checks "$UPLOADS_CHECK_DIR"
  else
    log "No uploaded-files archive found/given — skipping uploads verification."
  fi

  log "Verification complete — backup is restorable. Throwaway container/directory discarded, live deployment untouched."
fi
