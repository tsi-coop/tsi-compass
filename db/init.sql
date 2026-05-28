-- Enable UUID and Cryptographic extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ==========================================
-- MODULE 1: CORE PLATFORM & ACCESS CONTROL
-- ==========================================

CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('HEAD_OFFICE', 'REGIONAL_OFFICE', 'BRANCH', 'DEPARTMENT')),
    parent_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE designations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE designation_kras (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    designation_id UUID REFERENCES designations(id) ON DELETE CASCADE,
    kra_title VARCHAR(255) NOT NULL,
    responsibility_description TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    username VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL CHECK (role IN ('ADMIN', 'RISK_OWNER', 'COMPLIANCE_OFFICER', 'INTERNAL_AUDITOR', 'IT_STAFF', 'USER')),
    designation_id UUID REFERENCES designations(id) ON DELETE SET NULL,
    department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING')),
    recovery_key_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE system_audit_trail (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    audit_action VARCHAR(255) NOT NULL,
    context_details JSONB,
    previous_hash VARCHAR(64),
    log_hash VARCHAR(64) NOT NULL
);

-- ==========================================
-- MODULE 2: GOVERNANCE & POLICY MANAGEMENT
-- ==========================================

CREATE TABLE committees (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE committee_meetings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    committee_id UUID REFERENCES committees(id) ON DELETE CASCADE,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    venue VARCHAR(255),
    agenda TEXT,
    status VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CONDUCTED', 'CANCELLED', 'TENTATIVE'))
);

CREATE TABLE committee_mom (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    meeting_id UUID REFERENCES committee_meetings(id) ON DELETE CASCADE,
    mom_text TEXT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    distributed_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE committee_action_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    mom_id UUID REFERENCES committee_mom(id) ON DELETE CASCADE,
    meeting_id UUID REFERENCES committee_meetings(id) ON DELETE SET NULL,
    deliverable TEXT NOT NULL,
    assignee_id UUID REFERENCES users(id) ON DELETE SET NULL,
    due_date DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'OVERDUE'))
);

CREATE TABLE frameworks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    version VARCHAR(50),
    description TEXT
);

CREATE TABLE framework_requirements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    framework_id UUID REFERENCES frameworks(id) ON DELETE CASCADE,
    section_code VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('POLICY', 'STANDARD', 'GUIDELINE', 'SOP')),
    parent_policy_id UUID REFERENCES policies(id) ON DELETE SET NULL,
    version VARCHAR(20) DEFAULT '1.0',
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'UNDER_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED')),
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    framework_tags VARCHAR(500),
    review_date DATE,
    content TEXT NOT NULL DEFAULT '',
    document_name VARCHAR(255),
    document_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE policy_clauses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID REFERENCES policies(id) ON DELETE CASCADE,
    clause_number VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL
);

CREATE TABLE clause_framework_mappings (
    clause_id UUID REFERENCES policy_clauses(id) ON DELETE CASCADE,
    requirement_id UUID REFERENCES framework_requirements(id) ON DELETE CASCADE,
    PRIMARY KEY (clause_id, requirement_id)
);

CREATE TABLE policy_reviews (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID REFERENCES policies(id) ON DELETE CASCADE,
    reviewer_id UUID REFERENCES users(id) ON DELETE SET NULL,
    comments TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE policy_attestations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID REFERENCES policies(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    acknowledged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (policy_id, user_id)
);

-- ==========================================
-- MODULE 3: RISK & VULNERABILITY MANAGEMENT
-- ==========================================

CREATE SEQUENCE risk_code_seq START 1;

CREATE TABLE risks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    risk_code VARCHAR(20) NOT NULL UNIQUE DEFAULT ('R-' || LPAD(nextval('risk_code_seq')::text, 3, '0')),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100) NOT NULL,
    inherent_impact INT NOT NULL CHECK (inherent_impact BETWEEN 1 AND 5),
    inherent_likelihood INT NOT NULL CHECK (inherent_likelihood BETWEEN 1 AND 5),
    inherent_risk_score INT GENERATED ALWAYS AS (inherent_impact * inherent_likelihood) STORED,
    treatment_strategy VARCHAR(50) CHECK (treatment_strategy IN ('ACCEPT', 'MITIGATE', 'TRANSFER', 'AVOID')),
    mitigating_controls TEXT,
    residual_impact INT CHECK (residual_impact BETWEEN 1 AND 5),
    residual_likelihood INT CHECK (residual_likelihood BETWEEN 1 AND 5),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'IDENTIFIED' CHECK (status IN ('IDENTIFIED', 'ASSESSED', 'TREATED', 'MONITORED', 'RETIRED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE vuln_code_seq START 1;

CREATE TABLE vapt_engagements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('EXTERNAL_PENTEST', 'INTERNAL_PENTEST', 'FULL_VAPT', 'VULNERABILITY_SCAN')),
    vendor VARCHAR(255),
    scope TEXT,
    start_date DATE,
    end_date DATE,
    status VARCHAR(20) DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'REMEDIATION', 'CLOSED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vulnerabilities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    vuln_code VARCHAR(20) NOT NULL UNIQUE DEFAULT ('VPT-' || LPAD(nextval('vuln_code_seq')::text, 3, '0')),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    source VARCHAR(100) NOT NULL DEFAULT 'Manual',
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'FALSE_POSITIVE')),
    assignee_id UUID REFERENCES users(id) ON DELETE SET NULL,
    linked_risk_id UUID REFERENCES risks(id) ON DELETE SET NULL,
    engagement_id UUID REFERENCES vapt_engagements(id) ON DELETE SET NULL,
    affected_asset VARCHAR(255),
    sla_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    mitigation_details TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- MODULE 4: COMPLIANCE & CONTROL MANAGEMENT
-- ==========================================

CREATE TABLE compliance_regulators (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE compliance_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    regulator_id UUID REFERENCES compliance_regulators(id) ON DELETE CASCADE,
    requirement_title VARCHAR(255) NOT NULL,
    section_reference VARCHAR(50),
    description TEXT NOT NULL
);

CREATE TABLE compliance_calendar (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    compliance_item_id UUID REFERENCES compliance_items(id) ON DELETE CASCADE,
    filing_name VARCHAR(255) NOT NULL,
    due_date DATE NOT NULL,
    assigned_officer_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUBMITTED', 'OVERDUE', 'EXTENSION_REQUESTED')),
    remind_days_before INT DEFAULT 7
);

CREATE TABLE controls (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(50) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('TECHNICAL', 'ADMINISTRATIVE', 'PHYSICAL')),
    description TEXT NOT NULL,
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    frequency VARCHAR(50) NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUALLY', 'CONTINUOUS'))
);

CREATE TABLE control_requirement_mappings (
    control_id UUID REFERENCES controls(id) ON DELETE CASCADE,
    requirement_id UUID REFERENCES framework_requirements(id) ON DELETE CASCADE,
    PRIMARY KEY (control_id, requirement_id)
);

CREATE TABLE control_attestations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    control_id UUID REFERENCES controls(id) ON DELETE CASCADE,
    attested_by UUID REFERENCES users(id) ON DELETE SET NULL,
    evidence_file_link VARCHAR(512),
    evidence_checksum VARCHAR(64),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLIANT', 'NON_COMPLIANT', 'APPROVED')),
    attested_at TIMESTAMP WITH TIME ZONE,
    next_due_date DATE NOT NULL
);

CREATE TABLE exceptions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID REFERENCES policies(id) ON DELETE CASCADE,
    control_id UUID REFERENCES controls(id) ON DELETE SET NULL,
    requested_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reason TEXT NOT NULL,
    compensating_controls TEXT NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    approver_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- MODULE 5: INTERNAL AUDIT & EVIDENCE LOCKER
-- ==========================================

CREATE TABLE audits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    scope TEXT NOT NULL,
    framework_id UUID REFERENCES frameworks(id) ON DELETE SET NULL,
    department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    lead_auditor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    scheduled_start DATE NOT NULL,
    scheduled_end DATE NOT NULL,
    status VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'DRAFT_REPORT', 'FINAL_REPORT', 'CLOSED'))
);

CREATE TABLE audit_observations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    audit_id UUID REFERENCES audits(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    linked_risk_id UUID REFERENCES risks(id) ON DELETE SET NULL,
    failed_control_id UUID REFERENCES controls(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REMEDIATED', 'CLOSED'))
);

CREATE TABLE audit_remediations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    observation_id UUID REFERENCES audit_observations(id) ON DELETE CASCADE,
    action_plan TEXT NOT NULL,
    target_date DATE NOT NULL,
    auditee_comments TEXT,
    secure_link_token VARCHAR(64) UNIQUE,
    token_expiry TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'UNDER_REVIEW', 'RESOLVED'))
);

CREATE TABLE evidence_locker (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    timestamp_signature VARCHAR(256) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    audit_id UUID REFERENCES audits(id) ON DELETE SET NULL,
    control_attestation_id UUID REFERENCES control_attestations(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    is_locked BOOLEAN DEFAULT TRUE
);

-- ==========================================
-- MODULE 6: IT OPERATIONS & CHANGE MANAGEMENT
-- ==========================================

CREATE TABLE change_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    requester_id UUID REFERENCES users(id) ON DELETE SET NULL,
    stage VARCHAR(20) DEFAULT 'BRD' CHECK (stage IN ('BRD', 'SOLUTION_DESIGN', 'APPROVAL', 'QA_TESTING', 'UAT', 'PROD_DEPLOYMENT')),
    status VARCHAR(20) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'COMPLETED')),
    compliance_approver_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE change_request_gates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    change_request_id UUID REFERENCES change_requests(id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    required_document_type VARCHAR(100) NOT NULL,
    uploaded_document_link VARCHAR(512),
    is_approved BOOLEAN DEFAULT FALSE,
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE assets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL CHECK (category IN ('APPLICATION', 'CORE_SERVER', 'FIREWALL', 'LAPTOP', 'DESKTOP', 'PRINTER', 'OTHER')),
    criticality VARCHAR(20) NOT NULL CHECK (criticality IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    description TEXT,
    patch_status VARCHAR(20) DEFAULT 'CURRENT' CHECK (patch_status IN ('CURRENT', 'PENDING', 'OVERDUE', 'UNKNOWN')),
    lifecycle_status VARCHAR(30) DEFAULT 'ACTIVE' CHECK (lifecycle_status IN ('ACTIVE', 'EOL', 'DECOMMISSIONING', 'DECOMMISSIONED'))
);

CREATE TABLE vendors (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    agreement_details TEXT,
    license_expiry DATE,
    baseline_risk_score INT CHECK (baseline_risk_score BETWEEN 1 AND 25),
    security_questionnaire_status VARCHAR(50) CHECK (security_questionnaire_status IN ('SENT', 'RECEIVED', 'EVALUATED', 'NOT_APPLICABLE')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE helpdesk_tickets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    asset_id UUID REFERENCES assets(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority VARCHAR(20) DEFAULT 'MEDIUM' CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_to UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- MODULE 7: INCIDENTS & AWARENESS TRAINING
-- ==========================================

CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ticket_escalation_id UUID REFERENCES helpdesk_tickets(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    status VARCHAR(20) DEFAULT 'NEW' CHECK (status IN ('NEW', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED')),
    rca_timeline TEXT,
    rca_business_impact TEXT,
    rca_root_cause TEXT,
    rca_preventative_actions TEXT,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE incident_escalation_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    severity VARCHAR(20) NOT NULL,
    role_to_notify VARCHAR(50) NOT NULL,
    escalation_delay_minutes INT NOT NULL
);

CREATE TABLE training_materials (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) CHECK (type IN ('VIDEO', 'DOCUMENT', 'GUIDELINE')),
    content_url VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE awareness_campaigns (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    scheduled_start DATE NOT NULL,
    scheduled_end DATE NOT NULL,
    material_id UUID REFERENCES training_materials(id) ON DELETE SET NULL,
    status VARCHAR(20) DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'ACTIVE', 'COMPLETED'))
);

CREATE TABLE campaign_enrollments (
    campaign_id UUID REFERENCES awareness_campaigns(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) DEFAULT 'ENROLLED' CHECK (status IN ('ENROLLED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (campaign_id, user_id)
);

CREATE TABLE best_practices_vault (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) DEFAULT 'General',
    audience VARCHAR(100),
    status VARCHAR(20) DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE scheduled_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    cadence VARCHAR(20) NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY')),
    recipients TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


-- ==========================================
-- MODULE 8: INCIDENT & KB DOCUMENT STORAGE
-- ==========================================

CREATE TABLE incident_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    incident_id UUID REFERENCES incidents(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE kb_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    article_id UUID REFERENCES best_practices_vault(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE campaign_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    campaign_id UUID REFERENCES awareness_campaigns(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    key_prefix VARCHAR(12) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL,
    api_secret_hash VARCHAR(64) NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(10) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED'))
);

-- ==========================================
-- IMMUTABLE AUDIT TRAIL TRIGGERS
-- ==========================================

-- Trigger to calculate the hash chain for system_audit_trail
CREATE OR REPLACE FUNCTION system_audit_trail_hash_chain()
RETURNS TRIGGER AS $$
DECLARE
    prev_hash VARCHAR(64);
    chain_data TEXT;
BEGIN
    -- Get the most recent row's hash based on timestamp and ID ordering
    SELECT log_hash INTO prev_hash 
    FROM system_audit_trail 
    ORDER BY timestamp DESC, id DESC
    LIMIT 1;

    -- If no previous row exists, seed with 64 zeros
    IF prev_hash IS NULL THEN
        prev_hash := '0000000000000000000000000000000000000000000000000000000000000000';
    END IF;

    NEW.previous_hash := prev_hash;

    -- Concatenate log fields to verify data integrity
    chain_data := COALESCE(NEW.id::text, '') || '|' ||
                  COALESCE(NEW.timestamp::text, '') || '|' ||
                  COALESCE(NEW.user_id::text, '') || '|' ||
                  COALESCE(NEW.audit_action, '') || '|' ||
                  COALESCE(NEW.context_details::text, '') || '|' ||
                  prev_hash;

    -- Compute SHA-256 hash using digest() from pgcrypto
    NEW.log_hash := encode(digest(chain_data, 'sha256'), 'hex');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_system_audit_trail_hash_chain
BEFORE INSERT ON system_audit_trail
FOR EACH ROW
EXECUTE FUNCTION system_audit_trail_hash_chain();

-- Trigger to prevent any updates or deletes on system_audit_trail (Immutability)
CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Modification or deletion of system audit trail ledger is strictly prohibited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_audit_log_update
BEFORE UPDATE OR DELETE ON system_audit_trail
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_log_modification();


-- ==========================================
-- ROLE PERMISSIONS MATRIX
-- ==========================================

CREATE TABLE role_permissions (
    role VARCHAR(50) NOT NULL,
    module VARCHAR(50) NOT NULL,
    permission_level VARCHAR(10) NOT NULL DEFAULT 'NONE' CHECK (permission_level IN ('NONE', 'READ', 'WRITE', 'ADMIN')),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role, module)
);

-- Seed default permission matrix
INSERT INTO role_permissions (role, module, permission_level) VALUES
('ADMIN',               'platform',   'ADMIN'),
('ADMIN',               'governance', 'ADMIN'),
('ADMIN',               'risks',      'ADMIN'),
('ADMIN',               'controls',   'ADMIN'),
('ADMIN',               'evidence',   'ADMIN'),
('ADMIN',               'operations', 'ADMIN'),
('ADMIN',               'incidents',  'ADMIN'),
('ADMIN',               'reports',    'ADMIN'),
('ADMIN',               'helpdesk',   'ADMIN'),
('RISK_OWNER',          'platform',   'NONE'),
('RISK_OWNER',          'governance', 'READ'),
('RISK_OWNER',          'risks',      'ADMIN'),
('RISK_OWNER',          'controls',   'WRITE'),
('RISK_OWNER',          'evidence',   'READ'),
('RISK_OWNER',          'operations', 'READ'),
('RISK_OWNER',          'incidents',  'READ'),
('RISK_OWNER',          'reports',    'WRITE'),
('RISK_OWNER',          'helpdesk',   'NONE'),
('COMPLIANCE_OFFICER',  'platform',   'READ'),
('COMPLIANCE_OFFICER',  'governance', 'ADMIN'),
('COMPLIANCE_OFFICER',  'risks',      'WRITE'),
('COMPLIANCE_OFFICER',  'controls',   'ADMIN'),
('COMPLIANCE_OFFICER',  'evidence',   'WRITE'),
('COMPLIANCE_OFFICER',  'operations', 'READ'),
('COMPLIANCE_OFFICER',  'incidents',  'READ'),
('COMPLIANCE_OFFICER',  'reports',    'WRITE'),
('COMPLIANCE_OFFICER',  'helpdesk',   'READ'),
('INTERNAL_AUDITOR',    'platform',   'READ'),
('INTERNAL_AUDITOR',    'governance', 'READ'),
('INTERNAL_AUDITOR',    'risks',      'READ'),
('INTERNAL_AUDITOR',    'controls',   'WRITE'),
('INTERNAL_AUDITOR',    'evidence',   'ADMIN'),
('INTERNAL_AUDITOR',    'operations', 'READ'),
('INTERNAL_AUDITOR',    'incidents',  'WRITE'),
('INTERNAL_AUDITOR',    'reports',    'WRITE'),
('INTERNAL_AUDITOR',    'helpdesk',   'READ'),
('IT_STAFF',            'platform',   'NONE'),
('IT_STAFF',            'governance', 'NONE'),
('IT_STAFF',            'risks',      'NONE'),
('IT_STAFF',            'controls',   'NONE'),
('IT_STAFF',            'evidence',   'NONE'),
('IT_STAFF',            'operations', 'WRITE'),
('IT_STAFF',            'incidents',  'WRITE'),
('IT_STAFF',            'reports',    'NONE'),
('IT_STAFF',            'helpdesk',   'ADMIN'),
('USER',                'platform',   'NONE'),
('USER',                'governance', 'READ'),
('USER',                'risks',      'READ'),
('USER',                'controls',   'READ'),
('USER',                'evidence',   'NONE'),
('USER',                'operations', 'NONE'),
('USER',                'incidents',  'READ'),
('USER',                'reports',    'READ'),
('USER',                'helpdesk',   'ADMIN');

-- ==========================================
-- INDEXES FOR PERFORMANCE OPTIMIZATION
-- ==========================================

CREATE INDEX idx_audit_logs_timestamp ON system_audit_trail(timestamp DESC);
CREATE INDEX idx_risks_score ON risks(inherent_risk_score DESC);
CREATE INDEX idx_vulnerabilities_deadline ON vulnerabilities(sla_deadline ASC);
CREATE INDEX idx_compliance_calendar_due ON compliance_calendar(due_date ASC);
CREATE INDEX idx_control_attestations_due ON control_attestations(next_due_date ASC);
CREATE INDEX idx_helpdesk_tickets_status ON helpdesk_tickets(status);
CREATE INDEX idx_incidents_severity ON incidents(severity);


-- ==========================================
-- SEED DATA
-- ==========================================

-- Seed Roles & Designations
INSERT INTO designations (id, title) VALUES
('d1000000-0000-0000-0000-000000000001', 'Chief Information Officer'),
('d1000000-0000-0000-0000-000000000002', 'Risk Manager'),
('d1000000-0000-0000-0000-000000000003', 'Lead Auditor'),
('d1000000-0000-0000-0000-000000000004', 'Compliance Officer'),
('d1000000-0000-0000-0000-000000000005', 'IT Support Specialist');

-- Seed KRAs for designations
INSERT INTO designation_kras (designation_id, kra_title, responsibility_description) VALUES
('d1000000-0000-0000-0000-000000000001', 'Governance Steering', 'Oversee the IT Strategy Steering Committee and align IT risk goals with business values.'),
('d1000000-0000-0000-0000-000000000002', 'Risk Mitigation & Register', 'Regularly update the Enterprise Risk Register, oversee VAPT scans, and assign remediation workflows.'),
('d1000000-0000-0000-0000-000000000003', 'Audit Readiness', 'Lead the internal and external audit lifecycles and track observation closures.'),
('d1000000-0000-0000-0000-000000000004', 'Controls Management', 'Map organizational policies to standards (e.g. ISO 27001) and verify control attestations.'),
('d1000000-0000-0000-0000-000000000005', 'Change & Operations Controls', 'Verify compliance document gates during the Change Request lifecycle and manage helpdesk resolutions.');

-- Seed Committees
INSERT INTO committees (id, name, description) VALUES
('c1000000-0000-0000-0000-000000000001', 'IT Strategy Committee', 'Formulates and directs the strategic goals of the company IT landscape.'),
('c1000000-0000-0000-0000-000000000002', 'IT Steering Committee', 'Coordinates and operationalizes governance frameworks and handles approvals.'),
('c1000000-0000-0000-0000-000000000003', 'Risk Board', 'Reviews enterprise-level IT risk register maps, VAPT reports, and accepts/mitigates risks.');

-- Seed compliance frameworks
INSERT INTO frameworks (id, name, version, description) VALUES
('f1000000-0000-0000-0000-000000000001', 'ISO 27001', '2022', 'Information Security Management System (ISMS) framework for managing information security risks.'),
('f1000000-0000-0000-0000-000000000002', 'DPDP', '2023', 'Digital Personal Data Protection Act 2023 — India''s framework governing Data Fiduciaries and personal data rights.'),
('f1000000-0000-0000-0000-000000000003', 'SOC 2', 'Trust Services Criteria', 'System and Organization Controls for security, availability, processing integrity, confidentiality, and privacy.');

-- ISO 27001:2022 requirements
INSERT INTO framework_requirements (id, framework_id, section_code, title, description) VALUES
('f1100000-0000-0000-0000-000000000001', 'f1000000-0000-0000-0000-000000000001', 'A.5.1',  'Policies for information security',                    'Information security policy and topic-specific policies shall be defined, approved by management, published, communicated, and reviewed at planned intervals.'),
('f1100000-0000-0000-0000-000000000002', 'f1000000-0000-0000-0000-000000000001', 'A.8.20', 'Network security',                                     'Networks and network devices shall be secured, managed, and controlled to protect information in systems and applications.'),
('f1100000-0000-0000-0000-000000000004', 'f1000000-0000-0000-0000-000000000001', 'A.5.2',  'Information security roles and responsibilities',       'Information security roles and responsibilities shall be defined and allocated according to the organisation''s needs.'),
('f1100000-0000-0000-0000-000000000005', 'f1000000-0000-0000-0000-000000000001', 'A.5.15', 'Access control',                                       'Rules to control physical and logical access to information and other associated assets shall be established and implemented based on business and information security requirements.'),
('f1100000-0000-0000-0000-000000000006', 'f1000000-0000-0000-0000-000000000001', 'A.5.23', 'Information security for use of cloud services',       'Processes for acquisition, use, management, and exit from cloud services shall be established in accordance with the organisation''s information security requirements.'),
('f1100000-0000-0000-0000-000000000007', 'f1000000-0000-0000-0000-000000000001', 'A.6.3',  'Information security awareness, education and training','Personnel and relevant interested parties shall receive appropriate information security awareness, education and training.'),
('f1100000-0000-0000-0000-000000000008', 'f1000000-0000-0000-0000-000000000001', 'A.7.1',  'Physical security perimeters',                         'Security perimeters shall be defined and used to protect areas that contain information and other associated assets.'),
('f1100000-0000-0000-0000-000000000009', 'f1000000-0000-0000-0000-000000000001', 'A.8.2',  'Privileged access rights',                             'The allocation and use of privileged access rights shall be restricted and managed.'),
('f1100000-0000-0000-0000-000000000010', 'f1000000-0000-0000-0000-000000000001', 'A.8.5',  'Secure authentication',                                'Secure authentication technologies and procedures shall be implemented based on information access restrictions and the access control policy.'),
('f1100000-0000-0000-0000-000000000011', 'f1000000-0000-0000-0000-000000000001', 'A.8.7',  'Protection against malware',                           'Protection against malware shall be implemented and supported by appropriate user awareness.'),
('f1100000-0000-0000-0000-000000000012', 'f1000000-0000-0000-0000-000000000001', 'A.8.8',  'Management of technical vulnerabilities',              'Information about technical vulnerabilities shall be obtained, the organisation''s exposure evaluated, and appropriate measures taken.'),
('f1100000-0000-0000-0000-000000000013', 'f1000000-0000-0000-0000-000000000001', 'A.8.15', 'Logging',                                              'Logs that record activities, exceptions, faults, and other relevant events shall be produced, stored, protected, and analysed.');

-- DPDP 2023 requirements
INSERT INTO framework_requirements (id, framework_id, section_code, title, description) VALUES
('f2000000-0000-0000-0000-000000000001', 'f1000000-0000-0000-0000-000000000002', 'Sec 4',  'Grounds for processing personal data',            'Personal data may be processed only for a lawful purpose for which a Data Principal has given consent or for certain legitimate uses specified under the Act.'),
('f2000000-0000-0000-0000-000000000002', 'f1000000-0000-0000-0000-000000000002', 'Sec 5',  'Notice to Data Principals',                        'The Data Fiduciary must provide a notice with details of personal data collected, purpose of processing, and the rights of the Data Principal before or at the time of collecting personal data.'),
('f2000000-0000-0000-0000-000000000003', 'f1000000-0000-0000-0000-000000000002', 'Sec 6',  'Consent management',                              'Consent shall be free, specific, informed, unconditional, and unambiguous with a clear affirmative action, and limited to personal data necessary for the specified purpose.'),
('f2000000-0000-0000-0000-000000000004', 'f1000000-0000-0000-0000-000000000002', 'Sec 8',  'General obligations of Data Fiduciary',           'Data Fiduciary shall ensure accuracy and completeness of personal data, implement appropriate technical and organisational measures, and retain data only for the period necessary.'),
('f2000000-0000-0000-0000-000000000005', 'f1000000-0000-0000-0000-000000000002', 'Sec 9',  'Processing of personal data of children',         'Data Fiduciary shall not process personal data of a child in a manner detrimental to the child and shall obtain verifiable consent from a parent or legal guardian before processing.'),
('f2000000-0000-0000-0000-000000000006', 'f1000000-0000-0000-0000-000000000002', 'Sec 11', 'Right to access information about personal data', 'Data Principal shall have the right to obtain a summary of personal data being processed and the processing activities undertaken by the Data Fiduciary.'),
('f2000000-0000-0000-0000-000000000007', 'f1000000-0000-0000-0000-000000000002', 'Sec 12', 'Right to correction and erasure',                 'Data Principal shall have the right to correction, completion, updating, and erasure of personal data, and the Data Fiduciary shall effect such correction or erasure.'),
('f2000000-0000-0000-0000-000000000008', 'f1000000-0000-0000-0000-000000000002', 'Sec 13', 'Right to grievance redressal',                    'Data Principal shall have the right to readily available means of grievance redressal provided by the Data Fiduciary, with responses within prescribed timelines.');

-- SOC 2 Trust Services Criteria requirements
INSERT INTO framework_requirements (id, framework_id, section_code, title, description) VALUES
('f3000000-0000-0000-0000-000000000001', 'f1000000-0000-0000-0000-000000000003', 'CC1.1', 'Control environment — integrity and ethical values', 'The entity demonstrates a commitment to integrity and ethical values through the actions of those in governance and management.'),
('f3000000-0000-0000-0000-000000000002', 'f1000000-0000-0000-0000-000000000003', 'CC2.1', 'Information and communication',                      'The entity obtains or generates and uses relevant, quality information to support the functioning of internal control.'),
('f3000000-0000-0000-0000-000000000003', 'f1000000-0000-0000-0000-000000000003', 'CC3.1', 'Risk assessment — specification of objectives',      'The entity specifies objectives with sufficient clarity to enable the identification and assessment of risks relating to objectives.'),
('f3000000-0000-0000-0000-000000000004', 'f1000000-0000-0000-0000-000000000003', 'CC5.1', 'Control activities — design and selection',          'The entity selects and develops control activities that contribute to the mitigation of risks to the achievement of objectives to acceptable levels.'),
('f3000000-0000-0000-0000-000000000005', 'f1000000-0000-0000-0000-000000000003', 'CC6.1', 'Logical and physical access controls',               'The entity implements logical access security software, infrastructure, and architectures over protected information assets to protect them from security events.'),
('f3000000-0000-0000-0000-000000000006', 'f1000000-0000-0000-0000-000000000003', 'CC6.3', 'Role-based access management',                      'The entity authorises, modifies, or removes access based on roles and responsibilities, ensuring access is appropriate and current.'),
('f3000000-0000-0000-0000-000000000007', 'f1000000-0000-0000-0000-000000000003', 'CC6.6', 'Restriction of access to protected information',    'The entity implements logical access security measures to protect against threats from sources outside its system boundaries.'),
('f3000000-0000-0000-0000-000000000008', 'f1000000-0000-0000-0000-000000000003', 'CC7.1', 'System monitoring',                                 'The entity uses detection and monitoring procedures to identify changes to configurations or the environment and new vulnerabilities, and to detect security events.'),
('f3000000-0000-0000-0000-000000000009', 'f1000000-0000-0000-0000-000000000003', 'CC8.1', 'Change management',                                 'The entity authorises, designs, develops, configures, documents, tests, and approves changes to infrastructure, data, software, and procedures.'),
('f3000000-0000-0000-0000-000000000010', 'f1000000-0000-0000-0000-000000000003', 'A1.1',  'Availability — capacity and performance',            'The entity maintains, monitors, and evaluates current processing capacity and use of system components to manage capacity demand and enable implementation of additional capacity.');

-- Seed regulators
INSERT INTO compliance_regulators (id, name, description) VALUES
('c1100000-0000-0000-0000-000000000001', 'Central Bank / RBI', 'National banking and financial regulator overseeing financial cybersecurity directives.'),
('c1100000-0000-0000-0000-000000000002', 'DPA (Data Protection Authority)', 'Regulates corporate compliance under personal data protection guidelines.');

