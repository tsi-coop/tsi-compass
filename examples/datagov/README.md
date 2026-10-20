# Data Governance demo data

A ready-to-load demo scenario for the Data Governance module (Data Principals, Data Register, Data Flows, RoPA Export), for demos or repeatable local testing. Nothing here runs automatically.

## Contents

| File | What it is |
|------|------------|
| `data-governance-varam-example.md` | The scenario, written up: two DPDP RoPA worked examples (a microlending borrower consent policy and an employee data policy) for a fictional company, Varam, mapped field-by-field onto the Data Register and Data Flows schema. Includes notes on where the current schema falls short of a full RoPA. |
| `data-governance-varam-dpia.md` | DPIA walkthrough for the same scenario: expected risk scores per activity (4 High / 1 Medium / 2 Low) and the review workflow. |
| `sample-schema-export.csv` | A small `information_schema` export (customers, patients, orders tables with a mix of personal and benign columns) to try Data Discovery with. On the Discovery page choose **Import schema**, upload this file and expect 12 findings across 3 tables (PAN, Aadhaar, mobile, email, date of birth, bank account, UPI, diagnosis and so on); `id`, `status`, `product_name`, `ip_address`, `company`, `has_aadhaar` and similar are not flagged. Accept a table to create its Data Register entry. |
| `varam-lending-schema.csv` | Discovery demo, borrower side: an `information_schema` export of Varam's lending database (`borrower_kyc`, `credit_assessment`, `loan_servicing`, `marketing_offers`, plus benign `loan_products` and `system_audit_log`). Column names mirror the data elements in the Varam flows (`pan_card`, `masked_aadhaar`, `voter_id`, `bank_account_no`, `ifsc_code`, ...). Import under any source name (for example **Varam LOS**). Expect 12 findings across 4 tables. |
| `varam-hrms-schema.csv` | Discovery demo, employee side: Varam's HRMS database (`employee_payroll`, `employee_health_insurance`, `employee_performance`, plus benign `holiday_calendar`). Import under any source name (for example **Varam HRMS**). Expect 11 findings across 3 tables. |
| `seed-varam-after-discovery.sql` | Finishes the Varam scenario after the two imports above are accepted: classifies the 7 discovered entries and adds principals, recipients, compliance mappings, the 8 data flows and the DPIA risk signals. The discovery-first alternative to `seed-varam-data.sql` (do not run both). |
| `seed-varam-dpia.sql` | Sets the profiling / large-scale flags the DPIA scoring needs for that scenario. Run after `db/19_dpia.sql` and `seed-varam-data.sql`. |
| `reset-varam-data.sql` | Removes the Varam demo data (assets, flows, DPIAs, discovery sources and findings) so you can load either path again. Leaves other rows and the seeded vendors alone. Run: `docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass -v ON_ERROR_STOP=1 < examples/datagov/reset-varam-data.sql` |
| `seed-varam-data.sql` | Loads that scenario into a running instance: 7 data assets, their principal tags, recipients, DPDP/ISO 27001 compliance mappings, and 8 data flows. |

## Run it

Prerequisites: `db/18_data_governance.sql` already applied (see its header, or `docs/architecture/system-design.md` section 5.2), and the default seed data from `db/01_init.sql` (frameworks, requirements) and `db/18_data_governance.sql` (Customer/Employee data principals) present and unmodified.

```bash
docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
  -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-data.sql
```

Adjust the container/user/db names if your deployment overrides the defaults (see the main `README.md`'s Environment Variables table).

**This deletes every existing row in `data_flows` and `data_assets`** (and, by cascade, their access/recipients/principals/requirement-mapping rows) before loading the example data. It does not delete from any other module, but it does create the five recipient vendors (CIBIL, AWS, Twilio, Greytip, HDFCLife) if they are not already in Vendors, and links the VENDOR recipients to them (REGULATOR recipients such as CERSAI and EPFO stay unlinked).

After it runs, open `data-governance-ropa.html` and filter by Framework (DPDP) or by Data Principal (Customer vs. Employee) to see the two activities export cleanly as separate RoPA rows.

## Reset and rerun

To clear the demo data and start over (either path):

```bash
docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
  -v ON_ERROR_STOP=1 < examples/datagov/reset-varam-data.sql
```

This removes only Varam rows: assets named `Varam ...` or ending in the seven discovered `schema.table` names, flows touching them, DPIAs, and discovery sources named `Varam...` (with their runs and findings). The five recipient vendors stay, since Vendors may use them.

## Demo: from discovery to RoPA and DPIA

Prerequisites as above, plus `db/19_dpia.sql` and `db/20_data_discovery.sql` applied.

1. **Discover.** Data Register, then **Discover**, then **Import schema**. Source name e.g. `Varam LOS`, upload `varam-lending-schema.csv`. Repeat with source name e.g. `Varam HRMS` and `varam-hrms-schema.csv`. `loan_products`, `system_audit_log` and `holiday_calendar` produce no findings, and the boolean flags (`kyc_verified`, `consent_given`) are skipped on purpose.
2. **Accept.** Select all 7 tables and **Accept as Data Register entries**. They appear in the Register as *Discovered*, tagged "From discovery", but unclassified (no purpose, basis or retention). Show the Gap Check flagging them.
3. **Classify.** Either classify one entry by hand to show the workflow, or run the script to do all seven at once:
   ```bash
   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
     -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-after-discovery.sql
   ```
   Unlike `seed-varam-data.sql`, this deletes nothing outside the 7 entries. Like it, it creates any missing recipient vendors and links VENDOR recipients to them. It stops with a clear error if one of them has not been accepted yet.
4. **Data Flows.** 8 flows now hang off the discovered entries (CERSAI, CIBIL, AWS, Twilio cross-border to the US, Greytip, EPFO, HDFCLife).
5. **RoPA Export.** Filter by DPDP, or by Customer vs. Employee.
6. **DPIA.** Click **Auto-generate from RoPA**; expect 4 High / 1 Medium / 2 Low as in `data-governance-varam-dpia.md`.

Note that the Register entry names are the discovered table names (`Varam LOS.lending.borrower_kyc`), not the purpose-style names (the source name prefix is whatever you typed at import) used in `seed-varam-data.sql` and the walkthrough documents; the purpose, basis and retention values are the same.
