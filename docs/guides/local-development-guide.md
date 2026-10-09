# Local Development Guide

Build and run TSI Compass from source.

## Prerequisites
- JDK 17 and Maven 3.8+
- Docker and Docker Compose (for PostgreSQL, and for running the packaged app)

## Option 1: Docker Compose with the published image
```bash
echo "TSI_BOOTSTRAP_TOKEN=$(openssl rand -hex 32)" > .env
docker compose up -d
```
Open `http://localhost:8085/setup/`, paste the token, create the first admin, then sign in at `http://localhost:8085/`. A fresh database runs every script in `db/` in numeric order (`01_init.sql` to `22_vendor_portal.sql`).

## Option 2: Build your own image
```bash
mvn clean package            # produces target/tsi-compass.war
```
In `docker-compose.yml`, comment out `image:` and uncomment `build: .` under `jetty_app`, then `docker compose up -d --build`. The `Dockerfile` copies `target/tsi-compass.war` into `jetty:11-jdk17` as the root context.

## Working on the database
- Scripts in `db/` are mounted read-only into the Postgres container and run **only when the data volume is empty**. To re-initialise: `docker compose down -v && docker compose up -d` (this deletes all data).
- To apply a new script to a running database:
  ```bash
  docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass -v ON_ERROR_STOP=1 < db/NN_name.sql
  ```
- Connect with `docker exec -it tsi_compass_db psql -U tsi_admin -d tsi_compass`. The compose file also publishes Postgres on `5437` by default.

## Working on the front end
`web/` is plain HTML and JavaScript with no build step. Edit a page and rebuild the WAR (or mount `web/` into the container) to see it. Console pages share the same chrome and load `web/console/rbac.js` to hide what a role cannot use.

## Adding a module
Follow section 9 of the [System Design](../architecture/system-design.md): a numbered SQL script, a service class, a `_processor.tsi` route, a `SERVICE_MODULE_MAP` entry, a console page, an `rbac.js` entry, audit logging, and a guide.

## Useful checks
- A fresh install should list 3 default frameworks (ISO 27001, DPDP, SOC 2) under Compliance and Controls.
- Sign in as each role (`ADMIN`, `GRC_OFFICER`, `IT_STAFF`) and confirm the menu matches the role matrix in Platform and Access.
- API reference for every module and function: `/tour/api.html` in the running app.

## Demo data
See [`examples/datagov`](../../examples/datagov/) for the Varam Data Governance scenario, including the discovery-first flow.
