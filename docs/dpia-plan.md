# DPIA (Data Protection Impact Assessment) under Data Governance

Status: **planned, not started.** To be revisited.

## Context
Data Governance has Principals, Data Register, Data Flows and a RoPA Export. Nothing yet produces a DPIA, which DPDP Sec 10(2)(c)(i) / Rule 13(1) requires from Significant Data Fiduciaries. Goal: a new **DPIA** page, auto-derived from the RoPA (a "processing activity" = a `data_assets` row, exactly as `getRopaExport` in `DataRegister.java:739` treats it), with summary tiles, one row per activity showing risk / key factors / residual / status, and an expandable detail (Necessity & Lawful Basis, Proportionality, Risk Factors, Mitigations, Residual Risk).

Decisions made:
- **Status workflow:** Draft → Under Review → Approved → Submitted.
- **SDF:** ignore any SDF setting. No toggle. A DPIA is generated for *every* activity and the banner is static text (Rule 13(1)). No `business_settings` change.
- Stays India/DPDP-only; RBAC stays ADMIN full / GRC_OFFICER write / IT_STAFF none.

## Design

### 1. Schema: `db/19_dpia.sql`
- `dpias`: `id`, `data_asset_id UNIQUE → data_assets ON DELETE CASCADE`, `status` CHECK (DRAFT, UNDER_REVIEW, APPROVED, SUBMITTED), `inherent_risk` / `residual_risk` CHECK (LOW, MEDIUM, HIGH), `risk_score INT`, `risk_factors JSONB` (derived snapshot: `[{code,label,reference}]`), `necessity_note`, `proportionality_note`, `mitigations TEXT`, `residual_note`, `approved_by → users`, `approved_at`, `submitted_at`, `board_reference`, `generated_at`, `updated_at`.
- `ALTER TABLE data_assets ADD is_large_scale BOOLEAN DEFAULT FALSE, involves_profiling BOOLEAN DEFAULT FALSE`. Neither signal exists today (`volume_estimate` is free text), and the sample rows need both. Editable in the Register add/edit form.
- No new `role_permissions` row (reuses `data`).

### 2. Risk derivation (server-side, single method in the new service)
| Factor | Source | Reference shown | Points |
|---|---|---|---|
| Sensitive data | `category = HEALTH` or `sensitivity = RESTRICTED` | Rule 6 safeguards | 4 |
| Profiling / behavioural tracking | `involves_profiling` | Sec 10 / Rule 13 | 3 |
| Financial data | `category = FINANCIAL` | Rule 6 safeguards | 2 |
| Cross-border transfer | any `data_flows.is_cross_border` (country listed) | Sec 16 | 2 |
| Third-party sharing | rows in `data_asset_recipients` | Sec 8(2) / Rule 6(1)(f) | 1 |
| Large-scale processing | `is_large_scale` | Rule 13 | 1 |

Score ≥4 → High, 3 → Medium, ≤2 → Low. Checked against the 7 sample rows in the original design (payroll / B2B customer onboarding / product analytics = High; HR onboarding / lead-gen / vendor payment = Medium; support ticketing = Low), so tiles come out 3/3/1.

Each factor maps to a canned mitigation line (e.g. "Tokenise / mask financial identifiers…", "Ensure a signed DPA / Sec 8(2) contract…") concatenated into `mitigations` as the editable default. Necessity text is generated from purpose + `processing_basis`. Proportionality text is generated from field/category counts (flow `data_elements` and linked principals). Residual defaults to inherent.

### 3. Backend: new `Dpia.java` (`service/v1`, modelled on `DataFlows.java`)
Functions: `get_dpia_metrics`, `list_dpias`, `get_dpia_detail`, `generate_dpias`, `clear_generated_dpias`, `update_dpia` (notes, mitigations, residual), `set_dpia_status`, `delete_dpia`.
- **generate:** upsert one row per data asset. Existing DRAFTs get derived fields (factors, inherent, notes) refreshed; UNDER_REVIEW and later rows are never overwritten. If their derived risk has changed, they get a `stale` flag in list/detail so GRC can re-review.
- **clear:** deletes DRAFT rows only.
- **status transitions:** validated server-side (Draft→Under Review→Approved→Submitted; Under Review→Draft allowed for send-back). Approve records `approved_by/at`; Submit requires Approved and records `submitted_at` and optional `board_reference`.
- All writes logged via `EventLog.log` like the sibling services.
- Register in `InterceptingFilter.SERVICE_MODULE_MAP`: `"dpia" → "data"` (line ~72). Confirm how `/api/v1/<name>` resolves to the class (same as `dataflows`) and whether write-vs-read is decided by `_func` name, so read-only funcs stay readable.

### 4. Frontend: `web/console/data-governance-dpia.html`
Copy the chrome from `data-governance-ropa.html` (same CSS, sidebar, `api()` helper, `esc()`, `rbac.js`).
- Header with **Auto-generate from RoPA** and **Clear auto-generated** buttons (confirm dialog; clear only affects drafts). Tiles: Total / High / Medium / Low. Static SDF banner.
- Table: Activity, Dept (owner's department if available, else "-"), Risk badge, Key Risk Factors (first two + "…"), Residual, Status, and a **View/Hide** toggle that expands the detail row with the five sections. Notes / mitigations / residual are editable inline while Draft or Under Review. Status buttons per state; locked read-only once Approved.
- Filters by risk and status. CSV export, matching the RoPA page.
- Wiring: add a "DPIA" subnav link to all 5 existing `data-governance*.html` pages, a fifth hub card + count in `data-governance.html`, and `'data-governance-dpia.html': 'data'` in `rbac.js:32-36`.
- Register form (`data-governance-register.html`): add the two checkboxes (large scale, profiling) and persist them in `DataRegister.add/update_data_asset`.

### 5. Docs
`README.md` tree entry for `19_dpia.sql`; document the scoring table (this file can be extended). Optionally add a DPIA walkthrough to `examples/datagov/`.

## Open point to confirm at implementation
"Dept" comes from the asset owner's department. Not yet checked whether `users` has a department column. If not, drop the column or derive it from the owner's role.

## Verification
1. Apply `19_dpia.sql` on a local DB (docker-compose), build with Maven, load the console.
2. Use the example in `examples/datagov/data-governance-varam-example.md` and confirm scoring: each sample archetype lands at the expected risk level.
3. Click Auto-generate. One row per asset, tiles sum to total. Re-run: no duplicates, and Approved rows untouched.
4. Edit a draft, move it through Under Review → Approved → Submitted. Illegal jumps are rejected (call the API directly). Clear removes only drafts.
5. Change an asset's flows, then confirm the approved DPIA shows `stale` and the draft refreshes.
6. RBAC: GRC_OFFICER can write, IT_STAFF gets 403 on `/api/v1/dpia`, ADMIN full.
7. Check audit log entries for generate / approve / submit.
