-- ==========================================
-- MODULE: MULTI-LEVEL MANAGER HIERARCHY
-- Depends on: 01_init.sql (users)
--             07_supervisor_role.sql (SUPERVISOR role, users.supervisor_id)
-- ==========================================

-- Manager-1..Manager-5 levels. Only meaningful when role='SUPERVISOR';
-- enforced in application code (Platform.java), not via CHECK/trigger,
-- same convention as users.supervisor_id.
--
-- users.supervisor_id (added in 07_supervisor_role.sql) is reused unchanged
-- as the generic "reports to" edge at every level: a USER's supervisor_id is
-- their Manager-1, a Manager-1's supervisor_id is the Manager-2 they report
-- to, and so on up to Manager-5. No new FK column is needed.
--
-- Cycle prevention is structural rather than a runtime check: application
-- code only ever allows a manager's supervisor_id to point at another
-- SUPERVISOR whose manager_level is exactly one higher, and manager_level is
-- capped at 5, so a cycle can never form.
ALTER TABLE users ADD COLUMN manager_level SMALLINT CHECK (manager_level BETWEEN 1 AND 5);

-- Backfill: every existing SUPERVISOR becomes a Manager-1 with no upline
-- (supervisor_id already NULL for them), which is exactly today's behavior.
-- This is the only data change in this migration; every other flow is
-- unaffected until an ADMIN explicitly assigns levels 2-5 on the new
-- Platform > Managers screen.
UPDATE users SET manager_level = 1 WHERE role = 'SUPERVISOR';

-- Supports the new Managers admin screen's listing/filtering by level.
CREATE INDEX idx_users_manager_level ON users(manager_level) WHERE role = 'SUPERVISOR';
