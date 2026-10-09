# Reports and Dashboard Guide

## Executive Dashboard
The first page after sign-in. It shows Control Readiness, Open Risks, Policy Tasks, Evidence Artifacts and Vendor Reviews Overdue, plus a Recent System Activity feed. Roles see only the tiles for modules they can access.

## Export Reports
**Reports and Exports** downloads live CSVs for risks, controls, audit observations, incidents, assets, vendors and tickets. The file name carries the date.

## Monthly report
**Download PDF** produces a formatted monthly report. An executive summary function is available through the API.

## Scheduling
Compass has no background scheduler or email delivery. The API can store scheduled-report definitions (name, type, cadence, recipients) but nothing runs them; generate reports when due or call the API from an external scheduler.

## Tips
- For auditors, combine a CSV export with the System Audit Trail and the Evidence Locker.
- Data Governance has its own exports: the RoPA Export, DPIA list and Data Register can be downloaded from their pages.
