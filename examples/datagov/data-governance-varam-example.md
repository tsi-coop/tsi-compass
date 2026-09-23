# Data Governance Test Data: DPDP RoPA Worked Examples

Two test-data sets, built to reproduce real DPDP-style RoPA requirements documents from Varam (a fictional microlending company) through the Data Governance module: a borrower consent policy and an employee data policy. Useful both as test fixtures and as a worked example of where the current schema does and doesn't reach full RoPA fidelity.

## The core mismatch, and how it's resolved here

Both source documents describe **one processing activity** (e.g. `varam_borrower_v1`) broken into several purposes, each with its own legal basis, retention period, and processor; the borrower activity even states `Legal Basis: MIXED` outright. TSI Compass puts purpose, processing basis, and retention on the *data asset*, one value each; it has no way to hold "MIXED" in a single row.

The only faithful reproduction is **one data asset per purpose**, all describing slices of the same underlying data platform. That reproduces every value in each source document correctly, but it means one activity in the source becomes several rows in the RoPA export, not one. See "Known limitations" at the end.

---

## Example 1: Borrower consent (`varam_borrower_v1`)

Sector: Finance / Microlending. Covers KYC, credit assessment, loan servicing, and promotional communications.

### Data Principal

`Customer`, already seeded by default, matches the source document's `data_subject_categories: customer` exactly. No new principal needs to be defined.

### Data Register: 4 assets

All four: System Type `DATABASE`, Controller Role `CONTROLLER` (Varam is the Data Fiduciary for all of them; AWS/CIBIL/CERSAI/Twilio are its Data Processors), Data Principal `Customer`.

| Name | Category | Sensitivity | Purpose | Processing Basis | Retention |
|---|---|---|---|---|---|
| Varam Borrower KYC & Identity Verification | PII | RESTRICTED | KYC and identity verification under PMLA and the DPDP Act | LEGAL_OBLIGATION | 5 years from cessation of relationship |
| Varam Borrower Credit Assessment & Scoring | FINANCIAL | CONFIDENTIAL | Credit assessment and scoring for loan eligibility | CONTRACTUAL | 2 months from collection |
| Varam Borrower Loan Servicing & Collections | FINANCIAL | CONFIDENTIAL | Loan disbursement and collections servicing | CONTRACTUAL | 7 years from cessation of relationship |
| Varam Borrower Marketing & Promotional Offers | PII | INTERNAL | Promotional offers and new-product communications | CONSENT | 2 years from collection |

**Source mapping for legal basis:** the document's "Statutory Obligation (PMLA & DPDP Act)" maps to `LEGAL_OBLIGATION`; "Contractual Necessity" maps to `CONTRACTUAL`; "Consent" maps to `CONSENT` directly.

### Recipients (Shared With)

Matches the source document's Purpose-Processor Mapping table, one recipient per asset:

| On asset | Recipient | Type | Purpose of sharing |
|---|---|---|---|
| Varam Borrower KYC & Identity Verification | CERSAI | REGULATOR | Central registry verification |
| Varam Borrower Credit Assessment & Scoring | CIBIL | VENDOR | Credit bureau enquiry |
| Varam Borrower Loan Servicing & Collections | AWS | VENDOR | Cloud hosting and storage |
| Varam Borrower Marketing & Promotional Offers | Twilio | VENDOR | OTP and SMS delivery |

### Data Flows: 4 flows

This is where `personal_data_elements` and cross-border transfer live in the RoPA export, so the 15 data categories from the source document are distributed here, matched one-to-one to the purpose each element actually serves (5 + 4 + 4 + 2 = 15, same total as the source).

| Name | Source | Channel | Destination | Data Elements | Cross-border |
|---|---|---|---|---|---|
| KYC verification via CERSAI | Varam Borrower KYC & Identity Verification (asset) | API | "CERSAI Central Registry" (free text) | full_name, pan_card, masked_aadhaar, photo, voter_id | No |
| Credit bureau enquiry via CIBIL | Varam Borrower Credit Assessment & Scoring (asset) | API | "CIBIL Credit Bureau" (free text) | income_details, bank_statements, existing_loan_info, employment_status | No |
| Loan servicing data on AWS | Varam Borrower Loan Servicing & Collections (asset) | CLOUD_SYNC | "AWS ap-south-1" (free text) | bank_account_no, ifsc_code, current_location, contact_number | No |
| OTP/SMS delivery via Twilio | Varam Borrower Marketing & Promotional Offers (asset) | API | "Twilio" (free text) | mobile_number, email_address | Yes, United States |

For the Twilio flow, set Description to `Safeguard: Standard Contractual Clauses (SCC)`, since there's no dedicated safeguard-mechanism field (see "Known limitations").

### Compliance mapping (Gap Assessment)

Map all 4 assets to DPDP **Sec 8, "General obligations of Data Fiduciary"**, the closest existing requirement to the source document's security-measures and retention-limitation language. Additionally map the Marketing asset to DPDP **Sec 6, "Consent management"**, since it's the one consent-basis purpose among the four.

---

## Example 2: Employee data (`varam_employee_v1`)

Sector: Finance / Employee HR. Covers payroll & taxation, group health insurance, and performance management.

### Data Principal

`Employee`, already seeded by default, matches the source document's `data_subject_categories: employee` exactly. No new principal needs to be defined.

### Data Register: 3 assets

All three: System Type `APPLICATION`, Controller Role `CONTROLLER` (Varam is the Data Fiduciary; Greytip/HDFCLife/EPFO are its Data Processors), Data Principal `Employee`.

| Name | Category | Sensitivity | Purpose | Processing Basis | Retention |
|---|---|---|---|---|---|
| Varam Employee Payroll, Taxation & Compliance | EMPLOYEE | CONFIDENTIAL | Payroll processing, tax withholding, and statutory compliance (EPFO/TDS) | LEGAL_OBLIGATION | 10 years from cessation of employment |
| Varam Employee Health Insurance & Benefits | HEALTH | RESTRICTED | Group health insurance enrollment and benefits administration | CONTRACTUAL | 2 years from cessation of employment |
| Varam Employee Performance & Skill Development | EMPLOYEE | CONFIDENTIAL | Performance review and skill development tracking | LEGITIMATE_USE | 5 years from cessation of employment |

**Source mapping for legal basis:** "Contractual Necessity" maps to `CONTRACTUAL`; "Legitimate Use / Employment" maps to `LEGITIMATE_USE`. The payroll purpose is listed in the source as "Contractual Necessity / Statutory Obligation", a *compound* basis for a single purpose (distinct from example 1's mixed-*across*-purposes case); `LEGAL_OBLIGATION` was chosen as the dominant driver since EPFO/TDS compliance is legally mandated, not merely contractual. This is the same single-value-per-asset limitation as example 1, showing up in a slightly different shape.

### Recipients (Shared With)

Matches the source document's Purpose-Processor Mapping table. Note Greytip appears twice, once per asset it serves, and the payroll asset carries two recipients:

| On asset | Recipient | Type | Purpose of sharing |
|---|---|---|---|
| Varam Employee Payroll, Taxation & Compliance | Greytip | VENDOR | Payroll and HRMS platform |
| Varam Employee Payroll, Taxation & Compliance | EPFO | REGULATOR | Provident fund administration |
| Varam Employee Health Insurance & Benefits | HDFCLife | VENDOR | Group health insurance provider |
| Varam Employee Performance & Skill Development | Greytip | VENDOR | HRMS platform for performance and training records |

### Data Flows: 4 flows

The 11 data categories from the source document are distributed across flows to their relevant processor (5 + 4 + 3 payroll-adjacent groupings below, with realistic overlap where the same field feeds two processors, e.g. `full_name` and `salary_details` both go to Greytip and EPFO). `Cross-Border Transfers: No cross-border transfers` in the source, so every flow here is domestic, in contrast to example 1's Twilio flow.

| Name | Source | Channel | Destination | Data Elements | Cross-border |
|---|---|---|---|---|---|
| Payroll processing via Greytip | Varam Employee Payroll, Taxation & Compliance (asset) | API | "Greytip HRMS" (free text) | full_name, bank_account_details, salary_details | No |
| EPFO contribution filing | Varam Employee Payroll, Taxation & Compliance (asset) | API | "EPFO Portal" (free text) | full_name, pan_number, aadhaar_number, salary_details | No |
| Health insurance enrollment via HDFCLife | Varam Employee Health Insurance & Benefits (asset) | FILE_TRANSFER | "HDFCLife" (free text) | full_name, dependent_details, health_declaration, contact_information | No |
| Performance data sync via Greytip | Varam Employee Performance & Skill Development (asset) | API | "Greytip HRMS" (free text) | performance_appraisals, training_records, attendance_logs | No |

### Compliance mapping (Gap Assessment)

Map the Payroll asset to both DPDP **Sec 8, "General obligations of Data Fiduciary"** and ISO 27001 **A.8.15, "Logging"**: the latter is a direct match for the source's "audit logs for all sensitive record access," and demonstrates an asset mapped to requirements across two frameworks at once, which the RoPA export's Security Measures column aggregates together. Map the Health Insurance and Performance assets to DPDP **Sec 8** only.

---

## Known limitations of this mapping

Working these two examples end to end surfaced three places where the current schema falls short of a full RoPA, worth a future phase rather than something this test data can work around:

1. **Purpose granularity is asset-level, not activity-level.** A real RoPA activity can carry several purposes, each with its own legal basis, retention, and processor, as both source documents do (example 2 even compounds two legal bases onto a single purpose). TSI Compass currently requires a separate data asset per purpose to represent that, which is workable (as above) but means the RoPA export's Activity Name column will show several rows for what each source calls one activity. A future model would let one data asset declare multiple purpose/basis/retention/processor tuples.
2. **Security measures are compliance-mapped, not free text.** The RoPA export's Security Measures column is sourced from mapped framework requirements (e.g. "DPDP Sec 8", "ISO 27001 A.8.15"), not a free-text description. Each source document's literal control list (AES-256 at rest and TLS 1.3 in transit for borrowers; MFA on HRMS and encrypted payroll database for employees) has no field to live in today; it can go in an asset's Description for reference, but won't surface in the export itself.
3. **Cross-border transfers have no safeguard-mechanism field.** `data_flows` records that a transfer is cross-border and to which country, but not the legal safeguard used (SCC, adequacy decision, BCR, etc.), which DPDP and most frameworks expect a RoPA to state. Example 1's Twilio flow safeguard has to live in its free-text Description, which the export doesn't currently include as its own column.
