# Platform and Access Guide

For Admins. Platform and Access manages who can sign in, what each role can do, and the audit trail. Only `ADMIN` can use this menu by default.

## Users
**Users** lists console users (`ADMIN`, `GRC_OFFICER`, `IT_STAFF`). You can provision a user, edit, suspend or reactivate, and reset a password. A recovery passphrase (five words) can be set for each user so they can reset their own password at `/password-reset.html`. Self-service users (Employees and Managers) are provisioned by Admins or by their Managers; vendor portal users are created from Third-Party Risk only.

## Roles and Permissions
**Roles and Permissions** edits the matrix of role against module with a level of `NONE`, `READ`, `WRITE` or `ADMIN`.
- `READ` can call read-only functions (get, list, search, export, download, count, check).
- `WRITE` can also create and change records.
- `ADMIN` can also delete.
Changes take effect within five minutes (permissions are cached). Menus and pages hide or refuse what a role cannot use, and the server enforces the same rules on every API call.

## Managers
**Managers** registers the Manager-1 to Manager-5 reporting chain for the self-service portal. A manager can see and approve for their whole downline. See the [Self-Service Portal Guide](self-service-portal-guide.md).

## Business Settings
Deployment-wide switches. The main one is **Manager approval**: when on, an Employee's tickets and change requests are held for their Manager before IT and GRC staff can see them.

## Categories
**Ticket Categories** (and subcategories) and **Asset Categories** are the lists staff choose from. Retire a category instead of deleting it; existing records keep their label.

## API Keys
Create key and secret pairs for integrations. The secret is shown once and only its hash is kept. Call the API with `X-API-Key` and `X-API-Secret` headers; calls are checked against the same module permissions.

## Import Data
Download a spreadsheet template, fill it in and bulk-import helpdesk tickets, change requests, IT assets, vendors, Data Register entries, audit observations, and CBOM and SBOM components. Risks, vulnerabilities and framework controls have bulk-add or import actions on their own pages.

## Break Glass Key
An emergency credential for recovering an Admin account when normal recovery is unavailable. Store it offline and use it only in an emergency.

## System Audit Trail
An append-only, hash-chained record of every action (who, what, when, with context). Records cannot be edited or deleted, and each carries the hash of the one before it, so tampering is detectable. Search by action text and filter by date range to find an entry.
