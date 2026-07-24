-- ==========================================
-- MODULE: ADMIN NOTIFICATIONS
-- Depends on: 01_init.sql (users, role_permissions)
-- ==========================================

-- One row per event (new registration, ticket, change request, affirmation, ...).
-- target_roles is computed at emit time from role_permissions (WRITE/ADMIN on the
-- owning module), not hardcoded per event type, so it tracks the permission
-- matrix if that ever changes.
--
-- Self-service-facing events use target_user_id instead of target_roles when
-- the notification concerns one specific employee's own request (e.g. their
-- change request's status changed) rather than a role-wide broadcast (e.g. a
-- new policy needing everyone's attestation) — see Notification.emitToUser
-- vs. Notification.emitToRole.
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT,
    link_url VARCHAR(255) NOT NULL,
    entity_id UUID,
    target_roles TEXT[] NOT NULL DEFAULT '{}',
    target_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (target_roles <> '{}' OR target_user_id IS NOT NULL)
);

-- Read state is per-user, not per-notification: two admins seeing the same
-- broadcast notification must be able to dismiss it independently.
CREATE TABLE notification_reads (
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX idx_notifications_created_at ON notifications(created_at DESC);
CREATE INDEX idx_notifications_target_roles ON notifications USING GIN(target_roles);
CREATE INDEX idx_notifications_target_user_id ON notifications(target_user_id);
