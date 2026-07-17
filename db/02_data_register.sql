-- ==========================================
-- MODULE: DATA REGISTER
-- Depends on: 01_init.sql (users, assets, vendors, role_permissions)
-- ==========================================

CREATE TABLE data_assets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    system_type VARCHAR(50) NOT NULL CHECK (system_type IN ('APPLICATION','DATABASE','FILE_SHARE','SAAS','EMAIL','PHYSICAL','OTHER')),
    category VARCHAR(50) NOT NULL CHECK (category IN ('PII','FINANCIAL','HEALTH','EMPLOYEE','INTELLECTUAL_PROPERTY','PUBLIC','OTHER')),
    sensitivity VARCHAR(20) NOT NULL DEFAULT 'INTERNAL' CHECK (sensitivity IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    linked_asset_id UUID REFERENCES assets(id) ON DELETE SET NULL,
    linked_vendor_id UUID REFERENCES vendors(id) ON DELETE SET NULL,
    location VARCHAR(255),
    volume_estimate VARCHAR(100),
    description TEXT,
    discovery_status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED' CHECK (discovery_status IN ('DISCOVERED','CLASSIFIED','REVIEWED')),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    last_reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_data_assets_sensitivity ON data_assets(sensitivity);
CREATE INDEX idx_data_assets_category ON data_assets(category);

-- Seed permission matrix for the new 'data' module
INSERT INTO role_permissions (role, module, permission_level) VALUES
('ADMIN',        'data', 'ADMIN'),
('GRC_OFFICER',  'data', 'WRITE'),
('IT_STAFF',     'data', 'NONE');
