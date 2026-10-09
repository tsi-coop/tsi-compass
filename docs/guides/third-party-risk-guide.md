# Third-Party Risk Guide

For GRC Officers and Admins (menu: Risk and Vulnerability, Third-Party Risk). `IT_STAFF` cannot see risk tiers, assessments or findings; they use the plain vendor list under IT Operations.

## The page
Five tabs: **Overview**, **Vendors**, **Assessments**, **Findings**, **Questionnaires**. Opening a vendor shows a drawer with Profile, Assessments, Findings, Contracts and documents, Portal users, Linked data and Lifecycle.

## 1. Add and tier a vendor
Add a vendor and fill in its profile: type, business criticality, business owner, and four yes/no facts (processes personal data, stores data outside India, has network access, depends on a fourth party). Compass computes an **inherent risk tier** (Low, Medium, High, Critical) from these facts and from the data you have linked to the vendor, and shows the contributing factors. The tier sets how often it must be reviewed (6, 12, 24 or 36 months).

**Linked data** lists Data Register entries where the vendor is the linked vendor or a recipient (matched by link or by name). If the vendor receives personal data, expect a gap until a DPA is on file (DPDP Sec 8(2)).

## 2. Assess
1. In the vendor's **Assessments** tab, click **Start assessment**. Choose a questionnaire and a type (Onboarding, Periodic, Event-driven). If one is already open, Compass takes you to it: a vendor can have only one open assessment.
2. Answer each question (Yes, No or N/A, with a comment). Either fill it in yourself from documents the vendor emailed, or **Send to vendor portal** so the vendor answers it online (see the [Vendor Portal Guide](vendor-portal-guide.md)).
3. **Complete assessment**: all scored questions must be answered. Compass calculates a score (N/A questions are excluded), sets residual risk, and you record a conclusion (Approved, Approved with conditions, Rejected) and summary. The next review date is set from the tier. A No on a critical question raises a finding.
4. A completed assessment is read-only; reassessments create new records so history is kept.

## 3. Findings
Track issues with an owner, severity, due date and status (Open, In progress, Remediated, Closed). **Accept** a finding only with a reason; it records who accepted it. **Escalate to the Risk Register** to create a linked risk.

## 4. Contracts and documents
Record contracts (MSA, SOW, DPA, NDA, SLA) with dates, notice period and four clause checks (security, breach notification, audit right, data return or deletion). Upload certificates and reports with a validity date so expiring evidence shows up.

## 5. Gaps
Compass lists computed gaps automatically: personal-data vendor with no DPA, missing clauses, review overdue or never done, expiring evidence, overdue high findings, and ended vendors still receiving data.

## 6. Lifecycle and archiving
Move a vendor through Prospect, Onboarding, Active, Offboarding, Terminated. Moving to Offboarding shows a checklist (revoke access, return or delete data, retrieve assets, close findings). Deleting a vendor **archives** it: history is kept, it is hidden from lists, and it can be restored.

## 7. Questionnaires
Admins manage templates. 42 questions are seeded across governance, access control, data protection, personal data handling, incident management, continuity, secure development, physical and HR security, and certifications; have your own counsel review them. A printable Word version of the same questionnaire, for vendors you assess outside the portal, is at [`docs/architecture/third-party-risk-assessment.docx`](../architecture/third-party-risk-assessment.docx). A questionnaire that has been used is locked; clone it to change it.

## Notes
Reminders (review due, documents expiring, findings overdue, contract renewals) are raised when you open Third-Party Risk or Risk, at most once a week per item, because Compass has no background scheduler.
