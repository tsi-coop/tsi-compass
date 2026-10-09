# IT Operations Guide

For IT staff and GRC. `IT_STAFF` have write access; `GRC_OFFICER` can read.

## Change Management
A change request moves through stages **BRD, Solution design, Approval, QA testing, UAT, Production deployment**. Each stage can require specific documents; the change cannot advance until the required document is uploaded and the gate approved. Attach files and discuss in the comment thread. Changes raised by employees through the self-service portal arrive here (after Manager approval, if enabled).

## Asset Inventory
Register IT assets with category, criticality, patch status, tag, location and lifecycle state. Categories are managed in Platform and Access. Assets separate critical infrastructure (applications, core servers, firewalls) from end-user devices. Bulk import is available.

## Helpdesk
Tickets with category, subcategory, status (Open, In progress, Resolved, Closed), resolution notes and attachments. A ticket can be **escalated to a Security Incident** in one click (one incident per ticket).

## Vendor Register
A lightweight register of vendors, agreement details and licence expiry. Risk tiers, assessments and findings are not shown here; GRC manages them in [Third-Party Risk](third-party-risk-guide.md). Deleting a vendor archives it.

## Software Inventory (SBOM)
Register software components (libraries, frameworks, operating systems, container images) with version, supplier and licence, optionally linked to an IT asset. Bulk import is available.

## Crypto Inventory (CBOM)
Register cryptographic assets (algorithms, certificates, keys, protocols) with algorithm, key size and certificate expiry, optionally linked to an IT asset. Bulk import is available.
