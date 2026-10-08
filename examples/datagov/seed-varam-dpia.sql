-- ==========================================================================
-- Demo data: DPIA risk signals for the Varam example
--
-- Run AFTER db/19_dpia.sql and seed-varam-data.sql. Sets the two risk signals
-- that cannot be derived from other columns (see docs/dpia-plan.md section 2),
-- so the DPIA page shows a spread of risk levels. Idempotent; touches only
-- the 'Varam %' data assets. Then click "Auto-generate from RoPA" on the
-- DPIA page (data-governance-dpia.html).
--
--   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
--     -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-dpia.sql
-- ==========================================================================

BEGIN;

-- Automated credit scoring is profiling of borrowers.
UPDATE data_assets SET involves_profiling = TRUE
WHERE name = 'Varam Borrower Credit Assessment & Scoring';

-- Servicing covers the whole loan book.
UPDATE data_assets SET is_large_scale = TRUE
WHERE name = 'Varam Borrower Loan Servicing & Collections';

COMMIT;
