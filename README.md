# TSI Compass

**The beginner's open source IT GRC platform.**

TSI Compass is a self-hosted IT Governance, Risk & Compliance (GRC) tool built for organisations that are new to structured compliance practice. It covers the full IT GRC lifecycle: policies, risk registers, controls, audits, incidents, and reporting, behind a clean web console with an immutable audit trail on every action.

---

## Features

| Module | What it covers |
|--------|---------------|
| **Platform & Access** | Organisation structure, departments, user provisioning, role-based access control, API key management, and a full platform audit trail |
| **Governance & Policy** | Policy library, governance committees, and attestation workflows |
| **Risk & Vulnerability** | Risk register, VAPT tracking, risk scoring, and treatment plans |
| **Compliance & Controls** | Controls register, framework mappings (ISO 27001, SOC 2, etc.), and exception management |
| **Audit & Evidence** | Audit scheduling, findings, and an evidence locker |
| **IT Operations** | Asset inventory, change management, vendor register, and help desk |
| **Data Register** | Self-declared data discovery and classification register, with optional links to IT assets and vendors |
| **Incidents** | Incident register, knowledge base, and staff training records |
| **Reports** | Exportable compliance and risk summary reports |

**Authentication and access control**

- Session-based JWT login with per-module RBAC (`ADMIN`, `RISK_OWNER`, `COMPLIANCE_OFFICER`, `INTERNAL_AUDITOR`, `IT_STAFF`, `USER`)
- Machine-to-machine access via API key + secret pairs (key and secret hashed at rest; plain values shown once at creation)
- Per-user 5-word recovery passphrase for self-service password recovery
- Every action written to an immutable `system_audit_trail` table

---

## Soft Launch

Read the launch post: [TSI Compass - The Beginner's Open Source GRC Platform](https://techadvisory.substack.com/p/tsi-compass-the-beginners-open-source)

---

## Video Demos

| Demo | Link |
|------|------|
| **Installation Walkthrough** | [https://youtu.be/WRR5JjrhSmY](https://youtu.be/WRR5JjrhSmY) |
| **Functional Walkthrough** | [https://youtu.be/bVPx1KHzx0w](https://youtu.be/bVPx1KHzx0w) |

---

## Changelog

### v0.2

- **Data Register module** - new self-declared data discovery and classification register (`data_assets` table), tracking what data exists, where it lives, its category and sensitivity, and a `DISCOVERED` → `CLASSIFIED` → `REVIEWED` review workflow. Entries can optionally link to an existing IT asset or vendor instead of re-entering ownership details.
- Added a **Data Register** console page and nav entry (positioned after IT Operations, since records commonly link to assets/vendors managed there).
- New `data` module added to the RBAC permission matrix (`ADMIN`: full access, `GRC_OFFICER`: read/write, `IT_STAFF`: no access).
- Schema split: `db/init.sql` renamed to `db/01_init.sql`; Data Register tables and seed permissions now live in their own `db/02_data_register.sql`, applied after the base schema.

---

## Quick start

**Prerequisites:** Docker and Docker Compose.

```bash
git clone https://github.com/tsi-coop/tsi-compass.git
cd tsi-compass
docker compose up -d
```
Once the installation is complete, head to **http://localhost:8085/tour** for an guided introduction to the platform. It walks you through each module and is the best starting point before exploring the console.

### Environment variables

All secrets have safe local defaults. Override them for any non-local deployment.

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_DB` | `tsi_compass` | Database name |
| `POSTGRES_USER` | `tsi_admin` | Database user |
| `POSTGRES_PASSWD` | `secure_dev_password` | Database password |
| `JWT_SECRET` | *(dev placeholder)* | Secret used to sign JWTs (**change in production**) |
| `DB_ENCRYPTION_KEY` | *(dev placeholder)* | Key for field-level encryption (**change in production**) |
| `TSI_LOOKUP_SALT` | *(dev placeholder)* | Salt for deterministic lookups (**change in production**) |
| `APP_PORT_MAP` | `8085:8080` | Host:container port mapping |
| `DB_PORT_MAP` | `5437:5432` | PostgreSQL port mapping |
| `TSI_EXPORT_PATH` | `/var/lib/tsi-compass/exports/` | Report export directory |
| `ALLOWED_ORIGINS` | `http://localhost:8085` | CORS allowed origin |

---

## Project structure

```
tsi-compass/
├── db/
│   ├── 01_init.sql                # Full schema (tables, triggers, seed data)
│   └── 02_data_register.sql      # Data Register module schema (applied after 01_init.sql)
├── src/
│   └── org/tsicoop/compass/
│       ├── framework/            # Servlet filter, routing, JWT, DB pool, helpers
│       └── service/v1/           # One class per API module (Platform, Risk, Controls...)
├── web/
│   ├── index.html                # Login page
│   ├── password-reset.html       # Self-service password recovery
│   ├── setup/                    # First-time setup wizard
│   ├── tour/                     # Onboarding guide and API reference
│   ├── console/                  # Authenticated GRC console (all module pages + rbac.js)
│   └── WEB-INF/
│       └── _processor.tsi        # URL to Java class routing table
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

---

## API

All API calls are `POST /api/v1/{module}` with `Content-Type: application/json`.

**Authentication** (machine/integration use):
```
X-API-Key: <key>
X-API-Secret: <secret>
```

Every request body includes a `_func` field that selects the operation:
```json
{ "_func": "list_risks", "status": "OPEN" }
```

See **`/tour/api.html`** in the running app for a full reference of every module and function.

---

## Password recovery

If a user forgets their password:

1. **Admin resets it directly** - Platform & Access > User Management > Reset Password
2. **Self-service** - if the admin has set a recovery passphrase for the user (User Management > "Set Key"), the user can recover their own access at `/password-reset.html` by entering their email and 5-word passphrase

---

## License & Contributions

This project is fully open-source and distributed under the Apache 2.0 License. You are completely free to fork, modify, and customize the codebase to fit your specific technical or enterprise needs without any restriction.

**Contributing Back to the Main Project**

If you have built an optimization, bug fix, or feature extension that you believe would add value to the core platform, we would love to review it. To ensure the main repository remains highly stable and securely managed, direct commits to the main branch are restricted.

If you wish to give back your changes to the project, please follow this process:

**Email the Repository Owner:** Send a brief summary of your modifications and a link to your code branch directly to admin@tsicoop.org.

Every contribution is manually evaluated for architectural alignment, readability, and long-term maintenance impact before integration. Thank you for respecting this workflow and helping us maintain a clean, resilient core!

