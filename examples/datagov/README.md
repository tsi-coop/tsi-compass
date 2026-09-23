# Data Governance demo data

A ready-to-load demo scenario for the Data Governance module (Data Principals, Data Register, Data Flows, RoPA Export), for demos or repeatable local testing. Nothing here runs automatically.

## Contents

| File | What it is |
|------|------------|
| `data-governance-varam-example.md` | The scenario, written up: two DPDP RoPA worked examples (a microlending borrower consent policy and an employee data policy) for a fictional company, Varam, mapped field-by-field onto the Data Register and Data Flows schema. Includes notes on where the current schema falls short of a full RoPA. |
| `seed-varam-data.sql` | Loads that scenario into a running instance: 7 data assets, their principal tags, recipients, DPDP/ISO 27001 compliance mappings, and 8 data flows. |

## Run it

Prerequisites: `db/18_data_governance.sql` already applied (see its header, or `docs/data-register-phase2-plan.md` section 0), and the default seed data from `db/01_init.sql` (frameworks, requirements) and `db/18_data_governance.sql` (Customer/Employee data principals) present and unmodified.

```bash
docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
  -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-data.sql
```

Adjust the container/user/db names if your deployment overrides the defaults (see the main `README.md`'s Environment Variables table).

**This deletes every existing row in `data_flows` and `data_assets`** (and, by cascade, their access/recipients/principals/requirement-mapping rows) before loading the example data. It does not touch any other module.

After it runs, open `data-governance-ropa.html` and filter by Framework (DPDP) or by Data Principal (Customer vs. Employee) to see the two activities export cleanly as separate RoPA rows.
