-- ==========================================
-- MODULE: VENDOR PORTAL (Third-Party Risk)
-- Depends on: 01_init.sql (users, role_permissions), 03/07 (role constraint), 21_tprm.sql
-- Vendor contacts sign in as role VENDOR at /vendorportal/ to answer the security questionnaires
-- GRC sends them. The portal is off unless TSI_VENDOR_PORTAL_ENABLED=true.
-- See docs/tprm-vendor-portal-spec.md.
-- ==========================================

-- 1. New role. A VENDOR user belongs to exactly one vendor; the application requires vendor_id for the role
--    (same approach as supervisor_id), and every portal call resolves the vendor from the signed-in user.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN','GRC_OFFICER','IT_STAFF','USER','SUPERVISOR','VENDOR'));
ALTER TABLE users ADD COLUMN vendor_id UUID REFERENCES vendors(id) ON DELETE SET NULL;
CREATE INDEX idx_users_vendor ON users(vendor_id);

-- 2. Request state on the assessment. status (DRAFT..COMPLETED) is the assessor's view; portal_status is
--    the vendor-facing view of the request. NOT_SENT means the vendor cannot see it.
ALTER TABLE tprm_assessments
    ADD COLUMN portal_status VARCHAR(20) NOT NULL DEFAULT 'NOT_SENT'
        CHECK (portal_status IN ('NOT_SENT','SENT','IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED')),
    ADD COLUMN sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN sent_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN portal_due_date DATE,
    ADD COLUMN submitted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN submitted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN declaration_accepted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_tprm_assessments_portal ON tprm_assessments(portal_status);

-- 3. Who answered, and what the vendor originally said if the assessor overrode it.
ALTER TABLE tprm_answers
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'ASSESSOR' CHECK (source IN ('ASSESSOR','VENDOR')),
    ADD COLUMN original_answer VARCHAR(10),
    ADD COLUMN original_comment TEXT,
    ADD COLUMN returned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN return_note TEXT;

-- 4. Evidence uploaded by the vendor. Same storage shape and SHA-256 checksum as other attachments.
ALTER TABLE tprm_documents
    ADD COLUMN uploaded_by_vendor BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN question_id UUID REFERENCES tprm_questions(id) ON DELETE SET NULL,
    ADD COLUMN file_size BIGINT;

-- 5. Permissions: only VENDOR (and ADMIN) get the new module; VENDOR is NONE everywhere else.
INSERT INTO role_permissions (role, module, permission_level) VALUES
('ADMIN',       'vendorportal', 'ADMIN'),
('GRC_OFFICER', 'vendorportal', 'NONE'),
('IT_STAFF',    'vendorportal', 'NONE'),
('USER',        'vendorportal', 'NONE'),
('SUPERVISOR',  'vendorportal', 'NONE'),
('VENDOR',      'vendorportal', 'WRITE');

INSERT INTO role_permissions (role, module, permission_level)
SELECT 'VENDOR', m, 'NONE' FROM (SELECT DISTINCT module AS m FROM role_permissions WHERE module <> 'vendorportal') x
ON CONFLICT (role, module) DO NOTHING;
