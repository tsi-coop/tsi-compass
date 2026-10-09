# Compliance and Controls Guide

For GRC Officers and Admins.

## Frameworks
Three frameworks are seeded with sample requirements: **ISO 27001 (2022)**, **DPDP (2023)** and **SOC 2**. Each has requirements with a section code, title and description. Bring in more with **import framework controls**. **Framework Readiness** shows a percentage per framework based on control attestation status.

## Controls Register
A master list of controls mapped to framework requirements.
1. **Add a control** with a title, description, owner, implementation status and the requirements it satisfies (many-to-many).
2. **Attest.** Control owners are prompted to submit evidence that a control is operating. Each attestation carries a status (Pending, Compliant, Non-compliant, Approved) and drives readiness percentages.
3. Each control carries a frequency (daily, weekly, monthly, quarterly, annually or continuous) that records how often it should be re-attested. Compass shows it; it does not schedule reminders.

## Exception Register
Request a policy or control exception with a **reason**, **compensating controls** and a mandatory **expiry date**. An approver moves it to Approved or Rejected; it becomes Expired when its date passes. Nothing is open-ended.

## How it connects
- Data Governance maps each Data Register entry to framework requirements (for example DPDP Sec 8) and counts an unmapped entry as a gap.
- Policies (Governance) are the written basis for many controls; audit observations link to failed controls; exceptions reference a policy or control.
