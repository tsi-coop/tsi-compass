# Data Governance — Phase 2 Plan

Covers: menu/IA restructure, Data Principal registry, purpose/retention/access, gap assessment, data flow mapping, RoPA export.

## Context

`docs/data-register-plan.md` shipped v1 of the Data Register: a self-declared inventory of *what* data exists, roughly *where*, and *who owns* it (`data_assets` table). It deliberately scoped out purpose, retention, downstream sharing, and any link to controls/frameworks, flagging control-mapping as "a natural phase 2."

This closes that gap, prompted by the WHAT/WHY/WHERE/WHO/WITH WHOM/HOW LONG model commonly used for a DPDP-style data inventory, plus the observation that a flat inventory record doesn't capture how data actually moves (e.g. a discharge summary sent over WhatsApp). The module stays **framework-agnostic** per the v1 design principle — no field is named after a specific regulation; DPDP is just the first framework it gets checked against, via the `frameworks`/`framework_requirements` tables that already exist and are already seeded with a DPDP entry (`db/01_init.sql`).

**Revision note:** this pass also promotes Data Register out from under "Reports & Exports" into its own top-level **Data Governance** section, and adds a **Data Principal registry** — *whose* data an asset holds (Customer, Employee, Patient, …), not just what kind of data. The trigger was RoPA: reviewing 2d's export made it clear the org wants to slice a RoPA by data principal specifically — "show me every processing activity that touches Customer data" — as the working basis for writing that principal's consent policy. A single free-text or single-enum field on `data_assets` can't answer that (one asset routinely serves more than one principal type — an EMR is Patient data, but also touches the Employee data of the doctors accessing it in an access-log sense... no, more concretely: a CRM holds both Customer and Vendor-contact data in one table). It needs its own small register and a many-to-many tag, the same shape as recipients/access from 2b.

## Pieces at a glance

Six pieces. **0** is nav-only, touching every console page's `<nav>` block but no backend. **2a** (Data Principals) is a genuine dependency of 2b (tagging) and 2e (RoPA's principal filter), so despite the letter it's built *first*. 2b, 2c, 2d are independent of each other. 2e is downstream of 2a, 2b, and 2d, so it's last.

- **0 — Navigation restructure.** Moves SBOM/CBOM under IT Operations (relabeled), retires the now-empty Supply Chain menu, and creates a new Data Governance top-level menu.
- **2a — Data Principal Registry.** A small, org-editable master list of *who* the data is about, plus a tag join to `data_assets`. New table pair, new page — first thing under the new menu.
- **2b — Purpose, retention, recipients, access.** Extends `data_assets` and adds two join tables, one of them the Data Principal tag from 2a. No new page — folds into the existing Add/Edit modal.
- **2c — Gap Assessment.** Reuses the existing `frameworks` → `framework_requirements` → mapping-table pattern (same shape as `control_requirement_mappings`) to link data assets to requirements, then computes what's missing.
- **2d — Data Flow Mapping.** Records how data moves between systems/parties, including ad-hoc channels (WhatsApp, email, USB) the org doesn't formally manage. New table, new page.
- **2e — RoPA Export.** Not a register — a computed report over 2a + 2b + 2d (and optionally 2c) rendered in the standard Records of Processing Activities shape, filterable by framework *and* by data principal. Its own page under Data Governance, not a Reports & Exports card.

---

## 0. Navigation restructure

Every console page carries its own full copy of the `<nav class="nav">` block (there's no shared partial/include — confirmed by grepping for `data-register.html`, which shows up in all ~40 files under `web/console/`). That makes any nav change mechanically wide (edit every page) but conceptually simple (the same find-replace repeated ~40 times) — worth doing as one pass with a script or sed, not 40 manual edits.

The existing "parent + indented children" pattern already exists — no new CSS needed. From `operations.html`:
```html
.nav a.subnav{padding:6px 10px 6px 28px;font-size:12px;font-weight:600}
.nav a.subnav.active,.nav a.subnav:hover{background:#eaf5f3;color:var(--teal);font-weight:700}
...
<a href="operations.html" class="active">IT Operations</a>
<a href="operations-changes.html" class="subnav">Change Management</a>
<a href="operations-assets.html" class="subnav">Asset Inventory</a>
```
It's static markup repeated per page (always expanded, no collapse/toggle) — the new Data Governance entry follows the identical shape.

### SBOM / CBOM → IT Operations

- Rename `supplychain-sbom.html` → `operations-sbom.html`, `supplychain-cbom.html` → `operations-cbom.html`. Keep "sbom"/"cbom" in the filename (still the correct technical terms) — only the nav *label* changes.
- Nav label: "SBOM" → **"Software Inventory"**, "CBOM" → **"Crypto Inventory"**, both as `subnav` children under "IT Operations", after the existing four (Change Management, Asset Inventory, Vendor Register, Helpdesk).
- `operations.html`'s hub-grid gains two more `hub-card` entries, same shape as its existing four, linking to the renamed pages.
- **Decision (confirmed):** fold SBOM/CBOM's RBAC from the separate `supplychain` module into `operations`, matching their new visual/organizational home. Before building: check `01_init.sql`'s seed `role_permissions` rows for any role where `supplychain` and `operations` currently differ, and call out the resulting access change (if any) in release notes — this is the one place this restructure can silently change who can see what.
- Update `rbac.js` `PAGE_MODULE`: drop the two `supplychain-*` entries, add `'operations-sbom.html': 'operations'`, `'operations-cbom.html': 'operations'`.
- `platform-import.html`'s CBOM/SBOM bulk-import tabs reference them by element id (`tab-btn-cbom`/`tab-btn-sbom`), not filename — unaffected by the rename.

### Supply Chain menu — retire

`supplychain.html` is a hub page whose entire `hub-grid` is the two cards for SBOM and CBOM — nothing else. Once those move out, the hub has no content left to hub. Recommend deleting `supplychain.html` and its top-level nav entry outright, rather than leaving an empty landing page. If Supply Chain content grows later (e.g. a broader third-party software risk view beyond SBOM/CBOM), it can be reintroduced as its own section then — no need to hold a placeholder now.

### New "Data Governance" menu

Top-level nav entry, positioned **after IT Operations** (where Data Register used to sit standalone), with four `subnav` children in this order:

```html
<a href="operations.html">IT Operations</a>
<a href="operations-changes.html" class="subnav">Change Management</a>
<a href="operations-assets.html" class="subnav">Asset Inventory</a>
<a href="operations-vendors.html" class="subnav">Vendor Register</a>
<a href="operations-helpdesk.html" class="subnav">Helpdesk</a>
<a href="operations-sbom.html" class="subnav">Software Inventory</a>
<a href="operations-cbom.html" class="subnav">Crypto Inventory</a>
<a href="data-governance.html">Data Governance</a>
<a href="data-governance-principals.html" class="subnav">Data Principals</a>
<a href="data-governance-register.html" class="subnav">Data Register</a>
<a href="data-governance-flows.html" class="subnav">Data Flows</a>
<a href="data-governance-ropa.html" class="subnav">RoPA Export</a>
```

Order is deliberate: Data Principals first, per the ask — you define *who* your data is about before you tag assets against it. `data-governance.html` itself is a new hub/landing page, same pattern as `operations.html`/the old `supplychain.html`: a page header, a few headline stat cards (total data assets, total flows, gap count, principals defined), and a `hub-grid` of four cards linking to the subpages.

**Renames from earlier drafts of this plan**, now superseded:
- `data-register.html` → `data-governance-register.html` (content and functionality unchanged, only its file name and nav position move)
- `data-flows.html` → `data-governance-flows.html` (2d, below)
- RoPA Export is no longer a card on `reports.html` — it's `data-governance-ropa.html` (2e, below)

`rbac.js` `PAGE_MODULE`: replace `'data-register.html': 'data'` with entries for all four new filenames, all still mapped to the `data` module (same permission scope as today — no RBAC change here, unlike the SBOM/CBOM question above).

---

## 2a. Data Principal Registry

*Who* the data is about — Customer, Employee, Patient, Vendor Contact — as its own small, **org-editable** register, not a `CHECK` enum. This is a deliberate departure from how `category`/`sensitivity` work elsewhere in the register: those are generic enough to hardcode once, but a hospital needs "Patient," a school needs "Student"/"Guardian," a B2B SaaS needs "Customer"/"Subscriber" — the list is genuinely org-specific. Shape it like `frameworks` (an editable master table the org grows over time), not like the `category` `CHECK` constraint.

### Schema — new `db/03_data_governance.sql` (or folded into the phase-2 script; this now becomes the natural home for all of 2a–2e's tables)

```sql
CREATE TABLE data_principals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE data_asset_principals (
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    principal_id UUID NOT NULL REFERENCES data_principals(id) ON DELETE CASCADE,
    PRIMARY KEY (data_asset_id, principal_id)
);

INSERT INTO data_principals (name, description) VALUES
    ('Customer', 'End customers or end users of the org''s own products/services'),
    ('Employee', 'Current or former staff'),
    ('Patient', 'Individuals receiving care, where applicable'),
    ('Vendor Contact', 'Named individuals at third-party vendors/suppliers'),
    ('Prospect', 'Leads / prospective customers, not yet onboarded');
```

Seed rows are a starting point, not a fixed list — delete/rename freely per org. `data_asset_principals` is many-to-many, not many-to-one, because one asset routinely serves more than one principal (a CRM instance holding both Customer records and Vendor Contact records is one `data_asset` row with two tags).

### Backend — `DataRegister.java`

- `list_data_principals` / `add_data_principal` / `update_data_principal` / `delete_data_principal` — same CRUD shape used for other small master-data lists in the codebase; `delete_data_principal` should refuse (or warn) if it's still tagged on any asset, same guard style as other FK-protected deletes elsewhere.
- `list_data_asset_principals` / `add_data_asset_principal` / `delete_data_asset_principal` — same add/remove trio shape as `data_asset_recipients`/`data_asset_access` from 2b, scoped by `data_asset_id`.
- `list_data_assets` detail fetch (opened for edit) also returns its tagged principals, same pattern as 2b's recipients/access.

### Frontend — new `web/console/data-governance-principals.html`

- Single page, no subnav of its own (it's already a subnav leaf under Data Governance) — same shell as `controls-frameworks.html` or similar simple register pages: a table (Name, Description, # Assets tagged, Actions) plus an Add/Edit modal with just `name`/`description`.
- On `data-governance-register.html`'s Add/Edit modal (2b), a new "Data Principals" section: a chip-style multi-select drawing from `list_data_principals` — pick one or more, render as removable chips. Simpler than the recipients/access mini-lists (no per-tag metadata like purpose or access level, just a tag), so a plain multi-select or checkbox group is enough — no need for the fuller add-row + table pattern.
- `data-governance-register.html`'s list table gains a "Principals" column showing the tag chips (e.g. `Patient` `Employee`) for quick scanning, same visual language as the existing `category`/`sensitivity` tags.

---

## 2b. Purpose, retention, recipients, access

*(Was "2a" in the previous draft of this plan — content unchanged except for the Data Principals tagging note below, which now lives in 2a.)*

### Schema — add to `db/02_data_register.sql` (or the new phase-2 script)

```sql
ALTER TABLE data_assets
    ADD COLUMN purpose TEXT,
    ADD COLUMN processing_basis VARCHAR(50) CHECK (processing_basis IN ('CONSENT','LEGITIMATE_USE','CONTRACTUAL','LEGAL_OBLIGATION','OTHER')),
    ADD COLUMN retention_period VARCHAR(100),
    ADD COLUMN deletion_trigger VARCHAR(255);

CREATE TABLE data_asset_recipients (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    recipient_type VARCHAR(30) NOT NULL CHECK (recipient_type IN ('VENDOR','REGULATOR','PARTNER','GROUP_ENTITY','OTHER')),
    linked_vendor_id UUID REFERENCES vendors(id) ON DELETE SET NULL,
    recipient_name VARCHAR(255),
    purpose_of_sharing TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE data_asset_access (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    role VARCHAR(100),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    access_level VARCHAR(20) DEFAULT 'READ' CHECK (access_level IN ('READ','WRITE','ADMIN')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (role IS NOT NULL OR user_id IS NOT NULL)
);
```

Rationale: recipients and access are inherently many-to-many (one data element, several recipients; one recipient, several data elements) — the existing single `linked_vendor_id`/`owner_id` columns stay as-is (they capture "primary" vendor/owner, still useful for the list view) and these new tables capture the full fan-out. `role` in `data_asset_access` covers "the HR team" without forcing every access grant down to a named user.

### Backend — `DataRegister.java`

- `add_data_asset`/`update_data_asset`: add `purpose`, `processing_basis`, `retention_period`, `deletion_trigger` to the insert/update, same `COALESCE` pattern already used.
- `list_data_asset_recipients` / `add_data_asset_recipient` / `delete_data_asset_recipient` — scoped by `data_asset_id`, same shape as the existing list/add/delete trio.
- `list_data_asset_access` / `add_data_asset_access` / `delete_data_asset_access` — same shape.
- `list_data_assets`: no change to the query itself, but the detail fetch (when a row is opened for edit) needs to also return its recipients/access/**principals** rows — either a `get_data_asset_detail` function, or have the frontend fire the relevant list calls when the edit modal opens.

### Frontend — `data-governance-register.html`

- Add/Edit modal grows three new fields (`purpose` textarea, `processing_basis` dropdown, `retention_period` + `deletion_trigger` text inputs) in the existing form, plus the Data Principals chip-select from 2a.
- Below the main fields, two repeatable mini-lists inside the same modal: "Shared with" (recipients) and "Who has access" (access), each a small add-row + table.
- List table: no new columns by default beyond the Principals chips from 2a (would overcrowd it) — but the "Classify" style side panel/detail view should surface purpose/retention/recipients at a glance.

---

## 2c. Gap Assessment

*(Was "2b" — unchanged.)*

### Schema

```sql
CREATE TABLE data_asset_requirement_mappings (
    data_asset_id UUID REFERENCES data_assets(id) ON DELETE CASCADE,
    requirement_id UUID REFERENCES framework_requirements(id) ON DELETE CASCADE,
    PRIMARY KEY (data_asset_id, requirement_id)
);
```

Same shape as `control_requirement_mappings` — deliberately, so the UI pattern (a multi-select of requirements against a framework picker) can be lifted from wherever `controls.html` already does control→requirement mapping.

### What counts as a "gap" (v2 logic, computed not stored)

For each data asset, per row:
- `discovery_status != 'REVIEWED'` → not yet reviewed
- `sensitivity IN ('CONFIDENTIAL','RESTRICTED')` or `category = 'PII'` **and** `processing_basis IS NULL` → no lawful/processing ground recorded
- retention fields both null → no retention policy recorded
- no row in `data_asset_requirement_mappings` → not mapped to any control/requirement at all

### Backend

- `get_gap_assessment(framework_id?)` — for each data asset (optionally filtered to those relevant to a given framework via its mapped requirements), returns which of the above checks failed, plus a `gap_count`.
- `get_data_metrics` gains a `gap_count` figure (assets with ≥1 unresolved gap) — same idea as the existing `discovered_count`.
- `map_data_asset_requirements` / reuse `list_data_asset_requirement_mappings` — same add/remove pattern as `control_requirement_mappings` handling elsewhere in the codebase (check `Controls.java` for the exact existing method names to mirror rather than reinvent).

### Frontend

- New stat card on `data-governance-register.html`: "Gaps" (count), stripe-red.
- New filter option: "Has gaps" toggle.
- Row detail/edit view shows a checklist of the four gap conditions with pass/fail, plus the framework-requirement multi-select.
- No new page needed — this rides on the existing Data Register page.

---

## 2d. Data Flow Mapping

*(Was "2c" — unchanged apart from the page rename covered in section 0.)*

A data flow is a directed movement of data between two points — either two registered data assets, or a registered asset and an ad-hoc/unmanaged endpoint (a channel like WhatsApp, personal email, a USB drive) that the org wants visibility into precisely *because* it isn't formally managed.

### Schema — new `db/03_data_governance.sql` (same file as 2a, per the numbered-script pattern)

```sql
CREATE TABLE data_flows (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    source_data_asset_id UUID REFERENCES data_assets(id) ON DELETE SET NULL,
    source_label VARCHAR(255),
    destination_data_asset_id UUID REFERENCES data_assets(id) ON DELETE SET NULL,
    destination_label VARCHAR(255),
    channel VARCHAR(50) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','API','FILE_TRANSFER','CLOUD_SYNC','PHYSICAL_MEDIA','PRINTOUT','OTHER')),
    is_approved_channel BOOLEAN DEFAULT FALSE,
    data_elements TEXT,
    frequency VARCHAR(50),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    description TEXT,
    is_cross_border BOOLEAN NOT NULL DEFAULT FALSE,
    destination_country VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_data_asset_id IS NOT NULL OR source_label IS NOT NULL),
    CHECK (destination_data_asset_id IS NOT NULL OR destination_label IS NOT NULL)
);
```

`*_label` free-text columns exist so a flow can point at something not in the register at all (e.g. "Receptionist's personal WhatsApp") — capturing that gap is the entire point of the hospital-WhatsApp example; forcing every endpoint to be a pre-registered asset would defeat it. `is_cross_border`/`destination_country` are folded in here directly (previously drafted as a separate 2e addition) since they describe the flow, not the asset — one asset can have both a domestic flow and an overseas one.

### Backend — new `src/org/tsicoop/compass/service/v1/DataFlows.java`

Same `Action`/`switch(func)` shape as `DataRegister.java`: `get_flow_metrics`, `list_data_flows` (filter by channel, approved/unapproved, linked asset, cross-border), `add_data_flow`, `update_data_flow`, `delete_data_flow`, plus `list_data_assets` (reuse, for the source/destination pickers).

Register route: `/api/v1/dataflows=org.tsicoop.compass.service.v1.DataFlows` in `web/WEB-INF/_processor.tsi`. RBAC: `data` module, same as the rest of Data Governance.

### Frontend — new `web/console/data-governance-flows.html`

v2 scope is a **table**, not a diagram — "Source → Channel → Destination" rows, with an "Unapproved channel" badge highlighting exactly the shadow-IT cases (WhatsApp, personal email) the article calls out. Add/Edit modal gets an "Approved channel" checkbox and, when the destination isn't domestic, a conditional "Cross-border" checkbox + country field. A real flow diagram (nodes/edges) is a natural v3 but adds real complexity (layout engine, likely a JS graph library) for a feature whose main value — visibility — is already delivered by the tabular view.

Nav: see section 0 — lives under the new Data Governance menu.

---

## 2e. RoPA Export

*(Was "2d" — schema and scope revised: the data-subject-category gap is now closed by 2a instead of a single-enum column, and this is now its own page instead of a Reports & Exports card.)*

A Record of Processing Activities (RoPA — Art. 30 GDPR, and the equivalent under DPDP/most privacy frameworks) is a standard-shaped report, not a distinct data model. Once 2a (who), 2b (purpose/basis/retention/recipients), and 2d (how data moves) exist, most of a RoPA's required columns are already sitting in `data_assets`, `data_asset_principals`, `data_asset_recipients`, and `data_flows` — this piece joins them into that shape. The v1 plan (`data-register-plan.md`) deliberately scoped out RoPA and avoided the `ropa` route name to not imply more coverage than v1 delivered; 2e is where that coverage actually lands, without ever needing to build a "RoPA module."

### What's already covered, and what isn't

| RoPA field | Source | Gap? |
|---|---|---|
| Activity name / description | `data_assets.name`, `.description` | — |
| Purpose | `data_assets.purpose` (2b) | — |
| Legal / lawful basis | `data_assets.processing_basis` (2b) | — |
| Categories of recipients | `data_asset_recipients` (2b) | — |
| Retention period | `data_assets.retention_period`, `.deletion_trigger` (2b) | — |
| Transfer mechanism | `data_flows` (2d) | — |
| Cross-border transfer flag | `data_flows.is_cross_border`/`.destination_country` (2d) | — |
| **Categories of data subjects** | `data_asset_principals` → `data_principals` (2a) | — *(closed by 2a, no longer needs an additive column)* |
| Security measures | `data_asset_requirement_mappings` → `framework_requirements` (2c), if those are in turn linked to `controls` | partial — depends on 2c being built and requirements having a control mapping |
| **Controller vs. processor role** | nothing | **yes** |

Only one real gap remains, and it gets one additive column.

### Schema — add to the same phase-2 script

```sql
ALTER TABLE data_assets
    ADD COLUMN controller_role VARCHAR(20) NOT NULL DEFAULT 'CONTROLLER' CHECK (controller_role IN ('CONTROLLER','PROCESSOR','JOINT'));
```

`controller_role` sits on the asset (not org-wide) because the same org can be controller for its own HR data and processor for a client's end-customer data held on their behalf.

### Backend

- `get_ropa_export(framework_id?, principal_id?)` on `DataRegister.java` — one row per `data_asset`, with `data_asset_principals`, `data_asset_recipients`, and `data_flows` each aggregated (string-concatenated, same idea as any "roll up children into one export row" query) and, if 2c is built, `data_asset_requirement_mappings` → `framework_requirements` → `controls` joined in for a security-measures column.
  - `framework_id` scopes to assets mapped to that framework, same pattern as `get_gap_assessment`.
  - `principal_id` scopes to assets tagged with that principal — **this is the "data-principal-wise" export**: pick "Customer" and get back only the processing activities that touch customer data, in one place, as the working document for that principal's consent policy.
- Row shape: `activity_name, purpose, legal_basis, data_principals, personal_data_elements, recipients, retention_period, cross_border_transfers, security_measures, controller_role, owner`. (`data_subject_category` from the earlier draft is now `data_principals`, sourced from 2a instead of a fixed enum, and concatenated the same way `recipients` already is when more than one principal is tagged.)
- Read-only — no write path of its own.

### Frontend — new `web/console/data-governance-ropa.html`

- Own page under Data Governance (not a `reports.html` card, per the menu restructure in section 0), matching the section's other pages: page header, a filter bar with **Framework** and **Data Principal** dropdowns, a preview table, and an "Export CSV" button.
- Selecting a Data Principal is the primary intended path for the consent-policy use case — the filter narrows the whole table to that principal before export, rather than requiring the user to filter the downloaded CSV afterward.
- `controller_role` folds into the 2b modal fields on `data-governance-register.html` — one more `form-row.half`.

### Out of scope (still, per this pass)

- DPIA (Data Protection Impact Assessment) generation
- Automatic legal-basis suggestion or validation
- Auto-drafting the consent policy text itself — this export is the input to that work, not the policy document
- Multi-language / multi-framework RoPA templates (one shape, framework-agnostic, same as the rest of the register)
- Signature/approval workflow on the export

### Verification

- Tag Patient EMR Database with the "Patient" principal; export RoPA filtered to "Patient" and confirm it appears with concatenated recipients, concatenated flows, retention, and legal basis all populated.
- Tag a second asset (e.g. Vendor Invoices Archive) with "Vendor Contact" only; confirm filtering the export to "Patient" excludes it, and filtering to "Vendor Contact" includes it.
- Mark one flow `is_cross_border = true` with a `destination_country`; confirm it surfaces in the export's transfer column.
- Export a data asset with no mapped requirements: confirm the security-measures column renders blank, not an error.
- Delete a `data_principal` that's still tagged on an asset: confirm the delete is refused (or the tag is cleanly removed, per whichever guard is implemented) rather than silently orphaning `data_asset_principals` rows.

---

## Suggested build order

1. **0** (nav restructure) — no backend dependency, can land anytime; doing it first means every later piece is built directly in its final location instead of being moved afterward.
2. **2a** (Data Principal registry) — built early despite its position in the letter order, because 2b's tagging UI and 2e's principal filter both depend on it existing.
3. **2b** (purpose/retention/recipients/access) — extension of existing table/page, highest immediate compliance value.
4. **2c** (gap assessment) — depends on 2b's fields existing to have anything to flag; reuses an existing mapping-table pattern.
5. **2d** (data flow mapping) — independent of 2a–2c, could be built any time after 0; largest net-new surface (new table, new service class, new page).
6. **2e** (RoPA export) — last, by necessity: reads what 2a, 2b, and 2d all store.

## Out of scope (still, per this pass)

- DSAR/grievance tracking, consent logging, breach-notification workflow (unchanged from v1 scoping) — note 2e's export is meant to *feed* future consent-policy work, not replace it
- Automated discovery/scanning — still self-declared
- Visual (node/edge) flow diagram — table view only for 2d
- DPIA generation, legal-basis auto-suggestion, RoPA approval workflow — see 2e's own out-of-scope note
- Reintroducing "Supply Chain" as a section — retired in section 0; revisit only if its scope grows beyond SBOM/CBOM
- Cleanup of the dead `_processor.tsi` stub routes (`ropa`, `consent`, `fiduciary`, `legal`, `job`, `app`) — still flagged, still separate; worth another look once 2e ships, since it's the piece that would have justified the `ropa` name

## Verification (once built)

- Nav: confirm "Software Inventory"/"Crypto Inventory" appear under IT Operations, "Supply Chain" is gone from every page, and "Data Governance" appears after IT Operations with all four subnav children in the specified order.
- Define two data principals ("Patient", "Employee"); tag a data asset with both; confirm both chips render on the list and detail views.
- Add a data asset, set purpose/processing_basis/retention, add two recipients and one role-based access grant; confirm all persist and reload correctly.
- Leave a `PII`/`CONFIDENTIAL` asset with no `processing_basis` — confirm it shows up in the gap count and gap checklist.
- Map a data asset to a DPDP `framework_requirements` row; confirm the gap checklist's "not mapped" condition clears.
- Create a data flow from a registered asset to a free-text destination ("Personal WhatsApp") with `channel = WHATSAPP`, `is_approved_channel = false`; confirm it renders with the unapproved badge on `data-governance-flows.html`.
- Export RoPA filtered by a single data principal; confirm the row set matches only assets tagged with that principal.
- `IT_STAFF` role still has no access to any Data Governance page, matching the existing `data` module permission (`NONE`).
