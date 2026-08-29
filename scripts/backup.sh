#!/usr/bin/env bash
# TSI Compass daily backup script.
#
# Dumps the Postgres database from the running docker-compose stack and
# copies it to a destination directory (e.g. a NAS mount, external drive,
# or any other mounted/local path). Run manually, or
# schedule yourself with cron/systemd on the host — this script does not
# install any scheduler.
#
# Usage:
#   ./scripts/backup.sh
#
# Configure via environment variables, or copy scripts/backup.env.example
# to scripts/backup.env and edit it (auto-loaded if present).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/backup.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/backup.env"
  set +a
fi

# --- Configuration (override via env vars or scripts/backup.env) ---
DB_CONTAINER="${DB_CONTAINER:-tsi_compass_db}"
POSTGRES_DB="${POSTGRES_DB:-tsi_compass}"
POSTGRES_USER="${POSTGRES_USER:-tsi_admin}"

LOCAL_BACKUP_DIR="${LOCAL_BACKUP_DIR:-$HOME/tsi-compass-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

if [ -z "${BACKUP_DEST_DIR:-}" ]; then
  BACKUP_DEST_DIR="$LOCAL_BACKUP_DIR"
  echo "WARNING: BACKUP_DEST_DIR not set — backups will stay in '$LOCAL_BACKUP_DIR' only, not copied off-host. Set BACKUP_DEST_DIR (e.g. a NAS mount) to copy them elsewhere." >&2
fi

INCLUDE_EXPORTS="${INCLUDE_EXPORTS:-false}"
EXPORTS_VOLUME="${EXPORTS_VOLUME:-tsi-compass_tsi_reports_data}"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
DUMP_FILE="tsi_compass_${TIMESTAMP}.sql.gz"
EXPORTS_FILE="tsi_compass_exports_${TIMESTAMP}.tar.gz"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

mkdir -p "$LOCAL_BACKUP_DIR"

if [ ! -d "$BACKUP_DEST_DIR" ]; then
  echo "ERROR: BACKUP_DEST_DIR '$BACKUP_DEST_DIR' does not exist. If it's a NAS mount, is the share mounted?" >&2
  exit 1
fi

if ! docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" >/dev/null 2>&1; then
  echo "ERROR: container '$DB_CONTAINER' is not running." >&2
  exit 1
fi

log "Dumping database '$POSTGRES_DB' from container '$DB_CONTAINER'..."
docker exec "$DB_CONTAINER" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists \
  | gzip > "$LOCAL_BACKUP_DIR/$DUMP_FILE"

if [ "$INCLUDE_EXPORTS" = "true" ]; then
  log "Archiving exports volume '$EXPORTS_VOLUME'..."
  docker run --rm \
    -v "${EXPORTS_VOLUME}:/data:ro" \
    -v "$LOCAL_BACKUP_DIR:/backup" \
    alpine tar czf "/backup/$EXPORTS_FILE" -C /data .
fi

if [ "$(cd "$LOCAL_BACKUP_DIR" && pwd)" = "$(cd "$BACKUP_DEST_DIR" && pwd)" ]; then
  log "BACKUP_DEST_DIR is the same as LOCAL_BACKUP_DIR — skipping copy step."
else
  log "Copying backup(s) to destination directory '$BACKUP_DEST_DIR'..."
  cp -p "$LOCAL_BACKUP_DIR/$DUMP_FILE" "$BACKUP_DEST_DIR/"
  if [ "$INCLUDE_EXPORTS" = "true" ]; then
    cp -p "$LOCAL_BACKUP_DIR/$EXPORTS_FILE" "$BACKUP_DEST_DIR/"
  fi
fi

log "Pruning backups older than $RETENTION_DAYS days..."
find "$LOCAL_BACKUP_DIR" -maxdepth 1 -name 'tsi_compass_*' -mtime "+$RETENTION_DAYS" -delete
find "$BACKUP_DEST_DIR" -maxdepth 1 -name 'tsi_compass_*' -mtime "+$RETENTION_DAYS" -delete

log "Backup complete: $DUMP_FILE"
