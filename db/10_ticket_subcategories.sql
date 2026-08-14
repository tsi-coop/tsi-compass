-- ==========================================
-- MODULE: HELPDESK TICKET SUBCATEGORIES
-- Depends on: 01_init.sql (helpdesk_tickets), 06_ticket_categories.sql (ticket_categories)
-- ==========================================

-- Second-level lookup under a ticket_category ("system") — e.g. category
-- "ERP" might have subcategories "Login Issue", "Report Generation", "Access
-- Request". Admin-managed the same way as categories (Platform & Access >
-- Ticket Categories): retired via is_active=false, never deleted.
CREATE TABLE ticket_subcategories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    category_id UUID NOT NULL REFERENCES ticket_categories(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (category_id, name)
);

CREATE INDEX idx_ticket_subcategories_category ON ticket_subcategories(category_id);

-- Optional and nullable: not every category has subcategories defined, and
-- existing tickets predate this column. ON DELETE RESTRICT mirrors
-- helpdesk_tickets.category_id — a subcategory is retired, never deleted, so
-- a ticket's subcategory is never silently orphaned.
ALTER TABLE helpdesk_tickets ADD COLUMN subcategory_id UUID REFERENCES ticket_subcategories(id) ON DELETE RESTRICT;
CREATE INDEX idx_helpdesk_tickets_subcategory ON helpdesk_tickets(subcategory_id);
