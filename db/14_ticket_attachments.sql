-- ==========================================
-- MODULE: HELPDESK TICKET ATTACHMENTS
-- Depends on: 01_init.sql (helpdesk_tickets, users)
-- ==========================================

-- Same shape as incident_documents/kb_documents/campaign_documents
-- (01_init.sql), so uploads reuse the existing base64-in/base64-out file
-- storage pattern (see Incidents.java addDocument/getDocument).
CREATE TABLE ticket_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ticket_id UUID NOT NULL REFERENCES helpdesk_tickets(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ticket_attachments_ticket ON ticket_attachments(ticket_id);
