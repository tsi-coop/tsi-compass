-- ==========================================================================
-- Demo data: finish the Varam example AFTER running Data Discovery
--
-- Companion to varam-lending-schema.csv and varam-hrms-schema.csv. Discovery only
-- knows column names, so after you import both files (under any source names) and accept every table,
-- the 7 Register entries exist but are unclassified. This script fills in what a GRC Officer would otherwise type by
-- hand: purpose, legal basis, retention, principals, recipients, compliance
-- mappings, the 8 data flows and the two DPIA risk signals, so the Data Flows,
-- RoPA Export and DPIA pages can be demonstrated straight away.
--
-- It is the discovery-first alternative to seed-varam-data.sql: do not run both.
-- It only touches the 7 entries named below and the flows it creates; it
-- deletes nothing else. Idempotent: flows are replaced, other rows upserted.
--
-- Prerequisites: db/18_data_governance.sql, db/19_dpia.sql, db/20_data_discovery.sql
-- applied; both CSVs imported and all tables accepted (the script stops with an
-- error if any of the 7 entries is missing).
--
--   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
--     -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-after-discovery.sql
-- ==========================================================================

BEGIN;

CREATE TEMP TABLE varam_map (seed_name text, asset_name text) ON COMMIT DROP;
INSERT INTO varam_map VALUES
  ('Varam Borrower KYC & Identity Verification',    'lending.borrower_kyc'),
  ('Varam Borrower Credit Assessment & Scoring',    'lending.credit_assessment'),
  ('Varam Borrower Loan Servicing & Collections',   'lending.loan_servicing'),
  ('Varam Borrower Marketing & Promotional Offers', 'lending.marketing_offers'),
  ('Varam Employee Payroll, Taxation & Compliance', 'hr.employee_payroll'),
  ('Varam Employee Health Insurance & Benefits',    'hr.employee_health_insurance'),
  ('Varam Employee Performance & Skill Development','hr.employee_performance');

DO $$
DECLARE missing text;
BEGIN
  SELECT string_agg(m.asset_name, ', ') INTO missing
  FROM varam_map m WHERE (SELECT count(*) FROM data_assets d WHERE d.name LIKE '%.' || m.asset_name) <> 1;
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'Not in the Data Register yet (import both CSVs in Discovery and accept every table; each must match exactly one entry ending in schema.table): %', missing;
  END IF;
END $$;

-- ===================== Classify the 7 discovered entries =====================
WITH owner AS (
  SELECT id FROM users
  ORDER BY (username = 'sathish') DESC, (role = 'ADMIN') DESC
  LIMIT 1
)
UPDATE data_assets d SET
  system_type = v.system_type, category = v.category, sensitivity = v.sensitivity,
  description = v.description || E'\n' || d.description,
  purpose = v.purpose, processing_basis = v.processing_basis, retention_period = v.retention_period,
  deletion_trigger = v.deletion_trigger, controller_role = 'CONTROLLER',
  discovery_status = 'REVIEWED', reviewed_by = owner.id, last_reviewed_at = CURRENT_TIMESTAMP,
  involves_profiling = (v.seed_name = 'Varam Borrower Credit Assessment & Scoring'),
  is_large_scale     = (v.seed_name = 'Varam Borrower Loan Servicing & Collections')
FROM owner, varam_map m, (VALUES
  ('Varam Borrower KYC & Identity Verification', 'DATABASE', 'PII', 'RESTRICTED',
   'Borrower identity records used for KYC verification against CERSAI.',
   'KYC and identity verification under PMLA and the DPDP Act', 'LEGAL_OBLIGATION',
   '5 years from cessation of relationship', 'Automated purge job, annually, post 5-year mark', 'CONTROLLER'),
  ('Varam Borrower Credit Assessment & Scoring', 'DATABASE', 'FINANCIAL', 'CONFIDENTIAL',
   'Borrower income and credit history used for loan eligibility scoring via CIBIL.',
   'Credit assessment and scoring for loan eligibility', 'CONTRACTUAL',
   '2 months from collection', 'Automated purge job, monthly', 'CONTROLLER'),
  ('Varam Borrower Loan Servicing & Collections', 'DATABASE', 'FINANCIAL', 'CONFIDENTIAL',
   'Active loan account and repayment data hosted on AWS.',
   'Loan disbursement and collections servicing', 'CONTRACTUAL',
   '7 years from cessation of relationship', 'Automated purge job, annually, post 7-year mark', 'CONTROLLER'),
  ('Varam Borrower Marketing & Promotional Offers', 'DATABASE', 'PII', 'INTERNAL',
   'Borrower contact details used for promotional offers and new-product communications.',
   'Promotional offers and new-product communications', 'CONSENT',
   '2 years from collection', 'Automated purge job, annually; immediate on consent withdrawal', 'CONTROLLER'),
  ('Varam Employee Payroll, Taxation & Compliance', 'APPLICATION', 'EMPLOYEE', 'CONFIDENTIAL',
   'Employee payroll, tax, and statutory compliance records on the Greytip HRMS platform.',
   'Payroll processing, tax withholding, and statutory compliance (EPFO/TDS)', 'LEGAL_OBLIGATION',
   '10 years from cessation of employment', 'Automated purge job, annually, post 10-year mark', 'CONTROLLER'),
  ('Varam Employee Health Insurance & Benefits', 'APPLICATION', 'HEALTH', 'RESTRICTED',
   'Employee and dependent health declarations for group insurance enrollment via HDFCLife.',
   'Group health insurance enrollment and benefits administration', 'CONTRACTUAL',
   '2 years from cessation of employment', 'Automated purge job, annually, post 2-year mark', 'CONTROLLER'),
  ('Varam Employee Performance & Skill Development', 'APPLICATION', 'EMPLOYEE', 'CONFIDENTIAL',
   'Employee performance appraisal and training records on the Greytip HRMS platform.',
   'Performance review and skill development tracking', 'LEGITIMATE_USE',
   '5 years from cessation of employment', 'Automated purge job, annually, post 5-year mark', 'CONTROLLER')
) AS v(seed_name, system_type, category, sensitivity, description, purpose, processing_basis, retention_period, deletion_trigger, controller_role)
WHERE m.seed_name = v.seed_name AND d.name LIKE '%.' || m.asset_name;

-- Seed-style names (as used in the RoPA walkthrough) over the discovered entries.
CREATE TEMP VIEW varam_assets AS
  SELECT d.id, m.seed_name AS name FROM data_assets d JOIN varam_map m ON d.name LIKE '%.' || m.asset_name;

-- Re-run safety: clear what this script adds before adding it again.
DELETE FROM data_flows WHERE source_data_asset_id IN (SELECT id FROM varam_assets);
DELETE FROM data_asset_recipients WHERE data_asset_id IN (SELECT id FROM varam_assets);
DELETE FROM data_asset_principals WHERE data_asset_id IN (SELECT id FROM varam_assets);
DELETE FROM data_asset_requirement_mappings WHERE data_asset_id IN (SELECT id FROM varam_assets);

-- ===================== Data Principals =====================
INSERT INTO data_asset_principals (data_asset_id, principal_id)
SELECT d.id, p.id FROM varam_assets d, data_principals p
WHERE p.name = 'Customer'
  AND d.name IN ('Varam Borrower KYC & Identity Verification','Varam Borrower Credit Assessment & Scoring',
                 'Varam Borrower Loan Servicing & Collections','Varam Borrower Marketing & Promotional Offers');

INSERT INTO data_asset_principals (data_asset_id, principal_id)
SELECT d.id, p.id FROM varam_assets d, data_principals p
WHERE p.name = 'Employee'
  AND d.name IN ('Varam Employee Payroll, Taxation & Compliance','Varam Employee Health Insurance & Benefits',
                 'Varam Employee Performance & Skill Development');

-- ===================== Recipients (Shared With) =====================
INSERT INTO data_asset_recipients (data_asset_id, recipient_type, recipient_name, purpose_of_sharing)
SELECT d.id, r.recipient_type, r.recipient_name, r.purpose_of_sharing
FROM varam_assets d JOIN (VALUES
  ('Varam Borrower KYC & Identity Verification',   'REGULATOR', 'CERSAI',   'Central registry verification'),
  ('Varam Borrower Credit Assessment & Scoring',   'VENDOR',    'CIBIL',    'Credit bureau enquiry'),
  ('Varam Borrower Loan Servicing & Collections',  'VENDOR',    'AWS',      'Cloud hosting and storage'),
  ('Varam Borrower Marketing & Promotional Offers','VENDOR',    'Twilio',   'OTP and SMS delivery'),
  ('Varam Employee Payroll, Taxation & Compliance','VENDOR',    'Greytip',  'Payroll and HRMS platform'),
  ('Varam Employee Payroll, Taxation & Compliance','REGULATOR', 'EPFO',     'Provident fund administration'),
  ('Varam Employee Health Insurance & Benefits',   'VENDOR',    'HDFCLife', 'Group health insurance provider'),
  ('Varam Employee Performance & Skill Development','VENDOR',   'Greytip',  'HRMS platform for performance and training records')
) AS r(asset_name, recipient_type, recipient_name, purpose_of_sharing) ON r.asset_name = d.name;

-- ===================== Compliance mapping (Gap Assessment) =====================
INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id)
SELECT d.id, fr.id
FROM varam_assets d JOIN framework_requirements fr ON fr.section_code = 'Sec 8'
JOIN frameworks f ON f.id = fr.framework_id AND f.name = 'DPDP'
WHERE d.name LIKE 'Varam %';

INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id)
SELECT d.id, fr.id
FROM varam_assets d JOIN framework_requirements fr ON fr.section_code = 'Sec 6'
JOIN frameworks f ON f.id = fr.framework_id AND f.name = 'DPDP'
WHERE d.name = 'Varam Borrower Marketing & Promotional Offers';

INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id)
SELECT d.id, fr.id
FROM varam_assets d JOIN framework_requirements fr ON fr.section_code = 'A.8.15'
JOIN frameworks f ON f.id = fr.framework_id AND f.name = 'ISO 27001'
WHERE d.name = 'Varam Employee Payroll, Taxation & Compliance';

-- ===================== Data Flows: 8 flows =====================
WITH owner AS (
  SELECT id FROM users
  ORDER BY (username = 'sathish') DESC, (role = 'ADMIN') DESC
  LIMIT 1
)
INSERT INTO data_flows
  (name, source_data_asset_id, destination_label, channel, is_approved_channel,
   data_elements, frequency, owner_id, description, is_cross_border, destination_country)
SELECT v.name, d.id, v.destination_label, v.channel, true,
       v.data_elements, v.frequency, owner.id, v.description, v.is_cross_border, v.destination_country
FROM owner, (VALUES
  ('KYC verification via CERSAI', 'Varam Borrower KYC & Identity Verification', 'CERSAI Central Registry', 'API',
   'full_name, pan_card, masked_aadhaar, photo, voter_id', 'Daily', 'Identity documents verified against the central KYC registry.', false, NULL),
  ('Credit bureau enquiry via CIBIL', 'Varam Borrower Credit Assessment & Scoring', 'CIBIL Credit Bureau', 'API',
   'income_details, bank_statements, existing_loan_info, employment_status', 'Per application', 'Credit history pulled for loan eligibility scoring.', false, NULL),
  ('Loan servicing data on AWS', 'Varam Borrower Loan Servicing & Collections', 'AWS ap-south-1', 'CLOUD_SYNC',
   'bank_account_no, ifsc_code, current_location, contact_number', 'Continuous', 'Active loan servicing data hosted and synced on AWS.', false, NULL),
  ('OTP/SMS delivery via Twilio', 'Varam Borrower Marketing & Promotional Offers', 'Twilio', 'API',
   'mobile_number, email_address', 'Ad hoc', 'Safeguard: Standard Contractual Clauses (SCC).', true, 'United States'),
  ('Payroll processing via Greytip', 'Varam Employee Payroll, Taxation & Compliance', 'Greytip HRMS', 'API',
   'full_name, bank_account_details, salary_details', 'Monthly', 'Monthly payroll run processed through Greytip HRMS.', false, NULL),
  ('EPFO contribution filing', 'Varam Employee Payroll, Taxation & Compliance', 'EPFO Portal', 'API',
   'full_name, pan_number, aadhaar_number, salary_details', 'Monthly', 'Statutory provident fund contribution filing.', false, NULL),
  ('Health insurance enrollment via HDFCLife', 'Varam Employee Health Insurance & Benefits', 'HDFCLife', 'FILE_TRANSFER',
   'full_name, dependent_details, health_declaration, contact_information', 'Annual, plus ad hoc for new joiners', 'Group health insurance enrollment data shared with the insurer.', false, NULL),
  ('Performance data sync via Greytip', 'Varam Employee Performance & Skill Development', 'Greytip HRMS', 'API',
   'performance_appraisals, training_records, attendance_logs', 'Quarterly', 'Performance and training records synced to HRMS.', false, NULL)
) AS v(name, source_asset_name, destination_label, channel, data_elements, frequency, description, is_cross_border, destination_country)
JOIN varam_assets d ON d.name = v.source_asset_name;



DROP VIEW varam_assets;

COMMIT;
