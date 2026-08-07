# Supervisor Role & Approval Workflow

## Context

tsi-compass currently has four roles (`ADMIN`, `GRC_OFFICER`, `IT_STAFF`, `USER`). Self-service `USER` accounts are created via open public registration (`register.html`), land as `status='PENDING'`, and are activated by an Admin. Once active, a `USER` can submit helpdesk tickets and change requests, which immediately notify IT/ops staff — there is no review step in between.

For businesses with a large branch footprint but lean IT staff, every unfiltered ticket/change request reaching IT directly is a burden — many could be resolved, rejected, or clarified locally by a line manager before ever reaching IT. This plan introduces a **Supervisor** role: a business-side manager who oversees a set of `USER`s, provisions/manages their accounts, and — when a business opts in — must approve their tickets and change requests before IT/GRC ever sees them. Because this app is single-tenant per deployment (one instance = one business, confirmed — no organizations/tenants table exists anywhere in the schema), "business configuration" is implemented as one deployment-wide settings toggle, not multi-tenant isolation.

Decisions locked in with the user before this plan:
- Config scope: a single deployment-wide toggle (`require_supervisor_approval`), not true multi-tenancy.
- Self-registration is **removed entirely**. All accounts (Supervisors and Users) are provisioned by an Admin, or by a Supervisor for their own Users.
- If the toggle is on and a `USER` has no Supervisor assigned, ticket/change-request submission is **blocked** with an explicit error (never silently bypassed to IT).
- Approval gating applies to **both** tickets and change requests.
- Pending-approval items are **completely hidden** from `IT_STAFF`/`GRC_OFFICER` queues, not visible-but-locked.
- Hierarchy is single-level only — a Supervisor reviews their own Users directly; no nested supervisor-of-supervisors chains.

## Schema — new `db/07_supervisor_role.sql`

- Widen `users.role` CHECK to add `'SUPERVISOR'` (mirrors the existing widen done in `db/03_selfservice.sql:9-10` for `USER`).
- Add `users.supervisor_id UUID REFERENCES users(id) ON DELETE SET NULL` (nullable self-FK, indexed). Only meaningful for `role='USER'`; enforced in app code, not a DB constraint.
- New `business_settings(setting_key VARCHAR(100) PRIMARY KEY, setting_value VARCHAR(255) NOT NULL, updated_at ...)` — a generic key/value table (same shape family as `role_permissions`), seeded with `('require_supervisor_approval', 'false')` so every existing deployment is unaffected until an Admin opts in.
- Add `approval_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK (IN ('NOT_REQUIRED','PENDING','APPROVED','REJECTED'))`, `approver_id UUID REFERENCES users(id)`, `approved_at TIMESTAMP`, `rejection_reason TEXT` to both `helpdesk_tickets` and `change_requests`. `DEFAULT` backfills existing rows for free — no manual `UPDATE` needed. Note: this `approver_id` is the new Supervisor pre-approval gate, distinct from `change_requests.compliance_approver_id` (existing downstream compliance sign-off field) — keep them separate.
- Seed `role_permissions` for `SUPERVISOR`: `NONE` on every module except `selfservice = WRITE` (Supervisors operate out of the self-service portal, like `USER`; row-level "own team only" scoping is enforced in application SQL, not by this module-level grant).

## Backend

**New service class `src/org/tsicoop/compass/service/v1/Supervisor.java`**, routed via a new line in `web/WEB-INF/_processor.tsi` (`/api/v1/supervisor=org.tsicoop.compass.service.v1.Supervisor`), called by the frontend as `/api/v1/admin/supervisor` (matches the existing `/api/v1/admin/<service>` convention parsed in `InterceptingFilter.java:247-274`).

Why a new class instead of extending `SelfService.java` or `Platform.java`: `SelfService.java`'s documented invariant (`SelfService.java:19-21`) is that every func is scoped to the caller's *own* identity (`created_by/user_id = self`). Supervisor funcs are one-to-many (`supervisor_id = self`) — mixing the two risks breaking that invariant. `Platform.java` funcs are full-table Admin-scale queries with no row scoping — copy-pasting one risks leaking other Supervisors' teams. A dedicated class keeps the row-scoping code auditable in one place.

Because `role_permissions` only gates at module granularity and `USER` also holds `WRITE` on `selfservice`, `Supervisor.post()` must explicitly check `InputProcessor.getRole(req) == "SUPERVISOR"` (or `ADMIN`) and 403 otherwise, before dispatching `_func`. Add `SERVICE_MODULE_MAP.put("supervisor", "selfservice")` in `InterceptingFilter.java:57-69`.

`_func`s, each scoped by `supervisor_id = selfId` (`selfId = InputProcessor.getAuthenticatedUserId(req)`), following the paging/JSON conventions already used in `SelfService.java` and `Platform.java`:
- `list_my_team` — list Users where `supervisor_id = self`.
- `provision_team_user` — insert a `USER` with `role` and `supervisor_id` forced server-side (never trust the request body), same validation as `Platform.provisionUser` (email/username/password required, unique-email 409).
- `update_team_user` / `set_team_user_status` — `UPDATE ... WHERE id = ? AND supervisor_id = ?`; 0 rows affected ⇒ 404 (don't leak existence of users outside the team). Role/supervisor reassignment stays Admin-only via `Platform.updateUser`.
- `list_pending_tickets` / `list_pending_changes` — join to `users` on `created_by`/`requester_id`, filter `supervisor_id = self AND approval_status = 'PENDING'`.
- `approve_ticket` / `reject_ticket`, `approve_change` / `reject_change` — `UPDATE ... WHERE id = ? AND approval_status = 'PENDING'` joined through the owning user's `supervisor_id = self`; 0 rows ⇒ 404/409. On approve, fire the same IT-facing `Notification.emit(...)` broadcast the creation path used to call (this is the moment the item becomes visible to IT/GRC). On reject, `Notification.emitToUser(...)` back to the requester only, with the reason.

**`InterceptingFilter.java`**: remove `"register"` from `ADMIN_NOAUTH_FUNCS` (line 48-54); add the `supervisor` module map entry above.

**`User.java`**: delete the `register` case and method (lines ~113-170) entirely — self-registration is removed, not just gated. `login()` untouched.

**`Platform.java`**:
- Add `"SUPERVISOR"` to the `ROLES` array (~line 575).
- `provisionUser()` (line 226) / `updateUser()` (line 296): accept optional `supervisor_id`. When `role='USER'` and `supervisor_id` present, validate the referenced user has `role='SUPERVISOR'` (400 if not). For non-`USER` roles, ignore any supplied `supervisor_id`. In `updateUser`, support clearing the assignment via an explicit empty string (small deviation from pure-COALESCE, needed for reassignment/offboarding).
- New funcs (naturally Admin-only since they live behind the existing `platform` module ADMIN-only permission check): `list_supervisors` (active `SUPERVISOR`s, for the "Reports to" picker), `get_business_settings` / `save_business_settings` (read/upsert `business_settings`, mirroring the transactional structure of `saveRolePermissions`).

**`SelfService.java`** (`createTicket` line 76, `createChangeRequest` line 170): before insert, check `business_settings.require_supervisor_approval`.
- If `false`: exact current behavior, unchanged.
- If `true`: look up the caller's `supervisor_id`. If `null`, return 400 "Your account has no assigned supervisor. Contact your administrator." — never falls through to `NOT_REQUIRED`. Otherwise insert with `approval_status='PENDING'` explicitly, and call `Notification.emitToUser(...)` to the supervisor **instead of** the existing `Notification.emit(...)` broadcast, so it doesn't reach IT/GRC while pending.
- `listMyTickets`/`listMyChanges`: add `approval_status` to the selected/returned columns so the submitting `USER` can see a Pending/Rejected badge.

**`Operations.java`**: `listTickets`/`listChanges` (lines 469, 99) need the caller's role passed in (`InputProcessor.getRole(req)`, from the two call sites in `post()`); when role is `IT_STAFF` or `GRC_OFFICER`, append `AND approval_status != 'PENDING'` to both the count and data queries — `ADMIN` continues to see everything. This makes pending items fully absent, not present-but-locked.
Also fix `listStaff()` (line 706): currently `WHERE status='ACTIVE' AND role != 'USER'`, which would incorrectly start including `SUPERVISOR` accounts as assignable ticket staff once the role exists. Change to an explicit allow-list: `role IN ('IT_STAFF','GRC_OFFICER','ADMIN')`.
Recommended defense-in-depth: also exclude `approval_status='PENDING'` rows from `updateTicket`/`updateChange`/`deleteTicket`/`deleteChange`'s target row, so IT can't act on a pending item out-of-band even with a stale UUID.

## Notifications

No changes to `Notification.java` — reuse its existing three static methods (`emit`, `emitToRole`, `emitToUser`) as described above: pending → `emitToUser` to the supervisor only; approved → the normal `emit` broadcast (first time IT/GRC learns the item exists); rejected → `emitToUser` back to the requester with the reason.

## Frontend

- **`web/index.html`** (login): remove the "Employee? Request access" self-registration link; update the post-login redirect so `role === 'USER' || role === 'SUPERVISOR'` both land on `web/selfservice/index.html`.
- **`web/selfservice/register.html`**: delete (no longer linked, feature removed).
- **`web/selfservice/index.html`**: add "My Team" (`team.html`) and "Approvals" (`approvals.html`) nav entries, shown only when the stored role is `SUPERVISOR`.
- **New `web/selfservice/team.html`**: Supervisor-only (client-side role gate + redirect, same style as existing admin-only gates), list/provision/manage own team via the new `supervisor` funcs — structurally modeled on `platform-ticket-categories.html`'s list+modal pattern.
- **New `web/selfservice/approvals.html`**: Supervisor-only, two sections (pending tickets / pending change requests) with Approve and Reject (with reason) actions.
- **`web/selfservice/new-ticket.html` / `new-change.html`**: no structural change — the existing error-display path already surfaces the new 400 "no supervisor assigned" message from the server.
- **`web/console/platform-users.html`**: add `SUPERVISOR` to role dropdowns; add `USER` to the *provision* dropdown (previously only reachable via public self-registration, now Admin must be able to create it directly); add a conditional "Reports to (Supervisor)" picker (populated via `list_supervisors`) shown when role is `USER`.
- **New `web/console/platform-business-settings.html`**: clone the `platform-ticket-categories.html` shell, single toggle bound to `require_supervisor_approval` via `get_business_settings`/`save_business_settings`. Add nav entry in `platform.html` and a `PAGE_MODULE` entry in `rbac.js`.

## Rollout / compatibility

Default-off is airtight: the setting seeds to `'false'`, `approval_status` defaults to `'NOT_REQUIRED'`, and the IT-queue filter only excludes `'PENDING'` rows (which can't exist while the setting is off) — every existing deployment behaves identically until an Admin flips the toggle. No forced data migration; any pre-existing `status='PENDING'` self-registered users from the old flow remain manageable via the unmodified `platform-users.html` → `set_user_status` path. Document as an operational pre-flight for whoever enables the toggle: create at least one `SUPERVISOR`, assign existing `USER`s to one, *then* flip it on — otherwise those Users get blocked from submitting anything.

## Verification

- Run the app locally (`docker-compose up`, apply `07_supervisor_role.sql`), confirm existing ticket/change-request flows are unchanged with the setting left at its default `false`.
- As Admin: provision a `SUPERVISOR`, provision/assign a `USER` to them (via `platform-users.html`), enable the business setting.
- As the `USER`: submit a ticket and a change request; confirm they land as `PENDING`, are invisible in `IT_STAFF`/`GRC_OFFICER` console queues, and the Supervisor receives a notification (IT does not).
- As the `SUPERVISOR`: see the item in `approvals.html`, approve one and reject the other; confirm the approved one now appears in IT's queue and the requester sees the rejection with reason.
- As the `SUPERVISOR`: provision a new team member via `team.html`, confirm they can log in and only that Supervisor sees them in `list_my_team`.
- Confirm `register.html` is gone / unreachable and unauthenticated `_func=register` calls now 401.
- Confirm a `USER` with no Supervisor is blocked with a clear error when the setting is on.
