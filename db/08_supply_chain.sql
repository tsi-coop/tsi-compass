-- ==========================================
-- MODULE: SUPPLY CHAIN (SBOM & CBOM)
-- Depends on: 01_init.sql (assets, role_permissions)
-- ==========================================

CREATE TABLE sbom_components (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    asset_id UUID REFERENCES assets(id) ON DELETE SET NULL,
    component_name VARCHAR(255) NOT NULL,
    version VARCHAR(100),
    component_type VARCHAR(30) NOT NULL CHECK (component_type IN
        ('LIBRARY','FRAMEWORK','OPERATING_SYSTEM','CONTAINER_IMAGE','APPLICATION','FIRMWARE','OTHER')),
    supplier VARCHAR(255),
    license VARCHAR(100),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sbom_components_asset ON sbom_components(asset_id);
CREATE INDEX idx_sbom_components_type  ON sbom_components(component_type);

CREATE TABLE cbom_components (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    asset_id UUID REFERENCES assets(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    crypto_type VARCHAR(30) NOT NULL CHECK (crypto_type IN
        ('ALGORITHM','CERTIFICATE','KEY','PROTOCOL','LIBRARY')),
    algorithm VARCHAR(100),
    key_size INT,
    protocol VARCHAR(50),
    certificate_expiry DATE,
    standard VARCHAR(100),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cbom_components_asset  ON cbom_components(asset_id);
CREATE INDEX idx_cbom_components_type   ON cbom_components(crypto_type);
CREATE INDEX idx_cbom_components_expiry ON cbom_components(certificate_expiry);

-- Seed permission matrix for the new 'supplychain' module (mirrors 'operations')
INSERT INTO role_permissions (role, module, permission_level) VALUES
('ADMIN',       'supplychain', 'ADMIN'),
('GRC_OFFICER', 'supplychain', 'READ'),
('IT_STAFF',    'supplychain', 'WRITE');
