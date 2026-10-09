# Audit and Evidence Guide

For GRC Officers and Admins.

## Audit Schedule
Plan and track audits from scheduled through to the final report. Each audit moves through **Planned, In progress, Remediation, Closed**.

## Findings and Observations
Log observations against an audit with a priority (High, Medium, Low), link them to the risk they relate to or the control that failed, and track remediation (action plan, owner, target date). Observations can be bulk-imported from a spreadsheet.

## Evidence Locker
Tamper-evident storage for audit evidence (logs, screenshots, configuration exports).
- Each upload is stored with a **SHA-256 checksum** and is **locked** on upload, so an auditor can verify that a file has not changed since it was filed.
- Evidence attaches to an observation, and can be downloaded again through the console.
- Deleting evidence is an Admin-only action and is recorded in the audit trail.

## Tips
- File the evidence at the time of the control attestation or the audit, not afterwards.
- Pair the locker with the System Audit Trail (Platform and Access) to show who filed what and when.
