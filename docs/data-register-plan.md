# Data Discovery & Classification module

## Context

TSI Compass is a self-hosted, SME-focused IT GRC tool (Java servlet backend, Postgres, one service class per module under `src/org/tsicoop/compass/service/v1/`, one HTML page per screen under `web/console/`). It already tracks IT assets (`assets`), vendors (`vendors`), and maps controls to regulatory frameworks (ISO 27001, SOC 2, etc.) — but there's no register of *what data the org actually holds, where, and how sensitive it is*. That's the gap: today you can attest that a control like "encryption at rest" exists, but you can't say which data stores that control actually protects, because there's no data inventory to point it at.

The user wants a **simple** data discovery + classification register — explicitly not an automated scanner/DLP integration, and (per explicit scope narrowing) not bundling in protection-measure mapping, DSAR/grievance tracking, or breach workflows in this pass. This keeps it a self-declared register, consistent with how `assets` and `vendors` already work: someone manually enters what they know, the tool tracks and surfaces it. The module is deliberately **framework-agnostic** — it is not built around, or dependent on, any single regulation (e.g. DPDP); `category`/`sensitivity` are generic classification concepts that any framework mapping can be layered on top of later.

**Aside — found during exploration:** `web/WEB-INF/_processor.tsi` declares routes for `/api/v1/ropa`, `/api/v1/consent`, `/api/v1/fiduciary`, `/api/v1/grievance`, `/api/v1/legal`, `/api/v1/job`, `/api/v1/app`, but none of the corresponding Java classes exist anywhere in `src/` or compiled `target/` — dead config, almost certainly carried over from an unrelated template. Since this pass is intentionally narrower than a full RoPA (Record of Processing Activities — which also covers purpose, lawful basis, and third-party sharing, and is tied to a specific privacy-law concept), I'm **not** reusing the `ropa` route name, to avoid implying more scope — or a specific regulatory dependency — than this module delivers. The dead routes are left untouched for now — worth a separate cleanup pass, flagging it here so it's not forgotten.

## What's in v1

A new **Data Register** module: one table, one service class, one console page, added as its own top-level nav item (mirrors `reports.html`, which is also a single page with no subnav).

**Discovery** — a record of each place data lives: what it is, what kind of store, who owns it, where/with whom it sits (optionally linked to an existing IT asset or vendor so you're not re-entering things already in Operations).

**Classification** — a category (what kind of data) and a sensitivity level (how sensitive), plus a lightweight review workflow (`DISCOVERED` → `CLASSIFIED` → `REVIEWED`) so partially-filled-in records are visible as still needing work — this is the "simple" part standing in for a real discovery scan.

## Database — `db/init.sql`

Add a new section after Module 6 (IT Operations), since it references `assets`, `vendors`, and `users`:

```sql
CREATE TABLE data_assets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    system_type VARCHAR(50) NOT NULL CHECK (system_type IN ('APPLICATION','DATABASE','FILE_SHARE','SAAS','EMAIL','PHYSICAL','OTHER')),
    category VARCHAR(50) NOT NULL CHECK (category IN ('PII','FINANCIAL','HEALTH','EMPLOYEE','INTELLECTUAL_PROPERTY','PUBLIC','OTHER')),
    sensitivity VARCHAR(20) NOT NULL DEFAULT 'INTERNAL' CHECK (sensitivity IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    linked_asset_id UUID REFERENCES assets(id) ON DELETE SET NULL,
    linked_vendor_id UUID REFERENCES vendors(id) ON DELETE SET NULL,
    location VARCHAR(255),
    volume_estimate VARCHAR(100),
    description TEXT,
    discovery_status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED' CHECK (discovery_status IN ('DISCOVERED','CLASSIFIED','REVIEWED')),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    last_reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_data_assets_sensitivity ON data_assets(sensitivity);
CREATE INDEX idx_data_assets_category ON data_assets(category);
```

`role_permissions` — add a `data` module row per role, next to the existing `INSERT INTO role_permissions` block (~db/init.sql:557):
```sql
('ADMIN',       'data', 'ADMIN'),
('GRC_OFFICER', 'data', 'WRITE'),
('IT_STAFF',    'data', 'NONE');
```
(Matches the `controls`/`governance` pattern — GRC officers manage it, IT staff don't by default since this is a compliance register, not an ops tool.)

## Backend — new `src/org/tsicoop/compass/service/v1/DataRegister.java`

Same shape as `Controls.java`/`Operations.java` — implements `Action`, one `switch(func)` dispatch, `PoolDB`/`PreparedStatement` per method, `isBlank()` helper. Functions:

- `get_data_metrics` — counts: total registered, by sensitivity (`CONFIDENTIAL`+`RESTRICTED` as a headline "high-sensitivity" figure), count still `DISCOVERED` (not yet classified — the actionable backlog number, same idea as `pending_exceptions` in `Controls.getControlsMetrics()`)
- `list_data_assets` — joins `users` (owner + reviewer name), `assets` (linked asset name), `vendors` (linked vendor name); filters by `category`, `sensitivity`, `search` on name, same pattern as `Controls.listControls(input)`
- `add_data_asset` — required: `name`, `system_type`, `category`; optional owner/asset/vendor links, location, volume, description
- `update_data_asset` — `COALESCE`-based partial update, same as `Controls.updateControl`
- `update_classification` — dedicated function to set `category`/`sensitivity`/`discovery_status`, and stamp `reviewed_by`/`last_reviewed_at` when status moves to `REVIEWED` (keeps the classification action distinct from a general field edit, mirroring how `attest_control` is separate from `update_control`)
- `delete_data_asset` — delete + `EventLog.log(email, "DATA_ASSET_DELETED", ctx)`, same pattern as `Operations.deleteAsset`
- `import_data_assets` — CSV import via `CsvReader`, matching `Operations.importAssets` — every other register in this app supports bulk import, so this one should too
- `list_staff` / `list_it_assets` / `list_vendors` — lookup lists for the owner/link dropdowns, same as `Controls.listStaff()`/`listPolicies()`

Register the route in `web/WEB-INF/_processor.tsi`:
```
/api/v1/data=org.tsicoop.compass.service.v1.DataRegister
```

## Frontend — new `web/console/data-register.html`

Single page, no subnav — copy `reports.html`'s page shell (same CSS, same sidebar) since that's the existing precedent for a top-level module with one screen. Layout, following `operations-assets.html`/`controls-register.html` conventions:
- 3-4 stat cards at top (total, high-sensitivity count, still-discovered count) from `get_data_metrics`
- Filter bar: category dropdown, sensitivity dropdown, search box
- Table: Name, Type, Category, Sensitivity (colored badge — reuse the `stripe-*`/badge color conventions already in `controls.html`'s CSS), Owner, Linked Asset/Vendor, Status, last reviewed
- Add/Edit modal with the fields above; a lighter "Classify" action on each row opens a small modal for just `category`/`sensitivity`/`discovery_status` (the `update_classification` call), so classifying something already discovered is a one-click action
- CSV import button, matching the import UI already present on `operations-assets.html`

Add the nav link in every console page's sidebar (all ~28 files share the same `<nav class="nav">` block) between `controls.html` and `evidence.html`:
```html
<a href="data-register.html">Data Register</a>
```

Add to `PAGE_MODULE` in `web/console/rbac.js` (~line 26-29, next to the controls entries):
```js
'data-register.html': 'data',
```

## Out of scope for this pass (noted, not built)

- Mapping data assets to existing `controls` (protection-measure visibility) — natural phase 2, would just be a join table like `control_requirement_mappings`
- DSAR/grievance tracking, consent logging, breach-notification workflow — all deferred per earlier scoping
- Cleanup of the dead `_processor.tsi` stub routes (`ropa`, `consent`, `fiduciary`, `legal`, `job`, `app`) — flagged above, separate small task

## Verification

- `docker compose up -d` (or rebuild if already running) to apply the new `init.sql` on a fresh DB volume — note this schema change only applies on first init, so a fresh volume or a manual `ALTER`/migration is needed if testing against an existing running DB
- Log in as ADMIN, confirm "Data Register" nav item appears; log in as (or switch role to) `IT_STAFF`, confirm it's hidden per the `NONE` permission
- Add a data asset linked to an existing asset and an existing vendor, confirm the joins render owner/asset/vendor names in the list
- Use the "Classify" action to move a record `DISCOVERED → CLASSIFIED → REVIEWED`, confirm `reviewed_by`/`last_reviewed_at` populate only on the final transition
- Delete a record, confirm a `DATA_ASSET_DELETED` row appears in `platform-audit.html` (system_audit_trail)
- Import a small CSV, confirm rows land correctly and bad category/sensitivity values are rejected by the CHECK constraints
