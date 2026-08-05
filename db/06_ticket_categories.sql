-- ==========================================
-- MODULE: HELPDESK TICKET CATEGORIES
-- Depends on: 01_init.sql (helpdesk_tickets)
-- ==========================================

-- Admin-configurable lookup table. Unlike the CHECK-constrained enums used
-- elsewhere in this schema (status, priority, etc.), categories are meant to
-- change without a migration, so they live in their own table that an ADMIN
-- manages through the console (Platform & Access > Ticket Categories).
CREATE TABLE ticket_categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ticket_categories (name) VALUES
    ('Hardware'), ('Software'), ('Network'), ('Access Request'), ('Account / Password'), ('Other');

-- ON DELETE RESTRICT: categories are retired via is_active=false, never
-- deleted, so a ticket's category is never silently orphaned.
ALTER TABLE helpdesk_tickets ADD COLUMN category_id UUID REFERENCES ticket_categories(id) ON DELETE RESTRICT;
UPDATE helpdesk_tickets SET category_id = (SELECT id FROM ticket_categories WHERE name = 'Other') WHERE category_id IS NULL;
ALTER TABLE helpdesk_tickets ALTER COLUMN category_id SET NOT NULL;

CREATE INDEX idx_helpdesk_tickets_category ON helpdesk_tickets(category_id);
