# Third-Party Risk Management (TPRM)

Status: **Phase 1 and Phase 2 implemented and verified** (`db/21_tprm.sql`, `Tprm.java`, `risks-tprm.html` / `risks-tprm.js`). The vendor portal (formerly Phase 3) is also built, see `tprm-vendor-portal-spec.md`. Scheduled reminders and monitoring feeds are not.

## As built
Where the implementation differs from or settles points in the design below:
- **One page, five tabs** (`risks-tprm.html`): Overview, Vendors, Assessments, Findings, Questionnaires. A vendor opens in a side drawer with Profile, Assessments, Findings, Contracts & documents, Linked data and Lifecycle tabs.
- **Linked data matching:** a data asset counts as linked to a vendor when `data_assets.linked_vendor_id` or a recipient's `linked_vendor_id` points at it, **or the recipient name matches the vendor name** (case-insensitive). The Data Register's recipient field is mostly free text in practice, so name matching is what makes the personal-data, sensitive-data and cross-border signals work without re-keying.
- **Tiering** follows the plan's table exactly (max score 14; 8+ Critical, 5-7 High, 3-4 Medium, 2 or less Low). A vendor with no criticality set simply scores 0 for that factor. The tier is recomputed on every profile change and snapshotted on the vendor row.
- **Assessments:** only one open assessment per vendor. Unanswered scored questions block completion; N/A is only allowed on Yes/No/N/A questions and is excluded from the score. If every scored question is N/A the score is 100%. A questionnaire that has been used in an assessment is locked; ADMIN clones it to a new version to change questions.
- **Findings** raised from an assessment get severity HIGH, a 30-day due date and the vendor's business owner (or the assessor) as owner. `ACCEPTED` is only reachable through `accept_finding`, which needs a reason and records who accepted it.
- **Archiving:** `Operations.delete_vendor` (ADMIN) and `archive_vendor` (ADMIN or GRC_OFFICER) both archive. The Data Register vendor picker also hides archived vendors. Adding a vendor whose name matches an archived one is refused with a "restore it instead" message, on both the Operations and TPRM pages.
- **Delete permissions:** `delete_contract`, `delete_document` and `delete_question` fall under the existing ADMIN-only `delete_` rule.
- **Notifications:** Compass has no scheduler, so "due soon" notices (review due, document expiring, finding overdue, contract renewal) are raised lazily when the Third-Party Risk or Risk hub loads, at most once per week per item and 20 per load. Event notices (assessment completed, finding escalated, offboarding started) are raised immediately.
- **Dashboard:** a "Vendor Reviews Overdue" card, shown only to roles with access. The Operations `vendor_reviews_due` metric is kept (it is a licence-expiry signal) next to the new one, as recommended.
- **Not built (later):** scheduled reminders, evidence-locker filing of conclusions, vendor-caused incident link. The 42 seeded questions are a draft for your counsel to review, particularly the DPDP section.

## Context: what exists today
There is no TPRM module. What exists is a basic vendor register under IT Operations (`vendors` table, `Operations.java`, `operations-vendors.html`):

- Fields: `name`, `agreement_details` (free text), `license_expiry`, `baseline_risk_score` (1-25, manually typed), `security_questionnaire_status` (`SENT` / `RECEIVED` / `EVALUATED` / `NOT_APPLICABLE`).
- Operations metrics count vendors with `license_expiry` within 90 days as "reviews due".
- Vendors are referenced from `data_assets.linked_vendor_id` and `data_asset_recipients.linked_vendor_id` (Data Governance), and VAPT engagements hold a free-text `vendor` name.
- `docs/functional-specs.md` section 6.2 promises "baseline vendor risk scoring or security questionnaire tracking"; today that is a status flag, not a workflow.

Goal: a proper third-party risk lifecycle on top of the existing vendor register: classify the vendor, assess it, record findings, track contracts and evidence, review on a cadence, and roll the result into the existing Risk Register and Data Governance. Same philosophy as the rest of Compass: beginner-friendly, self-hosted, no external services, human-in-the-loop.

Scope and constraints:
- India / DPDP focus. The processor-related checks cite DPDP Sec 8(2) (Data Fiduciary engaging a Data Processor under a valid contract), plus Rule 6 safeguards. Common-sense hooks for RBI outsourcing and CERT-In expectations can appear as optional questionnaire sections but are not a build target. No GDPR / CCPA expansion.
- Owned by the GRC team. RBAC: ADMIN full, GRC_OFFICER write, IT_STAFF no access, the same as the Risk and Data Governance modules. See "Placement" below.
- Vendors stay one shared entity. TPRM extends the existing `vendors` table rather than creating a second vendor list, so every existing link keeps working.

## Difficulty assessment
**Moderate-to-large. Roughly 12-16 working days for a solid v1; about 7-9 days for a minimal v1 (tiering, assessments with a fixed questionnaire, findings, review cadence).**

| Part | Effort | Why |
|---|---|---|
| Extend `vendors` (tier, status, owner, dates) | Small | Additive columns; backfill defaults |
| Inherent risk tiering | Small | Derivation like the DPIA scoring table |
| Questionnaire templates + assessments | Medium | Template -> question -> answer -> score. The largest new data model |
| Findings and remediation tracking | Small-Medium | Mirrors audit findings / VAPT findings already in the codebase |
| Contracts and documents | Small | Reuses the base64 file storage pattern (`ticket_attachments`, `incident_documents`) |
| Review cadence and due/overdue logic | Small | Date arithmetic plus dashboard counts |
| Link-ups (Risk Register, Data Governance, DPIA) | Medium | Cross-module queries; low risk but touches existing code |
| New console section (4-5 pages) | Medium-Large | Most of the calendar time |
| Vendor-facing questionnaire portal | Large | Deferred; see out of scope |

There is no technically hard part. The work is volume and getting the model right, so the main risk is scope creep. The plan below draws the v1 line deliberately.

## Placement (decided)
TPRM sits under **Risk & Vulnerability** (`risks.html`), as a third sub-page next to Risk Register and VAPT:

```
Risk & Vulnerability
  - Risk Register
  - VAPT
  - Third-Party Risk   <- new
```

Why there rather than a new top-level section:
- It is already GRC-owned: the `risks` module is ADMIN / GRC_OFFICER only, IT_STAFF none. No new permission module is needed.
- Findings escalate into the Risk Register, and VAPT (a vendor-performed activity with a free-text `vendor` field) already lives here, so the three share one risk workflow.
- It avoids a ninth top-level nav item.
- Compliance & Controls was the other candidate, but it is framework / control oriented; vendor risk is a risk-treatment discipline.

The Operations vendor page remains, because IT Operations keeps the day-to-day vendor list used by IT, but it becomes a lightweight register. It stays the place to add basic vendor details; IT_STAFF cannot see the risk data (tier, assessments, findings). GRC users get a "Manage in Third-Party Risk" link from it.

Sub-pages inside Third-Party Risk are tabs on a single page rather than extra nav entries, to keep the Risk & Vulnerability subnav short: Vendors, Assessments, Findings, Questionnaires (ADMIN).

## Design

### 1. Schema: `db/NN_tprm.sql` (next free number at implementation time)

**Extend `vendors`** (all nullable or defaulted, so existing rows and the Operations vendor page keep working):
- `vendor_type` CHECK (`SAAS`, `CLOUD_INFRA`, `SERVICE_PROVIDER`, `CONSULTANT`, `HARDWARE`, `OTHER`)
- `criticality` CHECK (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), the business-impact tier
- `lifecycle_status` CHECK (`PROSPECT`, `ONBOARDING`, `ACTIVE`, `OFFBOARDING`, `TERMINATED`) default `ACTIVE`
- `business_owner_id -> users`, `description`, `primary_contact_name`, `primary_contact_email`, `website`
- `processes_personal_data BOOLEAN`, `stores_data_outside_india BOOLEAN`, `has_network_access BOOLEAN`, `is_fourth_party_dependent BOOLEAN` (drive the inherent-risk score)
- `inherent_risk` CHECK (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`) and `inherent_score INT` (derived, stored as a snapshot)
- `residual_risk` (same values), set from the latest completed assessment
- `last_assessed_at`, `next_review_due DATE`, `review_cycle_months INT` (default by tier)
- The existing `baseline_risk_score` and `security_questionnaire_status` are kept and left alone for backward compatibility. `security_questionnaire_status` is kept in sync from assessments (see 3) rather than removed.

**New tables**
- `tprm_questionnaires`: `id`, `name`, `version`, `description`, `is_active`, `created_by`. Seeded with one default template ("Baseline Vendor Security Assessment").
- `tprm_questions`: `id`, `questionnaire_id -> ... ON DELETE CASCADE`, `section`, `position`, `text`, `guidance`, `answer_type` CHECK (`YES_NO`, `YES_NO_NA`, `TEXT`), `weight INT`, `is_critical BOOLEAN` (a "No" on a critical question forces a finding), `maps_to` (optional free-text reference, e.g. "DPDP Rule 6", "ISO 27001 A.5.19").
- `tprm_assessments`: `id`, `vendor_id -> vendors ON DELETE CASCADE`, `questionnaire_id`, `assessment_type` CHECK (`ONBOARDING`, `PERIODIC`, `EVENT_DRIVEN`), `status` CHECK (`DRAFT`, `SENT`, `IN_REVIEW`, `COMPLETED`, `CANCELLED`), `assessor_id -> users`, `score_pct`, `residual_risk`, `conclusion` CHECK (`APPROVED`, `APPROVED_WITH_CONDITIONS`, `REJECTED`), `summary`, `due_date`, `completed_at`, `created_at`.
- `tprm_answers`: `id`, `assessment_id -> ... ON DELETE CASCADE`, `question_id`, `answer`, `comment`, `evidence_ref`, `UNIQUE (assessment_id, question_id)`.
- `tprm_findings`: `id`, `vendor_id`, `assessment_id NULL`, `title`, `description`, `severity` CHECK (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), `status` CHECK (`OPEN`, `IN_PROGRESS`, `REMEDIATED`, `ACCEPTED`, `CLOSED`), `owner_id`, `due_date`, `remediation_plan`, `accepted_by`, `acceptance_reason`, `linked_risk_id -> risks ON DELETE SET NULL`, `closed_at`.
- `tprm_contracts`: `id`, `vendor_id -> ... ON DELETE CASCADE`, `contract_type` CHECK (`MSA`, `SOW`, `DPA`, `NDA`, `SLA`, `OTHER`), `title`, `start_date`, `end_date`, `auto_renews`, `notice_period_days`, `value_inr NUMERIC NULL`, `has_security_clauses`, `has_breach_notification_clause`, `has_audit_right`, `has_data_return_deletion_clause`, `notes`.
- `tprm_documents`: same shape as `ticket_attachments` (`file_name`, `file_path`, `sha256_checksum`, `uploaded_by`, `uploaded_at`), plus `vendor_id`, optional `assessment_id` / `contract_id`, and `doc_type` CHECK (`CERTIFICATE`, `SOC_REPORT`, `PENTEST_SUMMARY`, `POLICY`, `CONTRACT`, `OTHER`), plus `valid_until DATE` (so expiring certificates surface in the review dashboard).
- Permissions: no new `role_permissions` rows. TPRM reuses the existing `risks` module (ADMIN: ADMIN, GRC_OFFICER: WRITE, IT_STAFF: NONE, SUPERVISOR: NONE).

### 2. Inherent risk tiering (server-side, one method; same style as DPIA scoring)
Derived from the vendor profile, shown with its contributing factors, editable override with a reason (so a GRC Officer can raise but the reason is on record).

| Factor | Source | Points |
|---|---|---|
| Business criticality | `criticality`: CRITICAL 4, HIGH 3, MEDIUM 2, LOW 0 | 0-4 |
| Processes personal data | `processes_personal_data` | 3 |
| Sensitive data involved | any linked data asset (via `linked_vendor_id` or recipient) with category HEALTH / FINANCIAL or sensitivity RESTRICTED | 2 |
| Data outside India | `stores_data_outside_india` or a linked `data_flows.is_cross_border` | 2 |
| Network / system access | `has_network_access` | 2 |
| Fourth-party dependence | `is_fourth_party_dependent` | 1 |

Score >= 8 -> CRITICAL, 5-7 -> HIGH, 3-4 -> MEDIUM, <= 2 -> LOW. (Thresholds to be sanity-checked against real vendors before shipping, as was done for DPIA.) The tier sets the default `review_cycle_months`: CRITICAL 6, HIGH 12, MEDIUM 24, LOW 36, and decides which questionnaire depth is recommended.

The "processes personal data" signal is largely already knowable from Data Governance: if a vendor appears as a recipient or linked vendor on any data asset, the vendor page shows "Linked to N data assets" and pre-ticks the flag. A vendor that is a recipient of personal data but has no signed DPA gets a computed gap (see 6).

### 3. Assessment workflow
1. GRC Officer opens a vendor and clicks **Start assessment**, picks a questionnaire (default pre-selected by tier) and type (Onboarding / Periodic / Event-driven). Status `DRAFT`.
2. v1 is **internally completed**: the assessor fills the answers from documents the vendor sent by email (SOC 2 report, ISO certificate, completed questionnaire). Each answer takes Yes / No / N/A, a comment, and an optional document reference. Status `IN_REVIEW`.
3. **Score on completion:** `score_pct = sum(weight of Yes) / sum(weight of applicable questions)`; N/A questions are excluded. Any "No" on an `is_critical` question auto-creates a finding (severity HIGH by default, editable). Residual risk = inherent risk stepped down one level for score >= 80%, unchanged for 50-79%, stepped up for < 50% (never below LOW / above CRITICAL).
4. Assessor records a **conclusion**: Approved / Approved with conditions / Rejected, plus a summary. Completing sets `vendors.last_assessed_at`, `residual_risk`, `next_review_due = completed_at + review_cycle_months`, and syncs `security_questionnaire_status` (`EVALUATED`).
5. A completed assessment is read-only. A re-assessment creates a new row, so history is kept and shown on a timeline.

Default questionnaire (about 35-45 questions, sections): Governance and policies; Access control and authentication; Data protection and encryption; Personal data handling (DPDP-aligned: purpose limitation, retention and erasure, breach notification to the fiduciary, sub-processors, cross-border transfer); Incident management; Business continuity; Secure development; Physical and HR security; Compliance and certifications. Content is seed data, editable in the UI by ADMIN.

### 4. Findings and remediation
- Findings can come from an assessment (auto or manual) or be raised standalone (e.g. after an incident at the vendor).
- Lifecycle `OPEN -> IN_PROGRESS -> REMEDIATED -> CLOSED`; `ACCEPTED` requires an approver and a reason, and only ADMIN or GRC_OFFICER can set it.
- Overdue findings (past `due_date`, not closed/accepted) are counted in metrics.
- **Escalate to Risk Register:** a button on a finding creates a `risks` row (category "Third Party", title from the finding, impact / likelihood mapped from severity) and stores `linked_risk_id`. One-way link, same spirit as ticket-to-incident escalation already in the product.

### 5. Contracts and documents
- Contract rows with key dates and the four clause checkboxes (security, breach notification, audit right, data return/deletion). Notice-period-aware **renewal due date** = `end_date - notice_period_days`.
- Documents reuse the existing base64-in / base64-out storage and SHA-256 checksum pattern; `valid_until` on certificates drives an "expiring in 60 days" list.
- The existing `license_expiry` column stays and is shown as legacy "License / agreement expiry".

### 6. Computed gaps (no extra tables)
Shown on the vendor page and rolled into dashboard counts:
- Personal-data vendor with no `DPA` contract (DPDP Sec 8(2)).
- Contract lacking breach notification or data return/deletion clause where personal data is processed.
- Review overdue (`next_review_due < today`) or never assessed.
- Expired or expiring certificate / SOC report.
- Open CRITICAL / HIGH finding past due date.
- Vendor `TERMINATED` / `OFFBOARDING` but still linked as a recipient of a live data asset.

### 7. Offboarding checklist
Moving a vendor to `OFFBOARDING` shows a fixed checklist (revoke access, return/delete data and obtain confirmation, retrieve assets, close open findings, final invoice). Ticks are stored as a small `offboarding_checklist JSONB` on the vendor; moving to `TERMINATED` requires all items ticked or an override reason. Deliberately light: a checklist, not a workflow engine.

### 8. Backend
- New `Tprm.java` (`service/v1`), modelled on `DataFlows.java` / `Operations.java`, registered at `/api/v1/tprm` in `_processor.tsi` and mapped `"tprm" -> "risks"` in `InterceptingFilter.SERVICE_MODULE_MAP`.
- Functions (grouped): `get_tprm_metrics`; `list_vendors_tprm`, `get_vendor_profile`, `update_vendor_profile`, `recalculate_inherent_risk`, `set_vendor_status`; `list_questionnaires`, `get_questionnaire`, `save_questionnaire`, `save_question`, `delete_question`; `start_assessment`, `get_assessment`, `save_answer`, `complete_assessment`, `cancel_assessment`, `list_assessments`; `list_findings`, `add_finding`, `update_finding`, `accept_finding`, `escalate_finding_to_risk`; `list_contracts`, `add_contract`, `update_contract`, `delete_contract`; `upload_document`, `get_document`, `delete_document`; `get_review_calendar`.
- The vendor list/add/update/import in `Operations.java` stay as-is (they still work on the original columns); `Tprm` owns the new columns. Avoids a risky rewrite of working code.
- Deletes are ADMIN-only via the existing `delete_` prefix rule. All writes go through `EventLog.log`.
- **Vendor deletion archives instead of deleting** (decided): see "Archiving" below. `Operations.deleteVendor` currently hard-deletes and would cascade into the new tables, so it is changed to archive.

### 9. Frontend
New sub-page under Risk & Vulnerability (see Placement; same chrome as the other modules, `rbac.js` mapping `'risks-tprm.html': 'risks'`). File naming follows the existing `risks-*.html` convention: `risks-tprm.html` (hub with tabs). Add a "Third-Party Risk" subnav link to `risks.html`, `risks-register.html`, `risks-vapt.html` and a card on the `risks.html` hub.
- Hub / Vendors tab: tiles (vendors by tier, assessments due in 30 days, overdue reviews, open findings, expiring documents/contracts) and the computed-gap list.
- Vendors tab: vendor table (tier, criticality, residual, status, next review, gap count) with filters; vendor detail drawer/page with tabs: Profile, Assessments, Findings, Contracts and Documents, Linked data (read-only list of data assets and recipients, links into Data Governance), Offboarding.
- Assessments tab: assessment list and the fill-in/score screen (section accordion, progress bar, live score).
- Findings tab: findings tracker with filters and bulk status change.
- Questionnaires tab (ADMIN): manage templates and questions.
- IT Operations vendors page: add a "Manage in Third-Party Risk" link visible to GRC_OFFICER / ADMIN only. Tier and next-review columns are shown only to those roles, so IT_STAFF see the plain register and nothing risk-related.
- Dashboard: one new tile, "Vendor reviews overdue", from `get_tprm_metrics`. Replace the Operations metric `vendor_reviews_due` (license-expiry proxy) with the real one, or show both during transition.

### 10. Integrations with existing modules
| Module | Link |
|---|---|
| Risk Register | Finding -> risk escalation (one way). Vendor page lists linked risks. |
| Data Governance | Vendor profile lists linked data assets and recipients. Missing-DPA gap uses them. A vendor with CRITICAL / HIGH residual risk is flagged on the Gap Check and (once built) the DPIA page for assets that share data with it. |
| Evidence / Audit | Assessment completion can optionally file the conclusion PDF into the evidence locker (nice to have, not v1). |
| Helpdesk / Incidents | Free-text vendor reference on an incident is out of scope for v1; later: "vendor-caused incident" link that triggers an event-driven assessment. |
| Notifications | Reuse the existing notification mechanism for "review due in 30 days" and "certificate expiring" to the business owner. Needs a look at how `Notification` is triggered (request-time vs scheduled) before committing. |

### 11. Archiving instead of deleting (decided)
- `vendors` gains `archived_at TIMESTAMPTZ NULL` and `archived_by -> users`. Deleting a vendor (from either the Operations vendor page or the TPRM page) sets these and `lifecycle_status = 'TERMINATED'`; it does **not** remove any row. Assessments, answers, findings, contracts and documents stay intact and become read-only.
- Archived vendors are hidden from the default lists, metrics, overdue/gap counts and review calendar. A filter "Show archived" lists them, and ADMIN or GRC_OFFICER can **Restore** (clears `archived_at`).
- Existing links are unaffected: `data_assets.linked_vendor_id` and `data_asset_recipients.linked_vendor_id` keep pointing at the archived vendor (history stays accurate), and Computed gaps flag a live data asset whose recipient is archived (see 6).
- The vendors `name` is `UNIQUE`, so an archived vendor still reserves its name. Adding a vendor with a clashing name returns "an archived vendor with this name exists, restore it instead".
- `delete_` stays ADMIN-only. A true hard delete is not offered in the UI; it remains a database administration task, as for other audit-relevant records.
- Operations metrics (`total_vendors`, `vendor_reviews_due`) and the Operations vendor list add `WHERE archived_at IS NULL`. This is the one required change to existing vendor queries.

### 12. Migration and compatibility
- All new `vendors` columns are nullable or defaulted; existing `baseline_risk_score` / `security_questionnaire_status` rows are untouched. A one-off backfill sets `criticality` from `baseline_risk_score` (>= 15 HIGH, >= 8 MEDIUM, else LOW) so nothing starts blank, and `next_review_due` from `license_expiry` where present. Backfill is plain SQL in the migration; reviewable and reversible.
- Existing installs get the migration by running the new SQL file, as with `18_data_governance.sql`.

## Phasing (recommended)
1. **Phase 1 (minimal v1, about 7-9 days):** vendor profile columns, inherent tiering, questionnaire + internal assessments with scoring, findings, review cadence, hub page and vendor/assessment pages.
2. **Phase 2:** contracts and documents, computed gaps, escalate-to-risk, notifications, offboarding checklist, IT Operations page link-up.
3. **Phase 3 (separate decision):** vendor self-service questionnaire portal, scheduled reminders, continuous-monitoring feeds.

## Explicitly out of scope
- Vendor-facing portal for completing questionnaires (needs a public, token-secured page. The `secure_link_token` pattern from VAPT could be reused later, but it adds an unauthenticated surface that deserves its own security review).
- External risk-rating feeds (security-scorecard style), dark-web or breach monitoring, financial-health scoring.
- Spend/procurement/PO management and invoice tracking.
- Fourth-party (vendor's vendors) mapping beyond a single flag.
- E-signature or contract redlining.
- Non-Indian regulations (GDPR Art. 28, HIPAA BAAs). RBI/SEBI outsourcing guidance can be a questionnaire add-on, not core logic.

## Decisions made
- **Ownership / placement:** GRC team; sub-page under Risk & Vulnerability, reusing the `risks` permission module (IT_STAFF none).
- **Assessments are completed internally** by the assessor from documents the vendor emails. No vendor portal in v1; the data model does not preclude adding one later.
- **Vendor deletion archives** the vendor with its assessments, findings, contracts and documents (restorable, read-only); nothing is hard-deleted.

## Open points to confirm
1. **Questionnaire content:** I will draft the default 35-45 questions; someone should review the DPDP sections.
2. **Replace or keep** the Operations `vendor_reviews_due` metric (licence-expiry based)? Recommendation: keep it for IT Operations (it is a licence/contract-renewal signal that IT cares about) and add the separate TPRM "reviews overdue" metric for GRC.
3. **Fully archived vendors and the unique name:** acceptable that an archived vendor reserves its name until restored?

## Verification
1. Apply the migration on a copy of an existing database that has vendors: the old vendor page still lists, adds, edits, imports and archives; backfilled `criticality` / `next_review_due` look sensible.
2. Tiering: create vendors covering the extremes (a CRITICAL cloud provider processing personal data abroad; a LOW stationery supplier). Tiers match the scoring table. Overriding requires a reason.
3. Assessment: complete the default questionnaire at 90% / 60% / 30% and confirm score, residual-risk step, auto-created findings for critical "No" answers, `next_review_due`, and `security_questionnaire_status` sync. A completed assessment cannot be edited; a new one keeps history.
4. Findings: full lifecycle; ACCEPTED requires reason and role; escalation creates exactly one linked risk and a second click does not duplicate.
5. Contracts / documents: upload, download, checksum verification; expiring-in-60-days lists work; renewal date honours notice period.
6. Computed gaps: personal-data vendor without a DPA appears; adding a DPA contract clears it; a terminated vendor still listed as a live recipient is flagged.
7. Data Governance cross-checks: link a vendor to a data asset and as a recipient, confirm both show on the vendor page.
8. RBAC: GRC_OFFICER write, IT_STAFF gets 403 on `/api/v1/tprm` (reads and writes) and sees no risk columns on the Operations vendor page, ADMIN only for deletes and questionnaire editing.
9. Archiving: archive a vendor that has assessments, findings, contracts and documents. All rows remain, vendor disappears from default lists and counts, archived records are read-only, linked data assets still show the vendor, restore brings everything back, re-adding the same name is refused with the restore hint.
10. Audit trail entries for each write, assessment completion, finding acceptance and escalation.
