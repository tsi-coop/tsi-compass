# TSI Compass: System Design

This is the single design document for TSI Compass. It describes what the system is, how it is built, how requests and permissions flow, what each module owns, and the decisions behind the less obvious parts. Task-oriented instructions live in the [guides](../guides/).

## 1. Purpose and scope

TSI Compass is a self-hosted IT GRC and ITSM platform for small and mid-sized organisations. It ties compliance work (policies, risks, controls, audits, incidents, reports) to daily IT work (helpdesk tickets, change requests, assets), and adds a Data Governance module aimed at India's DPDP Act.

Design goals:
- **Beginner-friendly and self-contained.** One WAR, one PostgreSQL database, no message broker, no scheduler, no external services required.
- **Everything auditable.** Every write is logged to a hash-chained, tamper-evident audit trail.
- **Least privilege by default.** Role and module permissions are enforced on the server for every API call and every page, not only hidden in the UI.
- **Proportionate.** Where a feature needs judgement (risk tiers, DPIA scoring, discovery matching) it is rules-based, explainable and editable by a person, never opaque.

Non-goals: agents or connectors that reach into customer systems, ML classification, continuous monitoring feeds, outbound email workflows, multi-tenancy (one deployment serves one organisation).

### Users

| Audience | Role keys | Surface |
|---|---|---|
| IT, security and GRC staff | `ADMIN`, `GRC_OFFICER`, `IT_STAFF` | Console `/console/` |
| Employees and managers | `USER` (Employee), `SUPERVISOR` (Manager) | Self-service portal `/selfservice/` |
| Outside vendors (optional) | `VENDOR` | Vendor portal `/vendorportal/` |
| Integrations | API key and secret | `/api/v1/*` |

## 2. Architecture

```
Browser (static HTML + vanilla JS)
   |  POST /api/v1/<service>  { "_func": "...", ... }   Authorization: Bearer <JWT>  |  X-API-Key / X-API-Secret
   v
Jetty 11 / Java 17 servlet container  (single WAR, root context)
   |-- BrandingFilter        *.html          injects BRAND_NAME
   |-- ConsoleAuthFilter     /console/*  /selfservice/*  /vendorportal/*   (role gate for pages)
   |-- InterceptingFilter    /api/*          CORS, security headers, auth, per-module RBAC, _func validation
   v
Service classes  org.tsicoop.compass.service.v1.*   (one class per API service, selected by _processor.tsi)
   |  PoolDB (commons-dbcp2)  +  EventLog.log(...)
   v
PostgreSQL 15   (schema from db/NN_*.sql, mounted into docker-entrypoint-initdb.d)
```

- **Frontend.** Plain HTML pages with inline or per-module JavaScript, no build step. Each console page repeats the same chrome (sidebar, `api()` helper, `esc()` escaping) and loads `web/console/rbac.js`, which hides what the signed-in role cannot use. Hiding is a convenience; the server enforces access.
- **Backend.** Each service implements `Action` (`post(req,res)` plus `validate`). All calls are `POST` with a JSON body whose `_func` selects the operation. `web/WEB-INF/_processor.tsi` maps `/api/v1/<name>` to a class.
- **Database.** One PostgreSQL schema. UUID primary keys (`uuid-ossp`), CHECK constraints for every enumerated status, foreign keys with explicit `ON DELETE` behaviour. Numbered scripts in `db/` run in order on first start; existing databases apply later scripts by hand.
- **Files.** Evidence, policy documents, attachments and generated reports are stored under `TSI_EXPORT_PATH` (a Docker volume). Metadata and a SHA-256 checksum are in the database.
- **Packaging.** `pom.xml` builds `tsi-compass.war`; the `Dockerfile` drops it into `jetty:11-jdk17`; `docker-compose.yml` runs the app and `postgres:15-alpine`.

### Request lifecycle (`InterceptingFilter`)
1. Set CORS headers (same origin always allowed; others only if listed in `ALLOWED_ORIGINS`) and security headers.
2. Resolve `/api/v1/<service>` to a class through `_processor.tsi`. Unknown services are rejected.
3. Authenticate: JWT bearer token for console, self-service and vendor sessions, or API key plus secret for machine access.
4. Map the service to a permission **module** (`SERVICE_MODULE_MAP`, for example `dpia` and `discovery` map to `data`, `tprm` maps to `risks`) and look up the role's level (`NONE`, `READ`, `WRITE`, `ADMIN`) in `role_permissions`, cached for five minutes.
5. Validate `_func` against the level. Read-only prefixes (`get_`, `list_`, `search_`, `export_`, `download_`, `count_`, `check_`) need `READ`; anything else needs `WRITE`; `delete_` functions need `ADMIN`.
6. Call the service. Services write their own `EventLog.log(...)` entries.

Rate limiting of failed sign-ins is in `User.login` (`LoginRateLimiter`), keyed on client address plus email: 5 failures in 15 minutes.

## 3. Identity, roles and permissions

- **Passwords:** BCrypt (12 rounds). **Sessions:** JWT signed with `JWT_SECRET`, 10-day lifetime; a token blocklist handles logout.
- **Recovery:** each console user can hold a five-word recovery passphrase (hash only is stored). A **break-glass key** gives ADMIN recovery when nothing else works.
- **Roles:** `ADMIN`, `GRC_OFFICER`, `IT_STAFF` (console), `USER`, `SUPERVISOR` (self-service), `VENDOR` (vendor portal). A `USER` or `SUPERVISOR` has `supervisor_id` (a reporting chain up to five manager levels, `13_manager_hierarchy.sql`); a `VENDOR` has `vendor_id`.
- **Permission matrix:** `role_permissions(role, module, permission_level)`, editable by ADMIN on Platform and Access, Roles and Permissions. Modules: `platform`, `governance`, `risks`, `controls`, `evidence`, `operations`, `incidents`, `reports`, `data`, `selfservice`, `vendorportal`.
- **Defaults:** ADMIN is `ADMIN` on every module. GRC_OFFICER is `WRITE` on governance, risks, controls, evidence, incidents, reports and data, `READ` on operations, and `NONE` on platform. IT_STAFF is `WRITE` on operations and incidents, and `NONE` on platform, governance, risks (including third-party risk), controls, evidence, reports and data. USER and SUPERVISOR have `WRITE` on selfservice only.
- **Page gating:** `ConsoleAuthFilter` refuses pages the role cannot use, and sends a vendor who opens a console page back to the portal.
- **Machine access:** API key and secret pairs, hashed at rest, shown once at creation, scoped by permission level.
- **PII at rest:** user email and phone use pgcrypto (`pgp_sym_encrypt`, base64) with an HMAC column for lookups, keyed by `DB_ENCRYPTION_KEY`.

## 4. Audit trail

`system_audit_trail` is append-only and hash-chained. A trigger computes `log_hash = SHA256(id || timestamp || user_id || action || details || previous_hash)` on insert; a second trigger rejects `UPDATE` and `DELETE`. Each service logs a named action with a JSON context (for example `DPIA_STATUS_APPROVED`, `DISCOVERY_FINDINGS_ACCEPTED`, `VENDOR_PORTAL_SUBMITTED`). The Platform and Access audit page lists and filters entries. Passwords and secrets are never logged.

## 5. Modules

| Module (console menu) | Service(s) | Permission module | Main tables | Schema scripts |
|---|---|---|---|---|
| Platform and Access | `platform`, `user`, `apikey`, `operator`, `setup` | `platform` | `users`, `role_permissions`, `api_keys`, `system_audit_trail`, ticket and asset category tables, business settings | 01, 03, 06, 07, 10, 11, 13 |
| Governance and Policy | `governance` | `governance` | `committees`, `committee_meetings`, `committee_mom`, `committee_action_items`, `policies`, `policy_clauses`, `policy_reviews`, `policy_attestations` | 01 |
| Risk and Vulnerability | `risk` | `risks` | `risks`, `vapt_engagements`, `vulnerabilities` | 01 |
| Third-Party Risk (under Risk) | `tprm` | `risks` | `vendors` (extended), `tprm_questionnaires`, `tprm_questions`, `tprm_assessments`, `tprm_answers`, `tprm_findings`, `tprm_contracts`, `tprm_documents` | 21 |
| Compliance and Controls | `controls` | `controls` | `frameworks`, `framework_requirements`, `controls`, `control_requirement_mappings`, `control_attestations`, `exceptions`, `compliance_regulators`, `compliance_items`, `compliance_calendar` | 01 |
| Audit and Evidence | `audit` | `evidence` | `audits`, `audit_observations`, `audit_remediations`, `evidence_locker` | 01 |
| IT Operations | `ops`, `supplychain` | `operations` | `assets`, `vendors`, `helpdesk_tickets`, `change_requests`, `change_request_gates`, attachment and comment tables, SBOM and CBOM tables | 01, 05, 08, 09, 11, 12, 14-17 |
| Data Governance | `data`, `dataflows`, `dpia`, `discovery`, `ropa` | `data` | `data_principals`, `data_assets`, `data_asset_*`, `data_flows`, `dpias`, `discovery_*` | 02, 18, 19, 20 |
| Incidents and Awareness | `incidents` | `incidents` | `incidents`, `incident_escalation_rules`, `training_materials`, `awareness_campaigns`, `campaign_enrollments`, `best_practices_vault`, document tables | 01, 05 |
| Reports and Dashboard | `reports`, `admindash` | `reports` | `scheduled_reports` (reads other modules) | 01 |
| Self-service portal | `selfservice`, `supervisor`, `notification` | `selfservice` | tickets, changes, attestations, enrolments, notifications | 03, 04, 07 |
| Vendor portal | `vendorportal` | `vendorportal` | `tprm_*` plus portal columns on assessments, answers and documents | 22 |

Cross-module links: a helpdesk ticket escalates to one incident; a TPRM finding escalates to a risk; a vendor links to data assets and recipients; control attestation and framework mappings feed the Data Register gap check; policies and training feed the self-service portal.

### 5.1 Core GRC modules (summary of behaviour)
- **Governance.** Committee calendar, minutes with action items, policy register with a Draft, Under review, Approved, Published, Archived lifecycle, framework tags, a review date, an uploaded document, and employee attestations.
- **Risk.** Risk register with `inherent_risk_score = impact x likelihood` (generated column, 1 to 5 each), treatment strategy, residual scoring, heatmap; VAPT engagements and vulnerabilities with SLAs and bulk import.
- **Controls.** Controls mapped to framework requirements (ISO 27001, DPDP and SOC 2 are seeded, more can be imported), periodic attestation, readiness percentages per framework, and an exception register that requires reason, compensating controls and an expiry date.
- **Audit.** Audit schedule, observations linked to risks and controls, remediation, and an evidence locker where each file is stored with a SHA-256 checksum and locked on upload.
- **IT Operations.** Assets with categories and criticality, change requests with document-gated stages and comments, a helpdesk with category and subcategory, one-click escalation to an incident, the lightweight vendor register, and the SBOM and CBOM inventories.
- **Incidents and awareness.** Incident register with severity, escalation rules and RCA report generation; knowledge base; training campaigns and enrolments.
- **Reports.** Live CSV exports, an executive summary, a monthly report generator and a scheduled-report definition table (see section 8 on scheduling).

### 5.2 Data Governance
Scope is India and the DPDP Act. RBAC: `ADMIN` full, `GRC_OFFICER` write, `IT_STAFF` none; there is deliberately no separate Data Flows access for IT staff.

- **Data Principals** (`data_principals`): editable categories of people the data is about (Customer and Employee are seeded).
- **Data Register** (`data_assets`): a self-declared inventory. Each entry has system type, category (`PII`, `FINANCIAL`, `HEALTH`, `EMPLOYEE`, ...), sensitivity (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`), owner, location, volume, purpose, processing basis, retention period and deletion trigger, controller role (`CONTROLLER`, `PROCESSOR`, `JOINT`), `is_large_scale`, `involves_profiling`, and a review status (`DISCOVERED`, `CLASSIFIED`, `REVIEWED`). Linked tables hold principals, recipients, access, and mappings to framework requirements.
- **Gap check** (computed, not stored): an entry has a gap when it is not reviewed, has a sensitive or personal category with no processing basis, has neither retention nor deletion trigger, or is mapped to no framework requirement.
- **Data Flows** (`data_flows`): a directed movement between two registered assets or free-text endpoints, with channel (including unapproved ones such as personal WhatsApp), data elements, frequency, and cross-border flag with destination country.
- **RoPA Export:** a join of the above in DPDP and GDPR Article 30 shape, filterable by framework or principal, exported as CSV.
- **DPIA** (`dpias`, one per register entry): risk is derived server-side from the entry and its flows.

  | Factor | Source | Points |
  |---|---|---|
  | Sensitive data | category `HEALTH` or sensitivity `RESTRICTED` | 4 |
  | Profiling | `involves_profiling` | 3 |
  | Financial data | category `FINANCIAL` | 2 |
  | Cross-border transfer | any flow with `is_cross_border` | 2 |
  | Third-party sharing | any recipient | 1 |
  | Large-scale | `is_large_scale` | 1 |

  A score of 4 or more is High, 3 is Medium, 2 or less is Low. Generation upserts one row per entry: Draft rows refresh their derived fields, Under review and later rows are never overwritten but are flagged `stale` if the derived risk has changed. Workflow is Draft, Under review, Approved, Submitted, with Under review back to Draft allowed. Approved and Submitted rows are locked; only an ADMIN can delete one. Approval records `approved_by` and `approved_at`; submission records `submitted_at` and an optional board reference. Any GRC Officer may approve, including the person who edited the DPIA (see the gaps document).
- **Discovery** (`discovery_sources`, `discovery_runs`, `discovery_findings`, `discovery_rules`): offline schema import. The user runs one `information_schema` query on their own database and uploads table, column and data type. Compass never connects to the database and never sees values. Column names are normalised (camelCase to snake_case) and matched against 13 seeded India-focused rules (Aadhaar, PAN, voter ID, passport and licence, health, biometric, bank account and IFSC, GSTIN, UPI, mobile, email, date of birth, name, address). One finding per column; the lowest-priority-number rule wins; confidence is at most Medium (name match only); boolean columns and a bare `name` column are skipped; a rule can exclude patterns (an `ip_address` is not a postal address). Findings are grouped per table and **accepted** into one `DISCOVERED` register entry per table, or **rejected**. Rejected and accepted findings are not changed by a re-import. Only ADMIN can enable or disable a rule; regexes are not editable in the UI. Imports are limited to 50,000 columns.

### 5.3 Third-Party Risk
Sits under Risk and Vulnerability and reuses the `risks` permission module (GRC and Admin only).

- **Inherent tier** is computed on every profile change from business criticality (0 to 4), personal data (3), sensitive linked data (2), data outside India (2), network access (2), and fourth-party dependence (1). Score of 8 or more is Critical, 5 to 7 High, 3 to 4 Medium, 2 or less Low. The tier sets the default review cycle (6, 12, 24, 36 months).
- **Linked data** counts a data asset as linked when `linked_vendor_id` points at the vendor, a recipient links to it, or a recipient name matches the vendor name (case-insensitive).
- **Assessments** use a versioned questionnaire (42 seeded questions, to be reviewed by counsel). Only one assessment per vendor is open at a time. Completion needs every scored question answered; N/A is excluded from the score; residual risk steps down at 80% or more and up below 50%. A No on a critical question raises a finding. A questionnaire used in an assessment is locked; ADMIN clones it to change it.
- **Findings** have owner, severity, due date and a lifecycle; `ACCEPTED` needs a reason and an approver. A finding can escalate one-way into the Risk Register.
- **Contracts and documents** track dates, notice periods and four clause flags (security, breach notification, audit right, data return and deletion). Documents carry a SHA-256 checksum and an optional validity date.
- **Computed gaps** (no extra tables): personal-data vendor without a DPA (DPDP Sec 8(2)), missing clauses, overdue or missing review, expiring evidence, overdue high findings, and terminated vendors still listed as recipients.
- **Archiving, not deleting:** deleting a vendor sets `archived_at` and keeps its history; archived vendors disappear from lists and metrics and can be restored. A vendor name stays reserved.
- **Notifications:** with no scheduler, due-soon notices are raised lazily when the Third-Party Risk or Risk hub loads, at most once a week per item.

### 5.4 Vendor portal
Off unless `TSI_VENDOR_PORTAL_ENABLED=true`; when off, vendor logins are refused and the pages return 404. A `VENDOR` user belongs to exactly one vendor and is created by GRC (temporary password shown once). Request state is held on the assessment in `portal_status`: `NOT_SENT`, `SENT`, `IN_PROGRESS`, `SUBMITTED`, `RETURNED`, `COMPLETED`.

- While the vendor holds a request the assessor cannot edit it; withdrawing hides it again.
- Submission needs every scored question answered and a declaration. After a return, only returned questions reopen.
- Assessor corrections keep the vendor's original answer and comment; the vendor still sees their own words, never scores, findings or ratings.
- Evidence: PDF, DOCX, XLSX, PNG, JPG; leading bytes must match the extension; 10 MB per file, 25 files and 50 MB per assessment; stored under a generated name; vendors can remove only their own files before submission.
- Default deny: a `VENDOR` token may call only `/api/v1/vendorportal`, every call resolves the vendor from the signed-in user, IDs belonging to another vendor return 404, and a disabled or archived-vendor account stops working on the next request. Compass sends no email.

### 5.5 Self-service portal and Manager approval
Employees raise helpdesk tickets and change requests (with attachments), acknowledge published policies and complete training. Managers also provision and manage their own team and approve their team's requests. When the deployment-wide **Manager approval** setting is on, a request is hidden from IT and GRC until the assigned Manager (and, up the chain, higher-level Managers) approves it. Managers can view every request from their whole downline.

## 6. Security design

| Concern | Approach |
|---|---|
| Authentication | BCrypt passwords, JWT sessions, hashed API keys, recovery passphrase, break-glass key, per-account and per-address login throttling |
| Authorisation | Server-side module and function checks on every API call; role gating on every page; default-deny for `VENDOR`; ADMIN-only deletes |
| Data protection | pgcrypto on user PII columns with HMAC lookup; secrets in environment variables; no data values ever stored by Discovery |
| Integrity | Hash-chained, immutable audit trail; SHA-256 checksums and lock-on-upload for evidence; vendor files validated by magic bytes |
| Transport and exposure | TLS and reverse proxy are the operator's responsibility; only `/vendorportal/`, `/index.html` and `/api/` need to be public if the vendor portal is on; CORS allow-list; response security headers |
| Bootstrapping | First admin is created at `/setup/` only with `TSI_BOOTSTRAP_TOKEN`; without it setup returns 503 |
| Input handling | Prepared statements throughout; output escaped in the console (`esc()`); upload type and size limits |

Operational limits: there is no built-in TLS, no multi-factor authentication, and no SSO. Backups are scripts (`scripts/backup.sh`, `scripts/restore.sh`) that the operator schedules.

## 7. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWD` | compose defaults | Database connection |
| `JWT_SECRET` | dev placeholder | Signs sessions (change in production) |
| `DB_ENCRYPTION_KEY` | dev placeholder | pgcrypto key for PII columns (change in production) |
| `TSI_LOOKUP_SALT` | dev placeholder | Deterministic lookups |
| `TSI_BOOTSTRAP_TOKEN` | none | Enables first-admin setup |
| `ALLOWED_ORIGINS` | `http://localhost:8085` | CORS allow-list |
| `TSI_EXPORT_PATH` | `/var/lib/tsi-compass/exports/` | Evidence, attachments, reports |
| `BRAND_NAME` | `TSI COMPASS` | Product name shown in pages |
| `TSI_VENDOR_PORTAL_ENABLED` | `false` | Switches the vendor portal on |
| `TSI_COMPASS_ENV` | `local` | Environment label |

## 8. Known limits and decisions

- **No scheduler.** Nothing runs on a timer. Review reminders and expiry notices are raised when a hub page loads; scheduled reports exist as definitions only. A deployment that needs timed jobs must use an external scheduler.
- **No outbound email.** The mail-sending classes are not wired to any workflow, so vendors and users are told credentials out of band.
- **One purpose per register entry.** The RoPA model puts purpose, basis and retention on the data asset, one value each. An activity that has several purposes with different bases is entered as several entries (see `examples/datagov`).
- **Self-declared data inventory.** The register is what people declare; Discovery proposes entries from column names only.
- **Single organisation per deployment.**
- **Separation of duties** is a role matter, not enforced per record: a GRC Officer who edits a DPIA or assessment can also approve it.

## 9. Extending the system

To add a module: write the SQL script as the next number in `db/`; add a service class implementing `Action`; register it in `web/WEB-INF/_processor.tsi`; map it to a permission module in `InterceptingFilter.SERVICE_MODULE_MAP` (and add `role_permissions` rows if it is a new module); add the console page with the standard chrome and an entry in `web/console/rbac.js`; log writes through `EventLog.log`; add a guide under `docs/guides/`.

## 10. Repository layout

```
db/            numbered schema and seed scripts (01 to 22)
src/org/tsicoop/compass/framework/    filters, JWT, DB pool, encryption, helpers
src/org/tsicoop/compass/service/v1/   one class per API service
web/           login, setup, tour, console/, selfservice/, vendorportal/, WEB-INF/
scripts/       backup and restore
examples/      datagov (Varam demo), policy (sample policy PDF)
docs/          architecture/ (this document), guides/
```
