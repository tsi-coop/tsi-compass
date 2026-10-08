-- ==========================================
-- MODULE: THIRD-PARTY RISK MANAGEMENT (TPRM)
-- Depends on: 01_init.sql (users, vendors, risks), 02_data_register.sql, 18_data_governance.sql
-- Sits under Risk & Vulnerability and reuses the existing 'risks' role_permissions module
-- (ADMIN / GRC_OFFICER only, IT_STAFF none); no new module row is required.
-- See docs/tprm-plan.md.
-- ==========================================

-- ---------- 1. Extend the shared vendor register (all columns nullable or defaulted) ----------
ALTER TABLE vendors
    ADD COLUMN vendor_type VARCHAR(30) CHECK (vendor_type IN ('SAAS','CLOUD_INFRA','SERVICE_PROVIDER','CONSULTANT','HARDWARE','OTHER')),
    ADD COLUMN criticality VARCHAR(10) CHECK (criticality IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle_status IN ('PROSPECT','ONBOARDING','ACTIVE','OFFBOARDING','TERMINATED')),
    ADD COLUMN business_owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN description TEXT,
    ADD COLUMN primary_contact_name VARCHAR(255),
    ADD COLUMN primary_contact_email VARCHAR(255),
    ADD COLUMN website VARCHAR(255),
    ADD COLUMN processes_personal_data BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stores_data_outside_india BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN has_network_access BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_fourth_party_dependent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inherent_risk VARCHAR(10) CHECK (inherent_risk IN ('LOW','MEDIUM','HIGH','CRITICAL')),   -- snapshot of the effective tier
    ADD COLUMN inherent_score INT,
    ADD COLUMN inherent_override VARCHAR(10) CHECK (inherent_override IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    ADD COLUMN inherent_override_reason TEXT,
    ADD COLUMN residual_risk VARCHAR(10) CHECK (residual_risk IN ('LOW','MEDIUM','HIGH','CRITICAL')),   -- from the latest completed assessment
    ADD COLUMN last_assessed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_review_due DATE,
    ADD COLUMN review_cycle_months INT CHECK (review_cycle_months BETWEEN 1 AND 120),
    ADD COLUMN offboarding_checklist JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN offboarding_override_reason TEXT,
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archived_by UUID REFERENCES users(id) ON DELETE SET NULL;

-- Backfill so nothing starts blank. baseline_risk_score and security_questionnaire_status are left untouched.
UPDATE vendors SET criticality = CASE
        WHEN baseline_risk_score >= 15 THEN 'HIGH'
        WHEN baseline_risk_score >= 8  THEN 'MEDIUM'
        ELSE 'LOW' END
    WHERE criticality IS NULL AND baseline_risk_score IS NOT NULL;
UPDATE vendors SET next_review_due = license_expiry WHERE next_review_due IS NULL AND license_expiry IS NOT NULL;

CREATE INDEX idx_vendors_archived ON vendors(archived_at);
CREATE INDEX idx_vendors_next_review ON vendors(next_review_due);

-- ---------- 2. Questionnaires ----------
CREATE TABLE tprm_questionnaires (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    version VARCHAR(20) NOT NULL DEFAULT '1.0',
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (name, version)
);

CREATE TABLE tprm_questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    questionnaire_id UUID NOT NULL REFERENCES tprm_questionnaires(id) ON DELETE CASCADE,
    section VARCHAR(100) NOT NULL,
    position INT NOT NULL,
    text TEXT NOT NULL,
    guidance TEXT,
    answer_type VARCHAR(10) NOT NULL DEFAULT 'YES_NO' CHECK (answer_type IN ('YES_NO','YES_NO_NA','TEXT')),
    weight INT NOT NULL DEFAULT 1 CHECK (weight BETWEEN 0 AND 10),
    is_critical BOOLEAN NOT NULL DEFAULT FALSE,   -- a "No" on a critical question raises a finding
    maps_to VARCHAR(255)
);

CREATE INDEX idx_tprm_questions_q ON tprm_questions(questionnaire_id, position);

-- ---------- 3. Assessments ----------
CREATE TABLE tprm_assessments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    vendor_id UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    questionnaire_id UUID NOT NULL REFERENCES tprm_questionnaires(id),
    assessment_type VARCHAR(20) NOT NULL DEFAULT 'PERIODIC' CHECK (assessment_type IN ('ONBOARDING','PERIODIC','EVENT_DRIVEN')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','IN_REVIEW','COMPLETED','CANCELLED')),
    assessor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    inherent_risk VARCHAR(10),                      -- tier at the time of completion
    score_pct NUMERIC(5,2),
    residual_risk VARCHAR(10) CHECK (residual_risk IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    conclusion VARCHAR(30) CHECK (conclusion IN ('APPROVED','APPROVED_WITH_CONDITIONS','REJECTED')),
    summary TEXT,
    due_date DATE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tprm_assessments_vendor ON tprm_assessments(vendor_id);
CREATE INDEX idx_tprm_assessments_status ON tprm_assessments(status);

CREATE TABLE tprm_answers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assessment_id UUID NOT NULL REFERENCES tprm_assessments(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES tprm_questions(id) ON DELETE CASCADE,
    answer VARCHAR(10) CHECK (answer IN ('YES','NO','NA')),
    text_answer TEXT,
    comment TEXT,
    evidence_ref VARCHAR(255),
    answered_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (assessment_id, question_id)
);

-- ---------- 4. Findings ----------
CREATE TABLE tprm_findings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    vendor_id UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    assessment_id UUID REFERENCES tprm_assessments(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    severity VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','REMEDIATED','ACCEPTED','CLOSED')),
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','ASSESSMENT')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    due_date DATE,
    remediation_plan TEXT,
    accepted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    acceptance_reason TEXT,
    linked_risk_id UUID REFERENCES risks(id) ON DELETE SET NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tprm_findings_vendor ON tprm_findings(vendor_id);
CREATE INDEX idx_tprm_findings_status ON tprm_findings(status);

-- ---------- 5. Contracts and documents ----------
CREATE TABLE tprm_contracts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    vendor_id UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    contract_type VARCHAR(10) NOT NULL CHECK (contract_type IN ('MSA','SOW','DPA','NDA','SLA','OTHER')),
    title VARCHAR(255) NOT NULL,
    start_date DATE,
    end_date DATE,
    auto_renews BOOLEAN NOT NULL DEFAULT FALSE,
    notice_period_days INT NOT NULL DEFAULT 0 CHECK (notice_period_days >= 0),
    value_inr NUMERIC(16,2),
    has_security_clauses BOOLEAN NOT NULL DEFAULT FALSE,
    has_breach_notification_clause BOOLEAN NOT NULL DEFAULT FALSE,
    has_audit_right BOOLEAN NOT NULL DEFAULT FALSE,
    has_data_return_deletion_clause BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tprm_contracts_vendor ON tprm_contracts(vendor_id);

CREATE TABLE tprm_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    vendor_id UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    assessment_id UUID REFERENCES tprm_assessments(id) ON DELETE SET NULL,
    contract_id UUID REFERENCES tprm_contracts(id) ON DELETE SET NULL,
    doc_type VARCHAR(20) NOT NULL DEFAULT 'OTHER' CHECK (doc_type IN ('CERTIFICATE','SOC_REPORT','PENTEST_SUMMARY','POLICY','CONTRACT','OTHER')),
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    valid_until DATE,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tprm_documents_vendor ON tprm_documents(vendor_id);
CREATE INDEX idx_tprm_documents_valid ON tprm_documents(valid_until);

-- ---------- 6. Default questionnaire (content is editable by ADMIN in the UI) ----------
-- Phrased so that YES is always the desired answer. Critical = a NO raises a finding.
-- Guidance text is shown to vendors in the vendor portal as well as to assessors, so keep it neutral.
WITH q AS (
    INSERT INTO tprm_questionnaires (name, version, description)
    VALUES ('Baseline Vendor Security Assessment', '1.0',
            'General security and DPDP-aligned personal data handling questionnaire. Review the DPDP section with your counsel before relying on it.')
    RETURNING id
)
INSERT INTO tprm_questions (questionnaire_id, section, position, text, guidance, answer_type, weight, is_critical, maps_to)
SELECT q.id, v.section, v.position, v.text, v.guidance, v.answer_type, v.weight, v.is_critical, v.maps_to
FROM q, (VALUES
 -- Governance and policies
 ('Governance and policies',  1, 'Does the vendor maintain a documented information security policy, approved by management and reviewed at least annually?', 'Provide the policy or its table of contents, with the date of its last review.', 'YES_NO', 3, FALSE, 'ISO 27001 A.5.1'),
 ('Governance and policies',  2, 'Is a named person or team accountable for information security (for example a CISO or security lead)?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.2'),
 ('Governance and policies',  3, 'Does the vendor carry out periodic information security risk assessments of its own environment?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 clause 6.1'),
 ('Governance and policies',  4, 'Does the vendor assess its own sub-contractors that touch our data and bind them to equivalent security obligations?', 'Mark N/A only if the vendor uses no sub-contractors.', 'YES_NO_NA', 2, FALSE, 'DPDP Sec 8(2); ISO 27001 A.5.19'),
 ('Governance and policies',  5, 'Are all staff required to complete security and privacy awareness training at least annually?', NULL, 'YES_NO', 1, FALSE, 'ISO 27001 A.6.3'),
 -- Access control
 ('Access control',           6, 'Is multi-factor authentication enforced for administrative and remote access to systems that hold or process our data?', NULL, 'YES_NO', 3, TRUE, 'ISO 27001 A.8.5; DPDP Rule 6'),
 ('Access control',           7, 'Is access granted on a least-privilege, need-to-know basis and reviewed at least every six months?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.18; DPDP Rule 6'),
 ('Access control',           8, 'Are individual named accounts used (no shared admin credentials), with access removed promptly when staff leave?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.16'),
 ('Access control',           9, 'Are privileged actions logged, with logs protected from tampering?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.15; DPDP Rule 6'),
 ('Access control',          10, 'Is a strong password policy enforced, or single sign-on with strong authentication?', NULL, 'YES_NO', 1, FALSE, 'ISO 27001 A.5.17'),
 -- Data protection and encryption
 ('Data protection and encryption', 11, 'Is our data encrypted at rest using industry-standard algorithms?', 'For example AES-256 for databases, disks and file storage.', 'YES_NO', 3, TRUE, 'ISO 27001 A.8.24; DPDP Rule 6'),
 ('Data protection and encryption', 12, 'Is our data encrypted in transit (TLS 1.2 or higher)?', NULL, 'YES_NO', 3, TRUE, 'ISO 27001 A.8.24; DPDP Rule 6'),
 ('Data protection and encryption', 13, 'Are encryption keys managed separately from the data, with restricted access?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.24'),
 ('Data protection and encryption', 14, 'Is our data logically segregated from other customers'' data?', 'N/A for single-tenant or on-premises deployments.', 'YES_NO_NA', 2, FALSE, 'ISO 27001 A.8.22'),
 ('Data protection and encryption', 15, 'Are backups encrypted and restore-tested at least annually?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.13'),
 -- Personal data handling (DPDP)
 ('Personal data handling (DPDP)', 16, 'Does the vendor process our personal data only on our documented instructions and for the agreed purpose?', 'The vendor acts as a Data Processor on behalf of us as Data Fiduciary.', 'YES_NO', 3, TRUE, 'DPDP Sec 8(2); purpose limitation'),
 ('Personal data handling (DPDP)', 17, 'Is there a signed contract or data processing agreement covering reasonable security safeguards and our instructions?', 'Attach the signed agreement or the relevant clauses.', 'YES_NO', 3, TRUE, 'DPDP Sec 8(2)'),
 ('Personal data handling (DPDP)', 18, 'Will the vendor notify us of a personal data breach without undue delay, within a timeline stated in the contract?', 'We must in turn inform the Data Protection Board and affected Data Principals.', 'YES_NO', 3, TRUE, 'DPDP Sec 8(6); Rule 7'),
 ('Personal data handling (DPDP)', 19, 'Can the vendor delete or return our personal data on request and at contract end, and confirm this in writing?', NULL, 'YES_NO', 3, TRUE, 'DPDP Sec 8(7)'),
 ('Personal data handling (DPDP)', 20, 'Can the vendor support our handling of Data Principal requests (access, correction, erasure, grievance)?', NULL, 'YES_NO', 2, FALSE, 'DPDP Sec 11-14'),
 ('Personal data handling (DPDP)', 21, 'Does the vendor apply a retention schedule so that personal data is not kept longer than needed?', NULL, 'YES_NO', 2, FALSE, 'DPDP Sec 8(7)'),
 ('Personal data handling (DPDP)', 22, 'Is our personal data stored and processed only in locations disclosed to us, none of them restricted by the Central Government?', NULL, 'YES_NO', 2, FALSE, 'DPDP Sec 16'),
 ('Personal data handling (DPDP)', 23, 'Does the vendor disclose all sub-processors of our personal data and notify us before changes?', 'Mark N/A only if no sub-processors are used.', 'YES_NO_NA', 2, FALSE, 'DPDP Sec 8(2)'),
 ('Personal data handling (DPDP)', 24, 'Is personal data masked, pseudonymised or excluded from non-production (test, development) environments?', NULL, 'YES_NO', 2, FALSE, 'DPDP Rule 6'),
 -- Incident management
 ('Incident management',     25, 'Does the vendor have a documented incident response plan that is tested at least annually?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.24'),
 ('Incident management',     26, 'Where applicable, does the vendor report cyber incidents to CERT-In within the required timelines?', 'N/A if not a service provider or body corporate covered by the CERT-In directions.', 'YES_NO_NA', 2, FALSE, 'CERT-In directions (2022)'),
 ('Incident management',     27, 'Are security events logged and monitored, with alerting for suspicious activity?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.16'),
 ('Incident management',     28, 'Are ICT system logs retained for at least 180 days, within Indian jurisdiction?', NULL, 'YES_NO_NA', 1, FALSE, 'CERT-In directions (2022)'),
 -- Business continuity
 ('Business continuity',     29, 'Does the vendor have a business continuity and disaster recovery plan that is tested at least annually?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.30'),
 ('Business continuity',     30, 'Are recovery time and recovery point objectives defined and aligned with our needs?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.5.30'),
 ('Business continuity',     31, 'Is the service hosted with redundancy (for example multiple availability zones)?', NULL, 'YES_NO_NA', 1, FALSE, 'ISO 27001 A.8.14'),
 -- Secure development and vulnerability management
 ('Secure development and vulnerability management', 32, 'Does the vendor follow a secure development lifecycle including code review and security testing?', 'N/A if the vendor does not develop software for us.', 'YES_NO_NA', 2, FALSE, 'ISO 27001 A.8.25'),
 ('Secure development and vulnerability management', 33, 'Is independent penetration testing performed at least annually, with findings remediated?', 'Attach a summary of the latest report.', 'YES_NO', 3, FALSE, 'ISO 27001 A.8.29'),
 ('Secure development and vulnerability management', 34, 'Are critical security patches applied within a defined timeframe?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.8'),
 ('Secure development and vulnerability management', 35, 'Are vulnerability scans run regularly against systems that process our data?', NULL, 'YES_NO', 1, FALSE, 'ISO 27001 A.8.8'),
 -- Physical and HR security
 ('Physical and HR security', 36, 'Are staff with access to our data background-verified and bound by confidentiality obligations?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.6.1, A.6.6'),
 ('Physical and HR security', 37, 'Are facilities that hold our data physically access-controlled?', 'N/A for fully cloud-hosted services (rely on the cloud provider''s attestation).', 'YES_NO_NA', 1, FALSE, 'ISO 27001 A.7.2'),
 ('Physical and HR security', 38, 'Are staff devices protected (disk encryption, endpoint protection, remote wipe)?', NULL, 'YES_NO', 2, FALSE, 'ISO 27001 A.8.1'),
 -- Compliance and certifications
 ('Compliance and certifications', 39, 'Does the vendor hold a current ISO/IEC 27001 certificate or SOC 2 Type II report covering the services provided to us?', 'Attach the certificate or report, with its expiry date.', 'YES_NO', 3, FALSE, 'ISO 27001; SOC 2'),
 ('Compliance and certifications', 40, 'Will the vendor allow us, or our auditor, to review its controls on reasonable notice?', NULL, 'YES_NO', 2, FALSE, 'DPDP Sec 8(1); audit right'),
 ('Compliance and certifications', 41, 'Describe any security incident or regulatory action affecting the vendor in the past 24 months (or write "none").', NULL, 'TEXT', 0, FALSE, NULL),
 ('Compliance and certifications', 42, 'List other relevant certifications or attestations held (PCI DSS, ISO 27701, CSA STAR and so on).', NULL, 'TEXT', 0, FALSE, NULL)
) AS v(section, position, text, guidance, answer_type, weight, is_critical, maps_to);
