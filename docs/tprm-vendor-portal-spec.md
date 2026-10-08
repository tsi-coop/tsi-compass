# Vendor Portal (Third-Party Risk Management)

Status: **implemented and verified** (`db/22_vendor_portal.sql`, `VendorPortal.java`, `web/vendorportal/`, GRC-side functions in `Tprm.java`). Extends `tprm-plan.md`. The open points below were settled with the recommended answers: one `VENDOR` role with many users per vendor; shipped disabled by default; uploads without antivirus but with an allowlist, magic-byte check and a visible warning label; vendors never see outcomes; one return round per question.

## As built
Where the implementation differs from or settles points in the design below:
- **Account provisioning:** GRC creates the account on the vendor's **Portal users** tab. Compass generates a 16-character temporary password and shows it once (reset works the same way). The existing recovery-passphrase flow is ADMIN-only and was not reused; resetting the password is the recovery path. `provision_user` and `update_user` in Platform refuse the `VENDOR` role, so portal accounts can only be made from Third-Party Risk.
- **Sending:** an assessment can be sent while it is Draft, Sent or In review and not already with the vendor (so a withdrawn one can be sent again). Sending needs at least one active portal user and the portal switched on.
- **While the vendor holds it** (`SENT`, `IN_PROGRESS`, `RETURNED`) the assessor cannot edit answers, mark it sent or complete it; withdraw it first. Cancelling it also hides it from the vendor. After submission the assessor may correct an answer; the vendor's original answer and comment are kept (`original_answer`, `original_comment`) and the vendor still sees their own words, never the correction.
- **Submission** requires every scored question answered (written answers are optional) and the declaration. After a return, only the returned questions are editable and each must be answered again (clicking the already-selected answer confirms it) before resubmitting.
- **Evidence:** PDF, DOCX, XLSX, PNG and JPG only; the file's leading bytes must match its extension; 10 MB per file, 25 files and 50 MB per assessment; stored under a generated name; the vendor's file name is sanitised and shown only as a label. GRC downloads through the authenticated API. A vendor can download and remove only files uploaded by their own account, and only before submission.
- **Disabled accounts** stop working on the next request (the account is checked on every call), not when the session expires. Archiving a vendor blocks its portal users the same way.
- **Rate limiting** is wired into `User.login` for every role, keyed on client address plus email (5 failed attempts in 15 minutes) so one noisy address cannot lock everyone out when many users share a proxy.
- **Default-deny:** a `VENDOR` token may call only `/api/v1/vendorportal`; any other token is refused by it. `ConsoleAuthFilter` also gates the pages by role. A vendor who opens a console or self-service page is sent to the portal.
- **Audit:** answer saves, evidence uploads and removals, submission, send, withdraw, return and account changes are all logged. Passwords are never logged.
- **Not built:** vendor-facing notifications (no outbound email; GRC tells vendors out of band), antivirus scanning.

## Idea in one paragraph
The vendor portal is the **Self-Service Portal pattern, applied to vendors.** Employees (role `USER`) log in to `/selfservice/` to raise tickets and attest policies; vendor contacts (new role `VENDOR`) log in to `/vendorportal/` to answer security questionnaires and upload evidence. Same login page, same JWT/cookie session, same role-based permissions, same user administration, same attachment handling, same audit trail. GRC creates the vendor user accounts, the same way IT creates employee accounts today. Nothing new to learn and no new authentication mechanism.

This replaces the earlier link-plus-access-code design, which needed its own token, lockout and session machinery.

## Scope
**In v1**
- GRC creates a login for a vendor contact, linked to a vendor.
- Vendor contact logs in, sees the assessments GRC has sent to their company, answers questions, attaches evidence, saves progress, submits.
- Assessor reviews, can return questions for clarification, completes the assessment per the TPRM plan.

**Out of v1** (keep it simple)
- Vendors seeing scores, findings, ratings, tiers, contracts or anything about other vendors.
- Vendor-initiated requests, vendor self-registration, SSO.
- Vendor-side remediation tracking.
- Automated email (Compass has no working outbound email today; GRC shares credentials/links manually, as IT does for new employees).

## Users and access
| Role | Where | Can do |
|---|---|---|
| `VENDOR` (new) | `/vendorportal/` | See and answer assessment requests for **their own vendor** only |
| `GRC_OFFICER` / `ADMIN` | Third-Party Risk console | Create/disable vendor users, send requests, review, return, complete |
| `IT_STAFF`, `USER`, `SUPERVISOR` | n/a | No access (unchanged) |

- A vendor can have several contact users. A vendor user belongs to exactly one vendor (`users.vendor_id`).
- A `VENDOR` user cannot reach the console or the employee self-service portal, and employees cannot reach the vendor portal.

## Flow
1. **Provision** (GRC): on a vendor's TPRM page, **Add portal user** (name, email). Compass creates the account (role `VENDOR`, linked vendor) and shows the temporary credentials / recovery passphrase once, as the existing user creation flow does. GRC shares them out of band.
2. **Send request** (GRC): on a `DRAFT` assessment click **Send to vendor**. It becomes visible to that vendor's users, with a due date.
3. **Respond** (vendor): login -> "My assessments" -> open one -> answer each question (Yes / No / N/A, comment), attach evidence per question, save progress at any time.
4. **Submit** (vendor): a review screen lists unanswered questions and a declaration ("answers are accurate to the best of my knowledge"). On submit the form locks and the assessor is notified (existing in-app notification).
5. **Review** (assessor): vendor answers are tagged "Vendor-provided". The assessor can edit an answer (original kept) or **return** specific questions with a note; the vendor sees only those reopened.
6. **Complete** (assessor): scoring, findings, conclusion, next review date as in the TPRM plan. The vendor sees only the status (Submitted / Returned / Completed), never the outcome.

Assessment request states (column on the assessment): `NOT_SENT` -> `SENT` -> `IN_PROGRESS` -> `SUBMITTED` <-> `RETURNED` -> `COMPLETED`. GRC can also **Withdraw** a request (back to `NOT_SENT`, hidden from the vendor).

## Vendor-facing pages (`/vendorportal/`)
Styled like `/selfservice/`, sharing its look, branding (`BRAND_NAME`) and top bar pattern.
1. **My assessments** (`index.html`): list of requests with vendor name, type, due date, status.
2. **Questionnaire** (`assessment.html`): sections, progress bar, per-question guidance, answer, comment, attach evidence, "returned" notes. Autosave.
3. **Evidence** (within the questionnaire page): uploaded files with type, size and optional "valid until" date.
4. **Submit** (modal on the questionnaire page).

Nothing else. No tickets, policies, training or dashboards.

## Data model additions (on top of `tprm-plan.md`)
- `users.vendor_id UUID NULL -> vendors(id)`; role constraint widened to include `'VENDOR'`; application code requires `vendor_id` when role is `VENDOR` (same approach as supervisor linkage in `07_supervisor_role.sql`). Disabling a user uses the existing `status`.
- `tprm_assessments` gains: `portal_status` (as above), `sent_at`, `sent_by`, `portal_due_date`, `submitted_at`, `submitted_by -> users`, `declaration_accepted BOOLEAN`.
- `tprm_answers` gains: `source` (`ASSESSOR` / `VENDOR`), `original_answer`, `original_comment` (set when an assessor overrides a vendor answer), `returned BOOLEAN`, `return_note`.
- `tprm_documents` gains: `uploaded_by_vendor BOOLEAN` and an optional `question_id`. Same storage shape and SHA-256 checksum as ticket attachments.
- `role_permissions`: new module `vendorportal` with `VENDOR: WRITE`, `ADMIN: ADMIN`, everyone else `NONE`. `VENDOR` is `NONE` for every existing module.
- No new tables for sessions, tokens or lockout.

## API
- New service `VendorPortal.java` at `/api/v1/vendorportal`, modelled on `SelfService.java`. Like it, **every function resolves identity and vendor from the authenticated user** (`InputProcessor.getAuthenticatedUserId`, then `users.vendor_id`); no vendor, assessment-owner or user IDs are trusted from the request body.
- Functions: `list_my_assessments`, `get_assessment` (questions + this vendor's answers), `save_answer`, `upload_evidence`, `list_evidence`, `get_evidence`, `delete_evidence` (before submission only), `submit_assessment`.
- Every function checks that the assessment belongs to the caller's vendor and is in a state that allows the action (e.g. no edits after submit except returned questions); otherwise 404.
- Registered in `_processor.tsi` and `SERVICE_MODULE_MAP` (`"vendorportal" -> "vendorportal"`).
- GRC-side additions in `Tprm.java` (existing `risks` permission): `create_vendor_user`, `list_vendor_users`, `disable_vendor_user`, `reset_vendor_user_password`, `send_to_vendor`, `withdraw_from_vendor`, `return_questions`.
- Portal responses use a dedicated response shape: question text, guidance, and the vendor's own answers only. Weights, `is_critical`, `maps_to`, scores, findings, risk ratings and assessor notes are never included.

## Security (small, but needed)
Because it reuses the self-service login, most protection comes for free. What is specific to vendors:

1. **Default-deny for the `VENDOR` role** in `InterceptingFilter`: a `VENDOR` token may call only the `vendorportal` service (plus login/logout). Today services missing from `SERVICE_MODULE_MAP` skip the module check, so this explicit rule is needed rather than relying on `NONE` rows. Same check protects against future services.
2. **Role-aware page gating** in `ConsoleAuthFilter`: `/vendorportal/*` requires role `VENDOR`; `/console/*` and `/selfservice/*` reject `VENDOR`. (The filter currently checks only that the session is valid.)
3. **Login redirect:** `web/index.html` sends `VENDOR` to `/vendorportal/index.html`, as it already does for `USER` / `SUPERVISOR`.
4. **Login rate limiting:** `LoginRateLimiter` exists but is not wired into `User.login` today. Wire it in; this benefits all roles and matters more once vendors outside the organisation can sign in.
5. **Uploads from outsiders:** the existing ticket attachment code is used by trusted employees. For vendors add: an extension and magic-byte allowlist (PDF, DOCX, XLSX, PNG, JPG), 10 MB per file, a cap per assessment, stored under a server-generated name, served only to GRC through the authenticated API with `Content-Disposition: attachment` and `nosniff`. No antivirus in v1; GRC sees a "vendor-uploaded, not virus scanned" label.
6. **Text from outsiders** is length-limited server-side and escaped when rendered in the GRC console.
7. **Exposure control:** `TSI_VENDOR_PORTAL_ENABLED` (default `false`). When false, `VENDOR` logins are refused and `/vendorportal/*` returns 404. The README notes that only `/vendorportal/`, `/index.html` and `/api/` need to be reachable from outside; the console can stay internal.
8. **Privacy:** vendor users are personal data (name, email). The login page / portal footer gets a one-line notice. Disabling a user keeps their name on submitted assessments for the audit trail.
9. All vendor actions (login, save, upload, submit) go through the existing audit trail like other user actions.

## GRC console additions (Third-Party Risk page)
- Vendor profile -> **Portal users** tab: add, disable, reset password.
- Assessment -> **Send to vendor / Withdraw**, request status chip, due date.
- Review screen: "Vendor-provided" badges, original vs overridden answer, **Return to vendor** with per-question notes, evidence panel with the not-scanned label.
- Hub tiles: "Awaiting vendor response", "Submitted, ready for review".

## Effort
**About 4-6 working days** on top of the internal TPRM module: provisioning and API (about 2 days, mostly copied from `SelfService` and user admin), vendor pages (about 1.5 days), role gating, default-deny and rate limiting (about 1 day), upload hardening and tests (about 1 day). Much smaller than the link-plus-code design because authentication, sessions and user admin already exist.

## Trade-offs accepted
- GRC has to create and disable vendor accounts. Acceptable for a beginner-scale deployment with a limited number of vendors.
- A vendor contact's access spans all of their vendor's open requests, not one questionnaire. Acceptable since the account belongs to that vendor.
- Credential sharing is manual (no email). Same as employee accounts today.

## Open points to confirm
1. **Role name and scope:** one `VENDOR` role, many users per vendor. OK?
2. **Password handling:** reuse the existing temporary-password / 5-word recovery passphrase flow for vendor users as-is?
3. **Uploads without antivirus:** acceptable for v1 with the allowlist and warning label?
4. **Exposure default:** ship the portal disabled by default (recommended)?
5. **Outcome visibility:** vendors never see scores or conclusions; GRC tells them offline. Confirm.
6. **One return round** per question (no threaded comments). Confirm.

## Verification
1. Create a vendor, add two portal users. Each logs in and sees only that vendor's sent assessments; a second vendor's user sees nothing of the first.
2. Walk the flow: send -> save partial -> log out/in (progress kept) -> submit (locked) -> return one question -> only it reopens -> resubmit -> complete. Statuses and audit entries correct throughout.
3. Role isolation: a `VENDOR` token is refused by every admin and self-service API function and by `/console/*` and `/selfservice/*` pages; `USER`, `SUPERVISOR`, `IT_STAFF` tokens are refused by `/vendorportal/*` and its API. GRC/ADMIN console still works.
4. Tampering: change assessment, answer and document IDs in requests to another vendor's records -> 404, no data returned.
5. Data exposure: inspect raw portal responses; no weights, `is_critical`, scores, findings, ratings or assessor notes.
6. Uploads: wrong magic bytes, double extension, oversize, HTML/SVG/EXE/ZIP all rejected; accepted files download for GRC only, as attachments.
7. Text: script payloads in answers/comments/filenames are inert in the GRC console; oversized fields rejected.
8. Login: repeated wrong passwords are rate-limited for all roles; disabled vendor user cannot log in and existing session stops working after logout/revocation.
9. Switch: `TSI_VENDOR_PORTAL_ENABLED=false` refuses vendor logins and 404s `/vendorportal/*`.
10. RBAC: GRC_OFFICER and ADMIN manage portal users and requests; IT_STAFF gets 403.
