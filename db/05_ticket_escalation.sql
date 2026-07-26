-- Prevent a helpdesk ticket from being escalated to more than one incident
-- (the ticket_escalation_id FK on incidents already exists in 01_init.sql; this
-- just enforces uniqueness and speeds up the "already escalated?" lookup).
CREATE UNIQUE INDEX IF NOT EXISTS uq_incidents_ticket_escalation
    ON incidents(ticket_escalation_id) WHERE ticket_escalation_id IS NOT NULL;
