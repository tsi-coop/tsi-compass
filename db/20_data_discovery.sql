-- ==========================================
-- MODULE: DATA DISCOVERY under Data Governance
-- Depends on: 01_init.sql (users), 02_data_register.sql (data_assets), 18_data_governance.sql
-- Uses the existing 'data' role_permissions module; no new module row is required.
-- See docs/guides/data-governance-guide.md and docs/architecture/system-design.md section 5.2.
--
-- v1 is an offline schema import: the user uploads the output of an information_schema query
-- and columns are matched against discovery_rules by name. No connection to the source system
-- is made, and no data values are read or stored.
-- ==========================================

CREATE TABLE discovery_sources (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    source_type VARCHAR(20) NOT NULL DEFAULT 'OTHER' CHECK (source_type IN ('POSTGRES','MYSQL','MSSQL','ORACLE','OTHER')),
    location VARCHAR(255),                             -- free text, e.g. host / database name, copied to the data asset
    description TEXT,
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_scanned_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE discovery_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_id UUID NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
    mode VARCHAR(20) NOT NULL DEFAULT 'SCHEMA_IMPORT' CHECK (mode IN ('SCHEMA_IMPORT','LIVE_METADATA','SAMPLED')),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    tables_scanned INT NOT NULL DEFAULT 0,
    columns_scanned INT NOT NULL DEFAULT 0,
    findings_new INT NOT NULL DEFAULT 0,
    findings_updated INT NOT NULL DEFAULT 0,
    findings_unchanged INT NOT NULL DEFAULT 0,         -- already accepted or rejected, left as they were
    error_message TEXT,
    started_by UUID REFERENCES users(id) ON DELETE SET NULL,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_discovery_runs_source ON discovery_runs(source_id);

-- Column-level findings. One per column: when several rules match, the highest priority one wins.
CREATE TABLE discovery_findings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_id UUID NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
    run_id UUID REFERENCES discovery_runs(id) ON DELETE SET NULL,
    schema_name VARCHAR(255) NOT NULL DEFAULT '',
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(100),
    detected_type VARCHAR(30) NOT NULL,                -- rule code, e.g. AADHAAR
    suggested_category VARCHAR(50) NOT NULL,           -- a data_assets.category value
    suggested_sensitivity VARCHAR(20) NOT NULL,        -- a data_assets.sensitivity value
    confidence VARCHAR(10) NOT NULL CHECK (confidence IN ('HIGH','MEDIUM','LOW')),
    match_basis VARCHAR(20) NOT NULL DEFAULT 'COLUMN_NAME' CHECK (match_basis IN ('COLUMN_NAME','VALUE_PATTERN')),
    status VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','ACCEPTED','REJECTED')),
    data_asset_id UUID REFERENCES data_assets(id) ON DELETE SET NULL,
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    first_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_id, schema_name, table_name, column_name)
);

CREATE INDEX idx_discovery_findings_status ON discovery_findings(status);
CREATE INDEX idx_discovery_findings_asset ON discovery_findings(data_asset_id);

-- Detection rules live in the table so they can be tuned without a release.
-- name_regex is matched (case-insensitive, find) against the column name after normalisation:
-- camelCase is split and everything non-alphanumeric becomes '_' (so aadhaarNo -> aadhaar_no).
-- Lowest priority number wins when several rules match one column.
CREATE TABLE discovery_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(30) NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL,
    name_regex TEXT NOT NULL,
    exclude_regex TEXT,
    category VARCHAR(50) NOT NULL,
    sensitivity VARCHAR(20) NOT NULL,
    base_confidence VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (base_confidence IN ('HIGH','MEDIUM','LOW')),
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- India-focused (DPDP). A name match alone is at most MEDIUM: HIGH needs value checks, which are not part of v1.
INSERT INTO discovery_rules (code, label, name_regex, exclude_regex, category, sensitivity, base_confidence, priority) VALUES
('AADHAAR',    'Aadhaar number',          'aadhaar|aadhar|adhaar|adhar|(^|_)uidai(_|$)',                                  NULL, 'PII',       'RESTRICTED',   'MEDIUM', 10),
('PAN',        'PAN',                     '(^|_)pan(_|$)',                                                                NULL, 'PII',       'RESTRICTED',   'MEDIUM', 20),
('VOTER_PASSPORT_DL', 'Voter ID / passport / driving licence', 'voter|(^|_)epic(_|$)|passport|driving_?licen[cs]e|(^|_)dl_?(no|num|number)(_|$)', NULL, 'PII', 'RESTRICTED', 'MEDIUM', 30),
('HEALTH',     'Health information',      'diagnos|prescription|medical|patient|blood_?(group|type)|(^|_)icd(_|$)',        NULL, 'HEALTH',    'RESTRICTED',   'MEDIUM', 40),
('BIOMETRIC',  'Biometric data',          'fingerprint|biometric|(^|_)iris(_|$)|face_?(id|template|print)',                NULL, 'PII',       'RESTRICTED',   'MEDIUM', 50),
('BANK_ACCOUNT','Bank account / IFSC',    'bank_?acc|acc(ount|t)_?(no|num|number)|ifsc',                                  NULL, 'FINANCIAL', 'RESTRICTED',   'MEDIUM', 60),
('GSTIN',      'GSTIN',                   'gstin|(^|_)gst_?(no|num|number|id)(_|$)',                                      NULL, 'FINANCIAL', 'CONFIDENTIAL', 'MEDIUM', 70),
('UPI',        'UPI ID',                  '(^|_)upi(_|$)',                                                                NULL, 'FINANCIAL', 'CONFIDENTIAL', 'MEDIUM', 80),
('MOBILE',     'Mobile / phone number',   'mobile|phone|msisdn|contact_?(no|num|number)|(^|_)cell(_|$)',                  NULL, 'PII',       'CONFIDENTIAL', 'MEDIUM', 90),
('EMAIL',      'Email address',           'e_?mail',                                                                      NULL, 'PII',       'CONFIDENTIAL', 'MEDIUM', 100),
('DOB',        'Date of birth',           '(^|_)dob(_|$)|birth',                                                          NULL, 'PII',       'CONFIDENTIAL', 'MEDIUM', 110),
('NAME',       'Person name',             '(^|_)(first|last|middle|full|father|mother|spouse)_?name(_|$)|(^|_)surname(_|$)', NULL, 'PII',   'INTERNAL',     'LOW',    120),
('ADDRESS',    'Postal address / PIN code','address|pin_?code|postal_?code|(^|_)zip(_|$)',                                '(^|_)(ip|mac|url|web|server|host|smtp|wallet|contract)_?address', 'PII', 'INTERNAL', 'LOW', 130);
