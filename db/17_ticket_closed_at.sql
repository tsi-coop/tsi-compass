-- ==========================================
-- MODULE: HELPDESK TICKET CLOSED DATE
-- Depends on: 01_init.sql (helpdesk_tickets)
-- ==========================================

-- Tracks when a ticket was actually marked CLOSED, so the console can filter
-- by "closed date" the same way it already filters by created date.
-- Maintained in application code (Operations.java updateTicket): set when
-- status transitions to CLOSED, cleared if a closed ticket is reopened.
-- NULL for any ticket never closed since this column existed - there is no
-- reliable historical status-change record to backfill from, so existing
-- CLOSED tickets simply have no closed_at until they're touched again.
ALTER TABLE helpdesk_tickets ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;
