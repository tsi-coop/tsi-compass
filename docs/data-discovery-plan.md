# Automated Data Discovery under Data Governance

Status: **v1 implemented and verified: offline schema import only** (`db/20_data_discovery.sql`, `DataDiscovery.java`, `data-governance-discovery.html`). Live connections, value sampling and the other database types below are not built.

## As built (v1)
Decisions taken at implementation, which override the design below where they differ:
- **Offline schema import instead of live connections.** The user runs one `information_schema` query on their own database and uploads the CSV (table, column, data type). Compass makes no outbound connection, so there are no stored credentials, no SSRF surface, no read-only-account requirement and no background worker; the scan runs inside the import request (50,000 columns takes about 5 s). `discovery_sources` therefore holds name, type, location only; the `mode` column on `discovery_runs` allows `LIVE_METADATA` / `SAMPLED` so those can be added later.
- **Entry point:** a **Discover** button on the Data Register opens the Discovery page; accepted findings come back as `DISCOVERED` Register entries tagged "From discovery".
- **One finding per column** (unique on source, schema, table, column) rather than per detected type; when several rules match, the lowest `priority` wins. Rejected and accepted findings are never changed by a re-import; a rejected one can be reopened.
- **Confidence is at most MEDIUM** (name match only); generic name / address rules are LOW. A bare column called `name` is deliberately not flagged. Boolean columns are skipped (`has_aadhaar` is a flag, not personal data).
- **Rules** have an extra `exclude_regex` (e.g. `ip_address` is not a postal address). Only ADMIN can enable or disable a rule; regexes are not editable in the UI.
- Functions: `import_schema`, `list_findings`, `accept_findings`, `reject_findings`, `reopen_findings`, `list_sources`, `update_source`, `delete_source`, `list_runs`, `list_rules`, `update_rule`, `get_discovery_metrics`. Audit actions: `DISCOVERY_IMPORT`, `DISCOVERY_FINDINGS_ACCEPTED` / `_REJECTED` / `_REOPENED`, `DISCOVERY_SOURCE_UPDATED` / `_DELETED`, `DISCOVERY_RULE_UPDATED`.
- Walkthrough: `examples/datagov/sample-schema-export.csv` and `examples/datagov/README.md`.
- Not applicable to v1 (no connection is made): the security model in section 5 and verification steps 5 and 7, and the `TSI_DISCOVERY_*` env vars. They apply if live scans are added.

## Context
The Data Register is entirely self-declared (see `data-register-phase2-plan.md`, "Automated discovery/scanning - still self-declared"). `DISCOVERED` is only a status label for an entry nobody has classified yet; nothing is actually discovered by the system.

Goal: let a GRC Officer point Compass at a database, have it find columns that look like personal data, and turn the findings into `DISCOVERED` Data Register entries that a human then classifies and reviews through the existing workflow. Discovery proposes; people decide. Nothing is written to `data_assets` without explicit acceptance.

Scope and constraints (carried over from existing decisions):
- India / DPDP focus. Detection patterns are India-specific (Aadhaar, PAN, GSTIN, IFSC, Indian mobile, voter ID, passport, UPI ID) plus generic ones (email, name, DOB, address).
- RBAC unchanged: ADMIN full, GRC_OFFICER write, IT_STAFF none, under the existing `data` module. No new `role_permissions` row.
- Compass stays a self-hosted, beginner-friendly tool. No agents, no external services, no new infrastructure. The scan runs inside the Compass server.

## Difficulty assessment
**Moderate. Roughly 6-9 working days for a solid v1 (Postgres only, metadata scan), plus 1-2 days per additional database type.**

| Part | Effort | Why |
|---|---|---|
| Schema + data-source CRUD | Small | Same shape as the existing Data Register / Data Flows services |
| Credential storage | Small | `DbEncryption` (pgcrypto) already exists for PII columns; reuse the scheme |
| Metadata scanner (Postgres) | Small-Medium | `information_schema.columns` plus a rules table. The postgres JDBC driver is already a dependency |
| Detection rules + scoring | Medium | The bulk of the thinking. Name-pattern rules are easy; value-pattern rules (Aadhaar Verhoeff checksum, PAN format) need care to avoid false positives |
| Review UI (accept / reject / merge) | Medium | New console page, modelled on the DPIA page plan |
| Security hardening | Medium | Server connecting to user-supplied hosts. SSRF, read-only credentials, no value storage. This is the part not to rush |
| Other databases (MySQL, SQL Server) | Small each, but adds dependencies | Extra JDBC drivers grow the WAR; do as follow-ups |
| File shares, cloud storage, SaaS | Large | Out of scope for v1 (see below) |

The hard part is not the code, it is trust: false positives erode confidence, and a scanner that connects to production databases needs a careful security model.

## Design

### Two-stage approach (recommended)
1. **Stage 1 (v1): metadata scan.** Read schema only (table and column names, types, row-count estimate). Match column names against rules. No row data is read. This is fast, safe, needs minimal privileges, and catches most real cases (`aadhaar_no`, `pan`, `mobile`, `email`).
2. **Stage 2 (optional, off by default): value sampling.** For columns that matched weakly or not at all, sample N rows (default 50, hard cap 200) and test against value patterns. Samples are matched in memory and **never stored or logged**; only the match ratio is kept. Requires an explicit per-source opt-in checkbox, because reading production values is itself a data-processing activity.

Ship Stage 1 first. Stage 2 is a separate, later increment.

### 1. Schema: `db/20_data_discovery.sql`
(Number follows `19_dpia.sql`; adjust to whatever is next at implementation time.)

- `discovery_sources`: `id`, `name`, `source_type` CHECK (`POSTGRES`, later `MYSQL`, `MSSQL`), `host`, `port`, `database_name`, `username`, `password_enc TEXT` (pgcrypto, same base64 scheme as `DbEncryption`), `ssl_mode`, `allow_sampling BOOLEAN DEFAULT FALSE`, `linked_asset_id -> assets ON DELETE SET NULL`, `owner_id -> users`, `created_at`, `last_scanned_at`.
- `discovery_runs`: `id`, `source_id -> discovery_sources ON DELETE CASCADE`, `status` CHECK (`RUNNING`, `COMPLETED`, `FAILED`), `mode` CHECK (`METADATA`, `SAMPLED`), `tables_scanned`, `columns_scanned`, `findings_count`, `error_message`, `started_by -> users`, `started_at`, `finished_at`.
- `discovery_findings`: `id`, `run_id -> discovery_runs ON DELETE CASCADE`, `source_id`, `schema_name`, `table_name`, `column_name`, `data_type`, `detected_type` (e.g. `AADHAAR`, `PAN`, `EMAIL`), `suggested_category` (existing `data_assets.category` values), `suggested_sensitivity`, `confidence` CHECK (`HIGH`, `MEDIUM`, `LOW`), `match_basis` (`COLUMN_NAME` / `VALUE_PATTERN`), `status` CHECK (`NEW`, `ACCEPTED`, `REJECTED`), `data_asset_id -> data_assets ON DELETE SET NULL`, `reviewed_by`, `reviewed_at`. `UNIQUE (source_id, schema_name, table_name, column_name, detected_type)` so re-scans update rather than duplicate, and a `REJECTED` finding stays rejected on re-scan.
- `discovery_rules`: seeded, `code`, `label`, `name_regex`, `value_regex NULL`, `checksum` (`NONE` / `VERHOEFF`), `category`, `sensitivity`, `enabled`. Rules live in the table (not hard-coded) so they can be tuned without a release.
- No raw values are stored anywhere.

### 2. Detection rules (seed data)
| Detected type | Column-name hint (regex, case-insensitive) | Value pattern (Stage 2) | Category / sensitivity |
|---|---|---|---|
| Aadhaar | `aadhaar\|aadhar\|uid(ai)?` | 12 digits, first digit 2-9, Verhoeff checksum | PII / RESTRICTED |
| PAN | `\bpan\b\|pan_?(no\|number)` | `[A-Z]{5}[0-9]{4}[A-Z]` | PII / RESTRICTED |
| GSTIN | `gstin\|gst_?no` | 15-char GSTIN format | FINANCIAL / CONFIDENTIAL |
| Mobile | `mobile\|phone\|msisdn\|contact_?no` | `(\+91\|0)?[6-9][0-9]{9}` | PII / CONFIDENTIAL |
| Email | `e_?mail` | RFC-lite email | PII / CONFIDENTIAL |
| Bank account / IFSC | `account_?(no\|number)\|ifsc` | IFSC `[A-Z]{4}0[A-Z0-9]{6}` | FINANCIAL / RESTRICTED |
| UPI ID | `upi` | `[\w.\-]+@[a-z]{2,}` | FINANCIAL / CONFIDENTIAL |
| Voter ID / Passport / DL | `voter\|epic\|passport\|driving_?licen` | format-specific | PII / RESTRICTED |
| Date of birth | `dob\|birth` | date type | PII / CONFIDENTIAL |
| Name / address | `first_?name\|last_?name\|full_?name\|address\|pincode` | none | PII / INTERNAL (LOW confidence) |
| Health | `diagnos\|prescription\|medical\|patient\|blood_?group` | none | HEALTH / RESTRICTED |
| Biometric | `fingerprint\|biometric\|iris\|face_?(id\|template)` | none | PII / RESTRICTED |

Confidence: a name match on a column with a plausible type (text/varchar for Aadhaar, not boolean) is MEDIUM; name plus a value match above 80% of the sample is HIGH; generic names (`name`, `address`) are always LOW. Findings are sorted so the actionable ones come first.

### 3. How findings become Data Register entries
- Findings are grouped **per table**. One table with several PII columns becomes **one** `data_assets` row (`system_type = DATABASE`, `name = <source>.<schema>.<table>`, `location` = host/db, `discovery_status = DISCOVERED`, `description` listing the matched columns). Column-level rows would flood the Register.
- Category and sensitivity of the asset = the highest-sensitivity finding in the table.
- **Accept** creates the asset (or, if an asset with the same name already exists, links to it and updates its description) and marks the findings `ACCEPTED`. **Reject** marks them `REJECTED` and they are suppressed on re-scan. Both are bulk actions per table.
- The existing Classify, Principals, Recipients, Access, Gap Check, RoPA and DPIA features then work on these entries unchanged.

### 4. Backend: new `DataDiscovery.java` (`service/v1`, modelled on `DataFlows.java`)
Functions: `list_sources`, `add_source`, `update_source`, `delete_source`, `test_connection`, `start_scan`, `get_run`, `list_runs`, `list_findings`, `accept_findings`, `reject_findings`, `list_rules`, `update_rule`, `get_discovery_metrics`.
- **Running a scan:** `start_scan` inserts a `RUNNING` run and returns immediately; the scan executes on a single background worker thread (one scan at a time, server-wide) and updates the run row when done. The UI polls `get_run`. Avoids request timeouts on large schemas.
- **Metadata query:** `information_schema.columns` for non-system schemas, plus `pg_class.reltuples` for row estimates. Hard limits: max tables and columns per run (configurable, default 2,000 tables), statement timeout 30 s, connect timeout 10 s.
- Registered in `InterceptingFilter.SERVICE_MODULE_MAP` as `"discovery" -> "data"`, plus the line in `_processor.tsi` (`/api/v1/discovery=...DataDiscovery`). Delete functions stay ADMIN-only via the existing `delete_` prefix rule.
- All writes and every scan start / finish logged through `EventLog.log`.
- `password_enc` is never returned by any API response; `list_sources` returns a `has_password` flag.

### 5. Security model (must be in v1, not deferred)
- **Read-only database account.** The UI and docs tell the user to create a dedicated `SELECT`-on-`information_schema`-only account. Compass additionally opens the connection with `readOnly=true` and `SET default_transaction_read_only = on`.
- **SSRF / network reach.** The server connects to user-supplied hosts. By default, block loopback, link-local (`169.254.0.0/16`, including the cloud metadata address) and the Compass's own Postgres host. Private ranges (10/8, 172.16/12, 192.168/16) are allowed because real databases live there. An env var `TSI_DISCOVERY_ALLOWED_HOSTS` (comma-separated) can restrict scans to an explicit allowlist; if set, anything not on it is refused. Hostnames are resolved once and the resolved address is the one connected to, to avoid DNS rebinding.
- **Credentials at rest** encrypted with `DB_ENCRYPTION_KEY`; never logged; never echoed to the client.
- **No data leaves the scan.** Findings hold names and types, never values. Stage 2 samples are held in memory only.
- **TLS:** `ssl_mode` defaults to `require` for any non-local host.
- **Per-source opt-in** for sampling, and the audit trail records whether a run was `METADATA` or `SAMPLED`.
- **Kill switch:** `TSI_DISCOVERY_ENABLED` (default `true`). When `false`, scan and connection-test functions return 403.

### 6. Frontend: `web/console/data-governance-discovery.html`
Chrome copied from `data-governance-ropa.html` (CSS, sidebar, `api()`, `esc()`, `rbac.js`).
- **Sources tab:** table of sources with Test, Scan and Edit. Add/Edit modal (with a note recommending a read-only account and an off-by-default sampling checkbox).
- **Findings tab:** stat tiles (sources, last scan, new findings, high-confidence). Table grouped by table, showing detected types, confidence badge, and suggested category/sensitivity. Bulk **Accept as Data Register entry** / **Reject**, plus filters by source, confidence and status.
- **Run progress:** simple status banner while a scan runs (polls `get_run` every 2 s).
- **Rules** (ADMIN only): list with enable/disable toggle. Editing regexes is deliberately out of v1.
- Wiring: "Discovery" subnav link on all existing `data-governance*.html` pages, a hub card with count in `data-governance.html`, and `'data-governance-discovery.html': 'data'` in `rbac.js`.
- Data Register page: show a small "From discovery" tag on entries created this way (via `data_asset_id` on findings).

### 7. Docs
README: add the module to the Data Governance row and Features changelog; document `TSI_DISCOVERY_ENABLED` and `TSI_DISCOVERY_ALLOWED_HOSTS` in the environment variables table. Add a short walkthrough to `examples/datagov/`. Update the "still self-declared" lines in `data-register-phase2-plan.md` to point here.

## Explicitly out of scope for v1
- File shares, S3 / Blob storage, email and SaaS scanning (document parsing, OCR, API connectors: each is its own project).
- Scheduled / recurring scans (v1 is manual; a scheduler can follow once `Job` infrastructure is confirmed suitable).
- MySQL / SQL Server / Oracle / MongoDB (next increments; each adds a JDBC driver dependency and a metadata query).
- Stage 2 value sampling (separate increment after Stage 1 is in use).
- ML-based classification. Rules only: they are explainable to an auditor.
- Non-Indian frameworks (GDPR / CCPA / HIPAA identifiers).

## Open points to confirm
1. **Which database types matter first?** Plan assumes Postgres only for v1. If your users mostly run MySQL, swap it in (driver dependency + one metadata query).
2. **Is a one-at-a-time background worker acceptable**, or should scans reuse the existing `Job` mechanism (`/admin/job` appears in `InterceptingFilter`)? Needs a look at how `Job` is implemented before committing.
3. **Is it acceptable for the Compass server to open outbound DB connections?** In locked-down deployments the container may have no route to the databases. The allowlist env var covers restricting it; a "paste a schema export" alternative (upload the output of an `information_schema` query as CSV, scan offline) avoids the connection entirely and is a cheap complementary mode (about 1 day, reuses the CSV import path in `import_data_assets`). Worth deciding whether it should ship in v1 alongside or instead of live connections.
4. **Stage 2 sampling:** ship at all? Many GRC teams will not want a compliance tool reading production rows.

## Verification
1. Apply `20_data_discovery.sql` on a local DB (docker compose), build with Maven, load the console.
2. Create a test Postgres database containing known PII columns (`aadhaar_no`, `pan`, `mobile`, `email`, `dob`, `diagnosis`) plus benign ones (`id`, `status`, `created_at`, `product_name`). Scan it: PII columns flagged with the expected type and confidence; benign columns not flagged.
3. Re-run the scan: no duplicate findings; a rejected finding stays rejected; a new column added since appears as a new finding.
4. Accept findings for a table: exactly one `data_assets` row appears with `DISCOVERED` status and correct system type, category and sensitivity; it can then be classified, given principals and shows up in the gap check and RoPA export.
5. Security checks: scan against `127.0.0.1`, `169.254.169.254` and the Compass's own DB host is refused; with `TSI_DISCOVERY_ALLOWED_HOSTS` set, other hosts are refused; connect with a wrong password returns a clean error with no credential in the message or logs.
6. Confirm `password_enc` is encrypted in the table and absent from every API response; confirm no cell values appear in tables, logs or responses.
7. Use a write-capable account and confirm the connection is nevertheless read-only (an attempted `CREATE TABLE` through the scan connection fails).
8. RBAC: GRC_OFFICER can add sources and scan; IT_STAFF gets 403 on `/api/v1/discovery`; only ADMIN can delete sources; `TSI_DISCOVERY_ENABLED=false` blocks scans.
9. Audit log entries exist for source create / update / delete, scan start / finish, and accept / reject.
