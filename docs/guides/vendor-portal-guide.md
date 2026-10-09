# Vendor Portal Guide

The vendor portal lets people at your vendors answer the security questionnaire you send them, attach evidence and submit it. They see nothing else in Compass.

- URL: `/vendorportal/` (sign in at the normal page; vendors are redirected).
- Off by default. Enable with `TSI_VENDOR_PORTAL_ENABLED=true` and restart. When off, vendor logins are refused and the portal pages return 404.
- Compass sends no email. Tell vendors their address, temporary password and URL yourself.

## How it works
1. **Provision.** In Third-Party Risk, open the vendor, **Portal users**, **+ Add portal user** (name and email). A temporary password is shown once.
2. **Send.** On an assessment click **Send to vendor portal** and set a due date. It needs at least one active portal user.
3. **Respond.** The vendor signs in, opens **My assessments**, answers each question (Yes, No, N/A, comment), attaches evidence, and saves progress at any time.
4. **Submit.** Every scored question must be answered; the vendor ticks a declaration. The form locks.
5. **Review.** The assessor sees vendor-provided answers and evidence, may correct an answer (the vendor's original is kept), or **returns** chosen questions with a note. Only returned questions reopen for the vendor.
6. **Complete.** Scoring, findings and the conclusion are recorded as usual. The vendor sees only a status (Sent, In progress, Submitted, Returned, Completed), never scores, findings or ratings.

You can **withdraw** a request at any time (hidden from the vendor again) and send it later.

## Evidence rules
PDF, DOCX, XLSX, PNG and JPG only; the file's contents must match its extension; 10 MB per file, 25 files and 50 MB per assessment. Vendors can remove only their own files and only before submitting. GRC downloads files through the console.

## Security
A vendor user belongs to one vendor and can reach only that vendor's requests. A `VENDOR` token can call only the vendor-portal API. Disabling a portal user, or archiving the vendor, cuts access on the next request. Failed sign-ins are rate-limited. Everything is audited.

## Testing it end to end
1. Enable the portal and restart:
   ```bash
   echo "TSI_VENDOR_PORTAL_ENABLED=true" >> .env
   docker compose up -d
   ```
   A database created before the portal existed also needs the migration once:
   ```bash
   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass -v ON_ERROR_STOP=1 < db/22_vendor_portal.sql
   ```
2. **Prepare (console, Admin or GRC Officer).** Risk and Vulnerability, Third-Party Risk: add a vendor, add a portal user (copy the password), start an assessment, **Send to vendor portal**.
3. **Sign in as the vendor in a private window.** Tokens are stored per browser, so an incognito window keeps your console session. Open `http://localhost:8085/`, sign in with the portal user, answer questions, attach evidence, sign out and back in to confirm progress is kept, then **Submit**.
4. **Review (console).** Refresh the assessment: the portal badge shows *Submitted*. Tick **Return to vendor** on one question with a note, return it, and confirm only that question reopens in the vendor window. Answer and resubmit, then **Complete assessment**.
5. **Try these.** A second vendor must not see the first vendor's request. A vendor opening `/console/dashboard.html` is sent back to the portal. Withdraw while the vendor holds it. Disable the portal user and watch the session stop. Switch the portal off and confirm `/vendorportal/` returns 404.
