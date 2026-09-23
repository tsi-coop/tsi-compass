# TSI Compass

**The beginner's open source IT GRC and ITSM platform.**

TSI Compass is a beginner-friendly, self-hosted IT GRC platform built right on top of the daily IT work you already do. It connects your compliance needs (policies, risk registers, controls, audits, incidents, and reports) directly to your helpdesk tickets, change requests, and asset inventory. Everything sits behind a clean web console with a tamper-proof log for every action.

---

## Features

### For IT & Information Security Teams

The console, used by `ADMIN`, `GRC_OFFICER`, and `IT_STAFF`.

| Module | What it covers |
|--------|---------------|
| **Platform & Access** | Organisation structure, departments, user provisioning, role-based access control, API key management, and a full platform audit trail |
| **Governance & Policy** | Policy library, governance committees, and attestation workflows |
| **Risk & Vulnerability** | Risk register, VAPT tracking, risk scoring, and treatment plans |
| **Compliance & Controls** | Controls register, framework mappings (ISO 27001, SOC 2, etc.), and exception management |
| **Audit & Evidence** | Audit scheduling, findings, and an evidence locker |
| **IT Operations** | Asset inventory, change management, vendor register, help desk, plus a Software Inventory (SBOM) and Crypto Inventory (CBOM) of the components and cryptographic assets that make up your software estate, optionally linked to IT assets, with bulk import |
| **Data Governance** | Data Principal registry (who your data is about, e.g. Customer, Employee, Patient), a self-declared data asset inventory (what data exists, where, purpose, retention, recipients, and access) tagged by principal, a compliance gap check per asset against mapped framework requirements, data flow mapping (including unapproved/shadow-IT channels like personal WhatsApp or email), and a RoPA (Record of Processing Activities) export filterable by framework or by data principal |
| **Incidents** | Incident register, knowledge base, and staff training records |
| **Reports** | Exportable compliance and risk summary reports |

### For End Users

A separate Self-Service Portal, used by `USER` (Employee) and `SUPERVISOR` (Manager) - no console access.

| Role | What they can do |
|------|-------------------|
| **Employee** | Submit helpdesk tickets and change requests, track their status, and complete policy attestations and training |
| **Manager** | Everything an Employee can do, plus provision and manage their own team and approve or reject their team's tickets and change requests - optionally required, deployment-wide, before IT/GRC ever sees them. Managers can themselves be organized into up to 5 levels (Manager-1 through Manager-5) reporting up a chain, e.g. for escalation or multi-tier approval |

**Authentication and access control**

- Session-based JWT login with per-module RBAC (`ADMIN`, `GRC_OFFICER`, `IT_STAFF`, `SUPERVISOR` i.e. Manager, `USER` i.e. Employee)
- Optional deployment-wide **Manager approval** setting (Platform & Access > Business Settings): when enabled, an Employee's tickets and change requests are held for their assigned Manager to approve or reject before IT/GRC staff can see them, flowing up the Manager hierarchy as needed
- Machine-to-machine access via API key + secret pairs (key and secret hashed at rest; plain values shown once at creation)
- Per-user 5-word recovery passphrase for self-service password recovery
- Every action written to an immutable `system_audit_trail` table


---

## Video Demos

| Demo | Link |
|------|------|
| **Installation Walkthrough** | [https://youtu.be/WRR5JjrhSmY](https://youtu.be/WRR5JjrhSmY) |
| **Functional Overview** | [https://youtu.be/bVPx1KHzx0w](https://youtu.be/bVPx1KHzx0w) |
| **Ticketing System Demo - Supervisor Approval** | [https://youtu.be/SBxnE55q3Z8](https://youtu.be/SBxnE55q3Z8) |
---

## Changelog

### v0.4.3

- **Data Governance module** - a new top-level console section replacing the old standalone Data Register page, built around four pieces:
  - **Data Principals** - an org-editable register of who your data is about (Customer, Employee, Patient, Vendor Contact, Prospect by default, and freely extendable), tagged onto data assets many-to-many.
  - **Purpose, retention, recipients, access** - the Data Register's Add/Edit modal now captures purpose, processing basis, retention period, deletion trigger, and controller/processor role, plus who a data asset is shared with and who has access to it.
  - **Gap assessment** - every data asset is checked live against four conditions (reviewed, processing basis recorded, retention recorded, mapped to a compliance requirement), surfaced as a Gaps stat card, a "Has gaps" filter, and a pass/fail checklist on each asset.
  - **Data Flow Mapping** - a new page recording how data actually moves between systems and parties, including free-text endpoints for channels nobody formally manages (a personal WhatsApp, a personal email account), with an Unapproved-channel badge and cross-border transfer tracking.
  - **RoPA Export** - a Record of Processing Activities export, in standard DPDP/GDPR Art. 30 shape, filterable by framework or by data principal - the filtered view is meant as the working input for drafting a consent policy for that principal.
- **Software Inventory & Crypto Inventory moved under IT Operations** - the former Supply Chain module (SBOM/CBOM) is now reachable from IT Operations, relabeled Software Inventory and Crypto Inventory; the standalone Supply Chain menu is retired.

### v0.4.2

- **Multi-level Manager hierarchy** - Managers can now be organized into up to 5 levels (Manager-1 through Manager-5) on a new Platform > Managers screen, each reporting to the manager one level above them. Ticket and change-request approvals flow up this same chain, rather than stopping at a single Manager.
- **Ticket & change request attachments** - Employees can attach files to a helpdesk ticket or change request at submission, and IT/GRC staff can view them from the console; every upload is checksum-verified (SHA-256).
- **Ticket time shown, not just date** - created/submitted timestamps for tickets and change requests, across the console and self-service portal, now show the actual time (in the viewer's local timezone) instead of a date-only value.
- **Date-based ticket filters** - the Helpdesk console can now filter tickets by a Created date range and a Closed date range, in addition to status, priority, and category.
- **Richer ticket exports** - the Helpdesk Excel/CSV export now includes Subcategory, Closed Date, and Resolution Notes columns. A ticket's closed date is recorded automatically when it moves to CLOSED, and cleared if it's reopened.

### v0.4.1

- **Manager-issued password reset keys** - Managers can now reset a team member's password from the My Team page by generating a one-time 5-word recovery passphrase, the same mechanism Admins use, instead of setting a plaintext password directly.
- **Consistent "Password Reset Key" labeling** - the Admin and Manager screens now use the same "Password Reset Key" wording for this action.

### v0.4

- **Manager approval workflow** - a new optional deployment-wide setting (Platform & Access > Business Settings) routes an Employee's helpdesk tickets and change requests to their assigned Manager for approval or rejection before IT/GRC staff can see them. Employees with no assigned Manager are blocked from submitting until one is assigned.
- **Manager Portal: My Team** - Managers can provision, edit, suspend/reactivate, and reset the password for the Employees who report to them, directly from the self-service portal, without needing Admin access.
- **Manager Portal: Approvals** - a dedicated queue where Managers review and approve or reject pending tickets and change requests from their team.
- **Employee / Manager role labels** - the `USER` and `SUPERVISOR` roles now display as "Employee" and "Manager" throughout the console and self-service portal for clarity (underlying role keys are unchanged).
- **Supply Chain module (SBOM/CBOM)** - new Supply Chain console page for tracking a Software Bill of Materials and Cryptography Bill of Materials, each entry optionally linked to an IT asset, with add/edit/delete and bulk import.

### v0.3

- **Search + pagination across the console** - added server-side search and pagination to every list view
- **Self-service portal** - created a self service portal for employees to manage the ITSM activities.
- **One-click ticket-to-incident escalation** - Helpdesk tickets can now be escalated to a Security Incident directly from the ticket
- **White labeling** - Branding support

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

## Backups

Configurable scripts for daily database backups (to a NAS mount or any other destination directory) and restore/verification are provided in [`scripts/`](scripts/README.md) - see that README for setup, scheduling, and restore instructions.

---

## Project structure

```
tsi-compass/
├── db/
│   ├── 01_init.sql                # Full schema (tables, triggers, seed data)
│   ├── 02_data_register.sql      # Data Register module schema (applied after 01_init.sql)
│   ├── 03_selfservice.sql        # Self-service portal schema additions
│   ├── 04_notifications.sql      # In-app notifications schema
│   ├── 05_ticket_escalation.sql  # Unique index enforcing one incident per escalated ticket
│   ├── ...                       # 06-17: ticket categories, asset categories, manager hierarchy, attachments, etc.
│   └── 18_data_governance.sql    # Data Governance module: principals, purpose/retention/access, gap mappings, data flows
├── scripts/
│   ├── backup.sh                 # Daily DB backup to a shared/NAS directory (see scripts/README.md)
│   └── restore.sh                # Restore + verify a backup (see scripts/README.md)
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

