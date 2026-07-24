-- ==========================================
-- MODULE: SELF-SERVICE PORTAL
-- Depends on: 01_init.sql (users, role_permissions, helpdesk_tickets,
--             change_requests, policies, policy_attestations,
--             campaign_enrollments)
-- ==========================================

-- Widen the role constraint to allow general org employees ('USER').
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN','GRC_OFFICER','IT_STAFF','USER'));

-- Seed permission matrix for the new 'selfservice' module.
-- USER gets WRITE (submit tickets/changes, acknowledge policies, complete trainings).
-- IT_STAFF/GRC_OFFICER keep triaging through the existing ops/governance consoles, not this portal.
INSERT INTO role_permissions (role, module, permission_level) VALUES
('ADMIN',        'selfservice', 'ADMIN'),
('GRC_OFFICER',  'selfservice', 'NONE'),
('IT_STAFF',     'selfservice', 'NONE'),
('USER',         'selfservice', 'WRITE');
