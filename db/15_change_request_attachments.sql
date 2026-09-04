-- ==========================================
-- MODULE: CHANGE REQUEST ATTACHMENTS
-- Depends on: 01_init.sql (change_requests, users)
-- ==========================================

-- Same shape as ticket_attachments (14_ticket_attachments.sql) — same
-- base64-in/base64-out file storage pattern (see SelfService.java
-- uploadTicketAttachment/getTicketAttachment), just scoped to change_requests
-- instead of helpdesk_tickets.
CREATE TABLE change_request_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    change_request_id UUID NOT NULL REFERENCES change_requests(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_request_attachments_cr ON change_request_attachments(change_request_id);
