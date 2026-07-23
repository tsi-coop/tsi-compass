# Self-service portal for org users (tickets, change requests, policy attestation, security training)

## Context

TSI Compass's console (`web/console/`) is built for IT/GRC staff (`ADMIN`, `GRC_OFFICER`, `IT_STAFF`). Regular org employees have no way to interact with IT ops at all today — they can't log a change or raise a helpdesk ticket themselves. Investigation of the current schema/backend surfaced two things that make this a real gap, not just a missing UI:

1. **No general-employee role exists.** `db/01_init.sql:14` only allows `role IN ('ADMIN','GRC_OFFICER','IT_STAFF')`. The README advertises a `USER` role (`README.md:25`) that was never implemented — this plan finally builds it.
2. **Existing create paths trust client-supplied identity.** `Operations.addChange`/`addTicket` (`Operations.java:141-143,358-361`) take `requester_id`/`created_by` straight from the request body — today's console UI populates it from a staff dropdown, but nothing stops a client from setting it to any user's UUID. A self-service surface must not inherit this — identity has to be derived server-side from the authenticated session.

The goal: a separate, minimal portal where any `USER`-role employee can submit a ticket or a change request and see the status of their own submissions, while IT staff keep triaging everything through the existing console (`operations-helpdesk.html`, `operations-changes.html`) unchanged. No new workflow — self-service is just a new, safe front door into the existing `change_requests` and `helpdesk_tickets` tables.

Two more employee-facing actions extend cleanly through the same pattern and are included here: **policy attestation** (`policy_attestations` already has a `user_id` FK, per-policy uniqueness, and an `acknowledged_at` timestamp — README's "attestation workflows" was never given a self-service front end) and **security awareness training completion** (`campaign_enrollments` already has `user_id`/`status`/`completed_at`). Both are read-mostly and lower risk than tickets/changes — no new table writes beyond an acknowledgement/status flip scoped to the caller's own `user_id`, same trust-boundary rule as the rest of this plan.

Accounts are **self-registered but admin-approved**: an employee signs up with name/email/password, the account is created `PENDING`, and it only becomes usable once an `ADMIN` approves it. Login already refuses non-`ACTIVE` accounts (`User.java:64`, `WHERE status = 'ACTIVE'`), and the admin console's User Management page (`web/console/platform-users.html`) already has a `PENDING` status filter and an approve-to-`ACTIVE` action wired to the existing `set_user_status` func (`platform-users.html:503`, `Platform.java:348-401`) — so the approval side needs **no new backend or console work**, only the public sign-up form itself is new.

## Approach

### 1. New `USER` role + migration — `db/03_selfservice.sql`

Follow the `db/02_data_register.sql` pattern (own numbered file, applied after `01_init.sql`):

- Widen the role constraint:
  ```sql
  ALTER TABLE users DROP CONSTRAINT users_role_check;
  ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN','GRC_OFFICER','IT_STAFF','USER'));
  ```
  (Confirm the actual constraint name with `\d users` at implementation time if `users_role_check` doesn't match Postgres's auto-generated name.)
- Seed `role_permissions` for a new `selfservice` module key: `USER → WRITE`, `ADMIN → ADMIN`, `GRC_OFFICER → NONE`, `IT_STAFF → NONE` (IT staff keep using the full ops console, not this portal).
- Update `Platform.java:536-538` `ROLES[]` to add `"USER"` so the admin's user-management/role-permission-matrix UI can display/filter it correctly (self-registration bypasses the admin-create path, but the matrix and filters need to recognize the role).

### 2. Backend — self-registration in `User.java`, `/api/v1/user`

Add a `register` func alongside the existing `login`/`reset_password` funcs in `User.java` (same class, since it's an identity/account operation, not an IT-ops one):

- Input: name, email, password. Validate email uniqueness (reject if a `users` row already exists for that email), hash the password with the same utility `create_user` already uses (`Platform.java:213-220`).
- Insert into `users` with `role = 'USER'`, `status = 'PENDING'`.
- Add `"register"` to `InterceptingFilter.ADMIN_NOAUTH_FUNCS` (`InterceptingFilter.java:48-53`) — same mechanism `login`/`reset_password` already use to bypass the JWT check, since this must be callable by a logged-out visitor. `ADMIN_NOAUTH_FUNCS` is a single global set keyed by func name (not scoped per service class), so `register` just needs to be a globally-unique func name — confirmed no collision with existing funcs.
- No approval-flow changes needed (see Context) — an `ADMIN` reviews and activates `PENDING` `USER` accounts through the existing `platform-users.html` flow.

### 3. Backend — new `SelfService.java` service, `/api/v1/selfservice`

New class in `src/org/tsicoop/compass/service/v1/`, wired into:
- `web/WEB-INF/_processor.tsi`: add `/api/v1/selfservice=org.tsicoop.compass.service.v1.SelfService`
- `InterceptingFilter.SERVICE_MODULE_MAP` (`InterceptingFilter.java:56-67`): add `"selfservice"→"selfservice"` — this is the wiring step the Data Register module skipped (per exploration, its `SERVICE_MODULE_MAP` entry is missing), so the centralized module-permission check would otherwise silently no-op for this module. Don't repeat that gap.

Funcs (all scoped to the caller — never trust client-supplied ids):
- `create_ticket` — inserts into `helpdesk_tickets` with `created_by` forced to the authenticated user's id (not read from the request body), `assigned_to` left null.
- `list_my_tickets` — `SELECT ... FROM helpdesk_tickets WHERE created_by = <self>`.
- `create_change_request` — inserts into `change_requests` with `requester_id` forced to the authenticated user's id, `status` forced to `SUBMITTED`.
- `list_my_changes` — `SELECT ... FROM change_requests WHERE requester_id = <self>`.
- `list_pending_policies` — `SELECT ... FROM policies WHERE status = 'PUBLISHED' AND id NOT IN (SELECT policy_id FROM policy_attestations WHERE user_id = <self>)`.
- `list_my_attestations` — policies already acknowledged by the caller, joined with `acknowledged_at`.
- `acknowledge_policy` — `INSERT INTO policy_attestations (policy_id, user_id) VALUES (?, <self>)`; the existing `UNIQUE (policy_id, user_id)` constraint (`01_init.sql:127`) makes this naturally idempotent.
- `list_my_trainings` — `campaign_enrollments` joined to `awareness_campaigns`/`training_materials`, filtered to `user_id = <self>`. Enrollment itself stays admin/GRC-driven (existing `campaign_enrollments` rows are created elsewhere when a campaign launches) — self-service only covers viewing and completing what's already assigned, not self-enrolling.
- `complete_training` — `UPDATE campaign_enrollments SET status = 'COMPLETED', completed_at = now() WHERE campaign_id = ? AND user_id = <self>`.

These are new methods, not modifications to `Operations.java`'s existing `listChanges`/`addChange`/`listTickets`/`addTicket` or to `Governance.java`'s policy funcs — keeps the IT-staff/GRC-staff console behavior untouched and isolates the new trust boundary in one place. All of it rides under the single `selfservice` role_permissions module already seeded in step 1, so no extra RBAC wiring is needed per capability — `USER` gets `WRITE` on `selfservice` once, and every func above is covered.

**Required fix**: resolving "the authenticated user's id" server-side needs `InputProcessor.getAuthenticatedUserId(req)` to actually work. Exploration found it currently queries a nonexistent `operators`/`email_hmac` table (`InputProcessor.java:243`) — dead code from a different template, harmless while unused, but self-service now genuinely depends on it. Fix it to query `users` by `email` (the real schema), matching the pattern already used elsewhere for `getEmail(req)`/`getRole(req)`.

### 4. Frontend — new `web/selfservice/` folder

Separate from `web/console/`, modeled on the flat/no-build-step HTML+inline-JS+inline-CSS pattern already used throughout (copy the `<style>` block from an existing console page, e.g. `operations-changes.html:7-48`, for visual consistency), but trimmed to just:

- `web/selfservice/register.html` — public, no auth: name, email, password (+confirm) form posting `register` to `/api/v1/user`. On success, show "Your request has been submitted — an administrator will approve your account before you can log in" (no auto-login, since the account is `PENDING`).
- `web/selfservice/index.html` — "My Requests": a single list combining the user's tickets and change requests with status, fed by `list_my_tickets` + `list_my_changes`. Nav bar for the four logged-in pages: My Requests / New Ticket / New Change / Policies / Training.
- `web/selfservice/new-ticket.html` — form: title, description, priority. Posts `create_ticket` to `/api/v1/selfservice`.
- `web/selfservice/new-change.html` — form: title, description. Posts `create_change_request`.
- `web/selfservice/policies.html` — two lists: policies pending acknowledgement (from `list_pending_policies`, each with an "Acknowledge" button calling `acknowledge_policy`) and already-acknowledged policies with their `acknowledged_at` date (from `list_my_attestations`).
- `web/selfservice/training.html` — assigned trainings (from `list_my_trainings`) with status pill, a link out to `training_materials.content_url`, and a "Mark Complete" button calling `complete_training`.

Auth pattern for the three logged-in pages matches console pages: `localStorage` JWT (`Authorization: Bearer`), redirect to `../index.html` on missing token or 401. No `rbac.js` dependency needed — this folder is the whole surface for `USER`-role logins, not a gated subset of a larger nav.

**Login page** — add a "Sign up" / "Request access" link on `web/index.html` pointing to `selfservice/register.html`. Separately, `web/index.html:94-96` currently always sends every successful login to `console/dashboard.html`; branch on `res.body.role`: `USER` → `selfservice/index.html`, everything else → `console/dashboard.html` (unchanged). A `PENDING` account's login attempt already fails today (`User.java:64` filters `status = 'ACTIVE'`) — no change needed there, though the login page could optionally show a friendlier "pending approval" hint instead of a generic invalid-credentials error (nice-to-have, not required).

### 5. IT-ops / Governance / Incidents consoles — no changes needed

`operations-helpdesk.html` and `operations-changes.html` already read `created_by`/`requester_id` via `LEFT JOIN users` and display names (`Operations.java:103,315`), and the existing stage/status workflow (`change_requests.stage`, `helpdesk_tickets.status`) is exactly what IT staff use to triage. Governance staff already manage `policies` and can see who's attested via `policy_attestations`; GRC staff already manage `awareness_campaigns`/`campaign_enrollments`. Self-service submissions and attestations/completions show up in all of these automatically — no schema or triage-UI change required anywhere.

## Verification

1. Apply `db/03_selfservice.sql` against a running dev DB (`docker compose up -d`, then run the migration as `01_init.sql`/`02_data_register.sql` are applied).
2. Register a new account via `selfservice/register.html`; confirm it lands in `users` as `role='USER', status='PENDING'`, and that logging in with it fails until approved.
3. As `ADMIN`, filter Platform > User Management by status `PENDING`, find the new account, approve it to `ACTIVE`.
4. Log in as that user — confirm redirect to `selfservice/index.html`, not the console.
5. Submit a ticket and a change request from the new forms; confirm both appear in "My Requests" for that user.
6. Log in as `IT_STAFF`/`ADMIN` and confirm both new records appear in `operations-helpdesk.html` / `operations-changes.html` with the correct requester name, and can be triaged (status/stage updates) exactly as any other record.
7. As `ADMIN`/`GRC_OFFICER`, publish a policy and enroll the test `USER` in an awareness campaign via the existing console flows; log back in as the `USER` and confirm `policies.html` shows the pending policy (acknowledge it, confirm it moves to the acknowledged list and a `policy_attestations` row appears) and `training.html` shows the assigned training (mark it complete, confirm `campaign_enrollments.status` flips to `COMPLETED`).
8. Confirm `USER` role gets a 403 hitting `/api/v1/ops` or `/api/v1/governance` directly (module permission `NONE`), and that `list_my_tickets`/`list_my_changes`/`list_my_attestations`/`list_my_trainings` for one `USER` never return another user's records (test with two `USER` accounts).
