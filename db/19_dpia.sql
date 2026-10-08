-- ==========================================
-- MODULE: DPIA (Data Protection Impact Assessment) under Data Governance
-- Depends on: 01_init.sql (users), 02_data_register.sql (data_assets), 18_data_governance.sql
-- Uses the existing 'data' role_permissions module; no new module row is required.
-- See docs/dpia-plan.md.
-- ==========================================

-- Two risk signals that are not derivable from existing columns (volume_estimate is free text).
ALTER TABLE data_assets
    ADD COLUMN is_large_scale BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN involves_profiling BOOLEAN NOT NULL DEFAULT FALSE;

-- One DPIA per processing activity (a data_assets row, as in the RoPA export).
CREATE TABLE dpias (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    data_asset_id UUID NOT NULL UNIQUE REFERENCES data_assets(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','UNDER_REVIEW','APPROVED','SUBMITTED')),
    inherent_risk VARCHAR(10) NOT NULL CHECK (inherent_risk IN ('LOW','MEDIUM','HIGH')),
    residual_risk VARCHAR(10) NOT NULL CHECK (residual_risk IN ('LOW','MEDIUM','HIGH')),
    risk_score INT NOT NULL DEFAULT 0,
    risk_factors JSONB NOT NULL DEFAULT '[]'::jsonb,   -- derived snapshot: [{code,label,reference,points}]
    necessity_note TEXT,
    proportionality_note TEXT,
    mitigations TEXT,
    residual_note TEXT,
    edited BOOLEAN NOT NULL DEFAULT FALSE,             -- true once a user edits notes; re-generate then refreshes only derived risk fields
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    submitted_at TIMESTAMP WITH TIME ZONE,
    board_reference VARCHAR(255),
    generated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dpias_status ON dpias(status);
CREATE INDEX idx_dpias_inherent ON dpias(inherent_risk);
