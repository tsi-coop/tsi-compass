-- ==========================================================================
-- Demo data: Varam DPDP RoPA worked examples (borrower + employee)
--
-- Loads the Data Register / Data Flows example documented in
-- examples/datagov/data-governance-varam-example.md into a running
-- TSI Compass instance, for demos or repeatable local testing.
--
-- Prerequisites:
--   - db/18_data_governance.sql already applied (data_principals/data_assets
--     phase-2 columns/data_flows/etc. must exist)
--   - The seeded default frameworks/requirements from db/01_init.sql (DPDP,
--     ISO 27001) present and unmodified
--   - The seeded default data_principals from db/18_data_governance.sql
--     (Customer, Employee) present
--
-- WARNING: this DELETEs every existing row in data_flows and data_assets
-- (and, by cascade, data_asset_access/recipients/principals/requirement
-- mappings) before loading the example data. It does not touch any other
-- module (users, controls, vendors, IT assets, etc.).
--
-- Run against a running instance, e.g.:
--   docker exec -i tsi_compass_db psql -U tsi_admin -d tsi_compass \
--     -v ON_ERROR_STOP=1 < examples/datagov/seed-varam-data.sql
-- ==========================================================================

BEGIN;

DELETE FROM data_flows;
DELETE FROM data_assets;

-- Owner for every seeded row: prefers a user named 'sathish' if present
-- (matches the original demo environment), else any ADMIN, else any user.
-- Never returns zero rows unless the users table itself is empty.

-- ===================== Data Register: 7 assets =====================
WITH owner AS (
  SELECT id FROM users
  ORDER BY (username = 'sathish') DESC, (role = 'ADMIN') DESC
  LIMIT 1
)
INSERT INTO data_assets
  (name, system_type, category, sensitivity, owner_id, description,
   purpose, processing_basis, retention_period, deletion_trigger, controller_role,
   discovery_status, reviewed_by, last_reviewed_at)
SELECT v.name, v.system_type, v.category, v.sensitivity, owner.id, v.description,
       v.purpose, v.processing_basis, v.retention_period, v.deletion_trigger, v.controller_role,
       'REVIEWED', owner.id, CURRENT_TIMESTAMP
FROM owner, (VALUES
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
) AS v(name, system_type, category, sensitivity, description, purpose, processing_basis, retention_period, deletion_trigger, controller_role);

-- ===================== Data Principals =====================
INSERT INTO data_asset_principals (data_asset_id, principal_id)
SELECT d.id, p.id FROM data_assets d, data_principals p
WHERE p.name = 'Customer'
  AND d.name IN ('Varam Borrower KYC & Identity Verification','Varam Borrower Credit Assessment & Scoring',
                 'Varam Borrower Loan Servicing & Collections','Varam Borrower Marketing & Promotional Offers');

INSERT INTO data_asset_principals (data_asset_id, principal_id)
SELECT d.id, p.id FROM data_assets d, data_principals p
WHERE p.name = 'Employee'
  AND d.name IN ('Varam Employee Payroll, Taxation & Compliance','Varam Employee Health Insurance & Benefits',
                 'Varam Employee Performance & Skill Development');

-- ===================== Recipients (Shared With) =====================
INSERT INTO data_asset_recipients (data_asset_id, recipient_type, recipient_name, purpose_of_sharing)
SELECT d.id, r.recipient_type, r.recipient_name, r.purpose_of_sharing
FROM data_assets d JOIN (VALUES
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
FROM data_assets d JOIN framework_requirements fr ON fr.section_code = 'Sec 8'
JOIN frameworks f ON f.id = fr.framework_id AND f.name = 'DPDP'
WHERE d.name LIKE 'Varam %';

INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id)
SELECT d.id, fr.id
FROM data_assets d JOIN framework_requirements fr ON fr.section_code = 'Sec 6'
JOIN frameworks f ON f.id = fr.framework_id AND f.name = 'DPDP'
WHERE d.name = 'Varam Borrower Marketing & Promotional Offers';

INSERT INTO data_asset_requirement_mappings (data_asset_id, requirement_id)
SELECT d.id, fr.id
FROM data_assets d JOIN framework_requirements fr ON fr.section_code = 'A.8.15'
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
JOIN data_assets d ON d.name = v.source_asset_name;

COMMIT;
