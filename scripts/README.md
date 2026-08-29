# Scripts

Operational scripts for a self-hosted TSI Compass deployment. Nothing here runs automatically — you invoke them yourself, or wire them into your own cron/systemd.

## backup.sh

Dumps the Postgres database from the running docker-compose stack and copies it to a destination directory (e.g. a NAS mount, external drive, or any other mounted/local path).

### Setup

```bash
cp scripts/backup.env.example scripts/backup.env
```

Edit `scripts/backup.env` (gitignored, host-specific):

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_CONTAINER` | `tsi_compass_db` | Docker container running Postgres |
| `POSTGRES_DB` | `tsi_compass` | Database name (must match your deployment) |
| `POSTGRES_USER` | `tsi_admin` | Database user (must match your deployment) |
| `LOCAL_BACKUP_DIR` | `~/tsi-compass-backups` | Local staging directory for the dump |
| `BACKUP_DEST_DIR` | *(falls back to `LOCAL_BACKUP_DIR`)* | Destination directory backups are copied to. Must already exist (mounted, if it's a NAS/network share). If left unset, backups just stay in `LOCAL_BACKUP_DIR` and the script prints a warning — set this to actually get an off-host copy. |
| `RETENTION_DAYS` | `14` | Days of backups to keep, in both `LOCAL_BACKUP_DIR` and `BACKUP_DEST_DIR` |
| `INCLUDE_EXPORTS` | `false` | Also archive the `tsi_reports_data` volume (generated reports) |
| `EXPORTS_VOLUME` | `tsi-compass_tsi_reports_data` | Docker volume name for exports, if included |

Values can also be set as plain environment variables instead of using `backup.env`.

### Run

```bash
./scripts/backup.sh
```

The script fails loudly (non-zero exit) if `BACKUP_DEST_DIR` is set but doesn't exist (e.g. share not mounted) or if the DB container isn't running, so it's safe to chain after a mount check in your own scheduler. If `BACKUP_DEST_DIR` is unset, the script doesn't fail — it just backs up to `LOCAL_BACKUP_DIR` and warns that nothing left the host.

### Scheduling

The script doesn't install a scheduler. To run it daily, add your own crontab entry:

```bash
crontab -e
# Daily at 2 AM, logging to the local staging directory:
0 2 * * * /path/to/tsi-compass/scripts/backup.sh >> /path/to/tsi-compass-backups/backup.log 2>&1
```

Backups are made with `pg_dump --clean --if-exists`, so each dump file is self-contained: restoring it drops and recreates every object, no separate `DROP DATABASE` step needed.

## restore.sh

Restores a backup and checks it. Two modes:

### Verify (default, safe)

Restores the backup into a throwaway, isolated Postgres container, runs sanity checks (table count, row counts for `users`, `system_audit_trail`, `helpdesk_tickets`, `risks`, `assets`), then discards the container. Your live deployment is never touched — use this to confirm a backup is actually restorable, e.g. as a periodic drill after `backup.sh` runs.

```bash
./scripts/restore.sh /path/to/tsi_compass_20260829_020000.sql.gz
```

Expected output ends with a per-table row count and `Verification complete — backup is restorable.`

### Live restore

Restores the backup into the real running deployment, replacing all current data. Destructive — prompts for typed confirmation (`RESTORE`) before doing anything.

```bash
./scripts/restore.sh /path/to/tsi_compass_20260829_020000.sql.gz --live
```

Consider stopping the `jetty_app` container first (`docker compose stop jetty_app`) so nothing writes to the database mid-restore, then start it again once the script finishes.
