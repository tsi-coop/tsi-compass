-- ==========================================
-- MODULE: DATA GOVERNANCE (phase 2)
-- Depends on: 01_init.sql (users, role_permissions), 02_data_register.sql (data_assets, vendors),
--             Controls (frameworks, framework_requirements)
-- Uses the existing 'data' role_permissions module seeded in 02_data_register.sql; no new
-- module row is required since every page here sits under the same Data Governance section.
-- ==========================================

-- 2a. Data Principals: who the data is about. Org-editable, not a fixed enum (a hospital
-- needs Patient, a school needs Student), so this is a small master table, not a CHECK list.
CREATE TABLE data_principals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE data_asset_principals (
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    principal_id UUID NOT NULL REFERENCES data_principals(id) ON DELETE CASCADE,
    PRIMARY KEY (data_asset_id, principal_id)
);

INSERT INTO data_principals (name, description) VALUES
    ('Customer', 'End customers or end users of the org''s own products/services'),
    ('Employee', 'Current or former staff'),
    ('Patient', 'Individuals receiving care, where applicable'),
    ('Vendor Contact', 'Named individuals at third-party vendors/suppliers'),
    ('Prospect', 'Leads and prospective customers, not yet onboarded');

-- 2b. Purpose, retention, recipients, access
ALTER TABLE data_assets
    ADD COLUMN purpose TEXT,
    ADD COLUMN processing_basis VARCHAR(50) CHECK (processing_basis IN ('CONSENT','LEGITIMATE_USE','CONTRACTUAL','LEGAL_OBLIGATION','OTHER')),
    ADD COLUMN retention_period VARCHAR(100),
    ADD COLUMN deletion_trigger VARCHAR(255);

CREATE TABLE data_asset_recipients (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    recipient_type VARCHAR(30) NOT NULL CHECK (recipient_type IN ('VENDOR','REGULATOR','PARTNER','GROUP_ENTITY','OTHER')),
    linked_vendor_id UUID REFERENCES vendors(id) ON DELETE SET NULL,
    recipient_name VARCHAR(255),
    purpose_of_sharing TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE data_asset_access (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    data_asset_id UUID NOT NULL REFERENCES data_assets(id) ON DELETE CASCADE,
    role VARCHAR(100),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    access_level VARCHAR(20) DEFAULT 'READ' CHECK (access_level IN ('READ','WRITE','ADMIN')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (role IS NOT NULL OR user_id IS NOT NULL)
);

-- 2c. Gap assessment: link data assets to the framework requirements they satisfy.
-- Same shape as control_requirement_mappings, deliberately.
CREATE TABLE data_asset_requirement_mappings (
    data_asset_id UUID REFERENCES data_assets(id) ON DELETE CASCADE,
    requirement_id UUID REFERENCES framework_requirements(id) ON DELETE CASCADE,
    PRIMARY KEY (data_asset_id, requirement_id)
);

-- 2d. Data flow mapping: directed movement of data between two points, either of which can
-- be a registered data asset or a free-text label for something the org hasn't formally
-- registered (a personal WhatsApp, a personal email account) - capturing that gap is the point.
CREATE TABLE data_flows (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    source_data_asset_id UUID REFERENCES data_assets(id) ON DELETE SET NULL,
    source_label VARCHAR(255),
    destination_data_asset_id UUID REFERENCES data_assets(id) ON DELETE SET NULL,
    destination_label VARCHAR(255),
    channel VARCHAR(50) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','API','FILE_TRANSFER','CLOUD_SYNC','PHYSICAL_MEDIA','PRINTOUT','OTHER')),
    is_approved_channel BOOLEAN DEFAULT FALSE,
    data_elements TEXT,
    frequency VARCHAR(50),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    description TEXT,
    is_cross_border BOOLEAN NOT NULL DEFAULT FALSE,
    destination_country VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_data_asset_id IS NOT NULL OR source_label IS NOT NULL),
    CHECK (destination_data_asset_id IS NOT NULL OR destination_label IS NOT NULL)
);

CREATE INDEX idx_data_flows_source ON data_flows(source_data_asset_id);
CREATE INDEX idx_data_flows_destination ON data_flows(destination_data_asset_id);
CREATE INDEX idx_data_flows_channel ON data_flows(channel);

-- 2e. RoPA export: controller/processor role is the one field a RoPA needs that nothing
-- above already captures (data subject categories come from 2a; transfers from 2d).
ALTER TABLE data_assets
    ADD COLUMN controller_role VARCHAR(20) NOT NULL DEFAULT 'CONTROLLER' CHECK (controller_role IN ('CONTROLLER','PROCESSOR','JOINT'));

-- Merge Software/Crypto Inventory's RBAC into 'operations' (see InterceptingFilter's
-- SERVICE_MODULE_MAP and rbac.js). ADMIN/GRC_OFFICER/IT_STAFF levels for 'supplychain' and
-- 'operations' are already identical in 01_init.sql/08_supply_chain.sql, so this is a no-op
-- for effective access; the old 'supplychain' rows are left in place (unused, harmless) rather
-- than deleted, since role_permissions is small reference data and no code reads them anymore.
