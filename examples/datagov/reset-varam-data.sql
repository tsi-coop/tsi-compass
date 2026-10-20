-- ==========================================================================
-- Reset: removes the Varam demo data so the examples can be loaded again
--
-- Undoes seed-varam-data.sql, or the discovery-first path (the two CSV
-- imports + seed-varam-after-discovery.sql + seed-varam-dpia.sql). Only
-- Varam rows are removed; other Data Register entries, flows and everything
-- outside Data Governance are left alone.
--
-- Removes:
--   - data assets named 'Varam %' (seed-varam-data.sql) or ending in one of
--     the 7 discovered schema.table names (discovery path), plus, by cascade,
--     their principals, recipients, access, requirement mappings and DPIAs
--   - data flows that start or end at one of those assets
--   - discovery sources named 'Varam%' or that produced one of those assets,
--     plus, by cascade, their runs and findings
--
-- Keeps: the vendors the seeds create (CIBIL, AWS, Twilio, Greytip, HDFCLife),
-- since they may be used elsewhere in Vendors; the seeds reuse them on rerun.
--
-- Run against a running instance, e.g.:
--   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
--     -v ON_ERROR_STOP=1 < examples/datagov/reset-varam-data.sql
-- ==========================================================================

BEGIN;

CREATE TEMP TABLE varam_assets ON COMMIT DROP AS
SELECT id FROM data_assets
WHERE name LIKE 'Varam %'
   OR name LIKE '%.lending.borrower_kyc'
   OR name LIKE '%.lending.credit_assessment'
   OR name LIKE '%.lending.loan_servicing'
   OR name LIKE '%.lending.marketing_offers'
   OR name LIKE '%.hr.employee_payroll'
   OR name LIKE '%.hr.employee_health_insurance'
   OR name LIKE '%.hr.employee_performance';

-- Discovery sources first: findings reference the assets (SET NULL), and we
-- need the asset links to find the sources.
DELETE FROM discovery_sources
WHERE name ILIKE 'Varam%'
   OR id IN (SELECT source_id FROM discovery_findings
             WHERE data_asset_id IN (SELECT id FROM varam_assets));

DELETE FROM data_flows
WHERE source_data_asset_id IN (SELECT id FROM varam_assets)
   OR destination_data_asset_id IN (SELECT id FROM varam_assets);

DELETE FROM data_assets WHERE id IN (SELECT id FROM varam_assets);

COMMIT;
