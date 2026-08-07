-- ==========================================
-- MODULE: SUPERVISOR APPROVAL WORKFLOW
-- Depends on: 01_init.sql (users, role_permissions, helpdesk_tickets, change_requests)
--             03_selfservice.sql (USER role, selfservice module)
-- ==========================================

-- 1. Widen role constraint to add SUPERVISOR: a business-side manager who
-- provisions/manages a set of USERs and, when business_settings enables it,
-- approves their tickets/change requests before IT/GRC sees them.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN','GRC_OFFICER','IT_STAFF','USER','SUPERVISOR'));

-- 2. Single-level hierarchy: a USER's supervisor. NULL = no supervisor
-- assigned. Only meaningful when role='USER'; enforced in application code
-- (Platform.java / Supervisor.java), not via CHECK/trigger.
ALTER TABLE users ADD COLUMN supervisor_id UUID REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX idx_users_supervisor ON users(supervisor_id);

-- 3. Deployment-wide business settings. This app is single-tenant per
-- deployment (one instance = one business), so "business configuration" is a
-- simple key/value table an ADMIN edits, not a multi-tenant org table.
CREATE TABLE business_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Defaults to 'false' so every existing deployment behaves exactly as today
-- until an ADMIN opts in via Platform > Business Settings.
INSERT INTO business_settings (setting_key, setting_value) VALUES
    ('require_supervisor_approval', 'false');

-- 4. Approval workflow gate. NOT_REQUIRED is the default, so every existing
-- row and every write path that doesn't opt into the flow is unaffected.
-- approver_id here is the Supervisor pre-approval gate — distinct from
-- change_requests.compliance_approver_id, which is a separate downstream
-- compliance sign-off field.
ALTER TABLE helpdesk_tickets ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK (approval_status IN ('NOT_REQUIRED','PENDING','APPROVED','REJECTED'));
ALTER TABLE helpdesk_tickets ADD COLUMN approver_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE helpdesk_tickets ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE helpdesk_tickets ADD COLUMN rejection_reason TEXT;

ALTER TABLE change_requests ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK (approval_status IN ('NOT_REQUIRED','PENDING','APPROVED','REJECTED'));
ALTER TABLE change_requests ADD COLUMN approver_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE change_requests ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE change_requests ADD COLUMN rejection_reason TEXT;

CREATE INDEX idx_helpdesk_tickets_approval ON helpdesk_tickets(approval_status);
CREATE INDEX idx_change_requests_approval ON change_requests(approval_status);

-- 5. Seed role_permissions for SUPERVISOR. Supervisors operate out of the
-- self-service portal like USER, plus own-team management/approvals via the
-- dedicated /api/v1/supervisor service; row-level scoping (own reports only)
-- is enforced in application code, not by this module-level grant.
INSERT INTO role_permissions (role, module, permission_level) VALUES
('SUPERVISOR', 'platform',    'NONE'),
('SUPERVISOR', 'governance',  'NONE'),
('SUPERVISOR', 'risks',       'NONE'),
('SUPERVISOR', 'controls',    'NONE'),
('SUPERVISOR', 'evidence',    'NONE'),
('SUPERVISOR', 'operations',  'NONE'),
('SUPERVISOR', 'incidents',   'NONE'),
('SUPERVISOR', 'reports',     'NONE'),
('SUPERVISOR', 'helpdesk',    'NONE'),
('SUPERVISOR', 'selfservice', 'WRITE');
