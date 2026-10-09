# Data Governance Guide

For GRC Officers and Admins. `IT_STAFF` have no access to this module. The module is built around India's DPDP Act. A worked scenario (Varam, a fictional microlender) is in [`examples/datagov`](../../examples/datagov/).

The pages, in the order you would normally use them:

1. **Data Principals**: who your data is about.
2. **Data Register**: what data you hold (start here, or with Discovery).
3. **Data Flows**: how it moves.
4. **RoPA Export**: the Record of Processing Activities.
5. **DPIA**: impact assessments derived from the above.

Discovery is opened from the Data Register through its **Discover** button.

## 1. Data Principals
The categories of people your data is about. **Customer** and **Employee** are seeded. Add your own (Patient, Student, Subscriber). Tag each Register entry with the principals it concerns.

## 2. Data Register
A self-declared inventory. For each entry record:
- **What and where:** name, system type (database, application, file share, SaaS, email, physical), location, volume estimate, owner.
- **How sensitive:** category (PII, financial, health, employee, IP, public, other) and sensitivity (public, internal, confidential, restricted).
- **Why and how long:** purpose, processing basis (consent, legitimate use, contractual, legal obligation, other), retention period, deletion trigger.
- **Role:** controller, processor or joint.
- **Risk signals:** large scale, involves profiling.
- **Who it is shared with** (recipients, optionally linked to a vendor), **who can access it**, and the **framework requirements** it is mapped to.
- **Review status:** Discovered (nobody has classified it), Classified, Reviewed.

The **Gap Check** flags an entry when it is not reviewed, has a personal or sensitive category but no processing basis, has neither retention nor deletion trigger, or has no mapped requirement. Import many entries from CSV.

One purpose per entry: if an activity has several purposes with different bases, enter one entry per purpose.

## 3. Data Flows
Record a movement between two registered entries, or between an entry and a free-text endpoint you have not registered (a personal WhatsApp, a personal email account). Capture the channel, whether it is an approved channel, the data elements, frequency, and whether it is **cross-border** with the destination country. Unapproved channels and cross-border flows are what the RoPA and DPIA pick up.

## 4. RoPA Export
Joins purpose, basis, retention, principals, recipients and flows into a Record of Processing Activities (DPDP and GDPR Art. 30 shape). Filter by framework or by data principal and export CSV.

## 5. DPIA
One DPIA per register entry, derived automatically.
1. Click **Auto-generate from RoPA**. Compass scores each entry (sensitive data 4, profiling 3, financial 2, cross-border 2, third-party sharing 1, large scale 1; 4 or more is High, 3 is Medium, 2 or less is Low) and drafts necessity, proportionality and mitigation text.
2. Edit the notes and residual risk while it is a Draft or Under review. Re-generating refreshes only the derived fields of drafts you have not edited.
3. Move it **Draft, Under review, Approved, Submitted**. Approval records who approved and when; submission records an optional board reference. Approved and Submitted DPIAs are locked.
4. If the register or flows change after review, an Under review or later DPIA is marked **stale** so you can re-review it.
5. **Clear auto-generated** removes drafts only. Only an Admin can delete a DPIA.
Any GRC Officer can approve, including the person who edited the DPIA; separate duties by assigning who approves.

## 6. Discovery
Find columns that look like personal data in a database, then turn them into Register entries.

1. On the Data Register click **Discover**, then **Import schema**. Pick your database type and copy the one query shown; run it on your own database. It returns table, column and data type only.
2. Enter a source name (for example your core database), optionally a location, upload the CSV and click **Scan**. Up to 50,000 columns per import.
3. Compass matches **column names only** against India-focused rules (Aadhaar, PAN, voter ID, passport and licence, bank account and IFSC, GSTIN, UPI, mobile, email, date of birth, name, address, health, biometric). It never connects to your database and never sees values.
4. Review findings grouped by table. Confidence is at most Medium for a name match, and Low for generic names such as `first_name` or `address`. Expect some false positives. Boolean flags such as `has_aadhaar` and a bare `name` column are not flagged.
5. **Accept** a table to create one Register entry (status Discovered, tagged "From discovery") or link to an existing one; **Reject** to dismiss. Re-importing never changes accepted or rejected findings. You can reopen a rejected one.
6. Classify and review the new entry as usual. Admins can disable individual detection rules under Rules.

## Demo with the Varam scenario
See [`examples/datagov/README.md`](../../examples/datagov/README.md). In short: import `varam-lending-schema.csv` and `varam-hrms-schema.csv` in Discovery, accept all seven tables, then either classify them by hand or run `seed-varam-after-discovery.sql` to fill in purposes, flows and DPIA signals, and show Data Flows, RoPA and DPIA.

## Access control
Admin: full. GRC Officer: read and write. IT Staff: none. Deleting records is Admin-only. All actions are audited.
