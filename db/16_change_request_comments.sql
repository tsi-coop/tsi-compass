-- ==========================================
-- MODULE: CHANGE REQUEST COMMENTS
-- Depends on: 01_init.sql (change_requests, users)
-- ==========================================

-- An append-only comment thread on a change request (who said what, when),
-- distinct from the single-value fields (stage/status) that already exist.
-- Modeled after ticket_attachments/change_request_attachments in shape and
-- convention, but for text comments rather than files.
CREATE TABLE change_request_comments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    change_request_id UUID NOT NULL REFERENCES change_requests(id) ON DELETE CASCADE,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    comment TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_request_comments_cr ON change_request_comments(change_request_id);
