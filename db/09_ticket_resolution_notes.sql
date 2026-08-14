-- ==========================================
-- MODULE: HELPDESK TICKET RESOLUTION NOTES
-- Depends on: 01_init.sql (helpdesk_tickets)
-- ==========================================

-- Free-text notes IT/GRC record when working/closing a ticket, so the
-- requester (and their Supervisor, if any) can see how it was resolved from
-- the self-service portal. Distinct from description (what the requester
-- reported) and rejection_reason (why a Supervisor declined it pre-IT).
ALTER TABLE helpdesk_tickets ADD COLUMN resolution_notes TEXT;
