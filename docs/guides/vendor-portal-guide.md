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

**Short answer: yes, you need a separate login.** The portal only accepts `VENDOR` users. Your Admin or GRC Officer account cannot sign in to it (portal pages are refused to other roles), and a vendor account cannot sign in to the console. So a test uses two accounts at the same time:

| Login | Who | Where | Created by |
|---|---|---|---|
| Your normal Admin or GRC Officer account | You, as the assessor | `http://localhost:8085/console/` | Already exists |
| A **portal user** for a test vendor | You, pretending to be the vendor | `http://localhost:8085/vendorportal/` (you are sent there after signing in) | You, in step 2 below |

You make the portal user yourself from the console; it takes about a minute and needs no email (use any address, for example `test.vendor@example.com`).

Keep the two logins in **separate browser windows**. Sign-in tokens are stored per browser, so use a private/incognito window (or a different browser) for the vendor, otherwise signing in as the vendor replaces your console session.

### Step 1. Turn the portal on
```bash
echo "TSI_VENDOR_PORTAL_ENABLED=true" >> .env
docker compose up -d
```
A database created before the portal existed also needs its migration once (a fresh install already has it):
```bash
docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass -v ON_ERROR_STOP=1 < db/22_vendor_portal.sql
```

### Step 2. In the console (normal window): create a test vendor, a portal user and a request
1. Sign in as Admin or GRC Officer. Go to **Risk and Vulnerability**, then **Third-Party Risk**, then the **Vendors** tab. Add a vendor (any name, for example "Test Vendor"), or open an existing one.
2. In the vendor's drawer open **Portal users**, click **+ Add portal user**, enter a name and an email, and click **Create account**.
3. A dialog shows the sign-in email and a **temporary password, once**. Copy both.
4. Open the vendor's **Assessments** tab, click **Start assessment**, pick the questionnaire and start.
5. In the assessment, click **Send to vendor portal** and choose a due date.

### Step 3. In the private window: act as the vendor
1. Open `http://localhost:8085/`. Sign in with the **portal user's email and temporary password** (not your admin login). You land in the vendor portal.
2. **My assessments** shows the request. Open it, answer the questions, attach evidence (optional), sign out and back in to confirm progress is kept, then **Submit**.

### Step 4. Back in the console window: review
1. Reopen the assessment. The portal badge shows *Submitted*.
2. Tick **Return to vendor** on one question with a note and click **Return ticked questions to vendor**. In the vendor window, refresh: only that question reopens. Answer and resubmit.
3. Click **Complete assessment**.

### Things to try
- Add a second vendor with its own portal user: it must not see the first vendor's request.
- Signed in as the vendor, open `http://localhost:8085/console/dashboard.html`: you are sent back to the portal.
- **Withdraw** a request while the vendor holds it.
- Disable the portal user (Portal users tab); the vendor session stops on its next action.
- Set `TSI_VENDOR_PORTAL_ENABLED=false`, restart, and `/vendorportal/` returns 404.
