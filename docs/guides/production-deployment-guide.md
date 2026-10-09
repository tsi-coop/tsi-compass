# Production Deployment Guide

Checklist for running TSI Compass for real. The compose defaults are for local use only.

## 1. Secrets
Set these before first boot and keep them out of version control. Change them from the development placeholders.

| Variable | Notes |
|---|---|
| `JWT_SECRET` | Long random string. Changing it signs everyone out. |
| `DB_ENCRYPTION_KEY` | Encrypts user email and phone columns. **Losing or changing it makes those values unreadable**, so back it up separately from the database. |
| `TSI_LOOKUP_SALT` | Deterministic lookups. Do not change after data exists. |
| `POSTGRES_PASSWD` | Database password. |
| `TSI_BOOTSTRAP_TOKEN` | Only for creating the first admin at `/setup/`. Generate with `openssl rand -hex 32`, then unset or rotate it. |

## 2. Network and TLS
- Compass serves plain HTTP on port 8080 inside the container. **Terminate TLS in front of it** (nginx, Caddy, a cloud load balancer).
- Set `ALLOWED_ORIGINS` to your public HTTPS origin.
- Do not publish the Postgres port. Remove or restrict the `ports:` mapping on `postgres_db`; the app reaches it over the internal Docker network.
- Keep the console on an internal network where you can. If the vendor portal is on, only `/vendorportal/`, `/index.html` and `/api/` need to be reachable from outside.

## 3. Vendor portal (optional)
Off by default. Turn it on with `TSI_VENDOR_PORTAL_ENABLED=true` and restart. See the [Vendor Portal Guide](vendor-portal-guide.md). Compass sends no email, so give vendors their credentials through another channel.

## 4. Host hardening
- Run Docker as a non-root user and keep the host patched.
- Use disk encryption on the host or volume that holds `postgres_data` and the exports volume.
- Give the Postgres container only the access it needs; use a dedicated database user.

## 5. Backups
Use `scripts/backup.sh` and `scripts/restore.sh` (see `scripts/README.md`). Schedule the backup with your own cron or systemd timer, copy dumps off the host (`BACKUP_DEST_DIR`), set `INCLUDE_EXPORTS=true` to also archive uploaded evidence and attachments, and **test a restore** on a spare machine. Store `DB_ENCRYPTION_KEY` and `JWT_SECRET` separately.

## 6. Upgrades
1. Back up the database and the exports volume.
2. Pull the new image (or rebuild).
3. Apply any new `db/NN_*.sql` scripts that your database has not yet run, in order, with `ON_ERROR_STOP=1`.
4. Restart and sign in as each role to check access.

## 7. First-time configuration
After setup: create users and set roles under Platform and Access, review the role matrix, set Business Settings (including whether Manager approval is required), import or define frameworks, and review the seeded questionnaire and detection rules with your own counsel and security team.

## 8. Things Compass does not do
No built-in TLS, multi-factor authentication or SSO, no scheduler for timed jobs, and no outbound email. Plan for these at the infrastructure layer.
