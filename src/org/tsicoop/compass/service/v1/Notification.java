package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Notification bell shared by the admin console and the self-service portal:
 * new user registrations, helpdesk tickets, change requests, policy affirmations,
 * published policies, assigned training, etc. Recipients are either a role
 * broadcast or one specific user:
 *  - {@link #emit} — computed at emit time from role_permissions (any role with
 *    WRITE/ADMIN on the owning module); used for admin-facing events.
 *  - {@link #emitToRole} — a fixed literal role (e.g. "USER"), independent of the
 *    module permission matrix; used for self-service-wide broadcasts like a newly
 *    published policy or a newly assigned training campaign.
 *  - {@link #emitToUser} — one specific employee; used when an event concerns
 *    only their own record, e.g. their change request's status changed.
 *
 * Read state is stored per (notification, user) in notification_reads so
 * multiple recipients sharing a broadcast notification can dismiss it independently.
 * list_notifications / mark_notification_read / mark_all_notifications_read all
 * scope strictly to the caller's own role and id from the verified auth token —
 * client input never selects a different recipient.
 */
public class Notification implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI());
                return;
            }

            String role = InputProcessor.getRole(req);
            UUID userId = InputProcessor.getAuthenticatedUserId(req);
            if (role == null || userId == null) {
                OutputProcessor.errorResponse(res, 401, "Unauthorized", "Could not resolve authenticated user", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "list_notifications":
                    OutputProcessor.send(res, 200, listNotifications(role, userId));
                    break;
                case "mark_notification_read":
                    markRead(req, res, input, userId);
                    break;
                case "mark_all_notifications_read":
                    markAllRead(role, userId);
                    OutputProcessor.send(res, 200, successResult("All notifications marked read"));
                    break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function: " + func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return "POST".equalsIgnoreCase(method);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject successResult(String message) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JSONObject listNotifications(String role, UUID userId) throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        JSONArray notifications = new JSONArray();
        long unreadCount = 0L;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT n.id::text, n.event_type, n.title, n.message, n.link_url, n.entity_id::text, " +
                "n.created_at, (nr.user_id IS NOT NULL) AS is_read " +
                "FROM notifications n " +
                "LEFT JOIN notification_reads nr ON nr.notification_id = n.id AND nr.user_id = ? " +
                "WHERE ? = ANY(n.target_roles) OR n.target_user_id = ? " +
                "ORDER BY n.created_at DESC LIMIT 50"
            );
            pstmt.setObject(1, userId);
            pstmt.setString(2, role);
            pstmt.setObject(3, userId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject n = new JSONObject();
                n.put("id",         rs.getString(1));
                n.put("event_type", rs.getString(2));
                n.put("title",      rs.getString(3));
                n.put("message",    rs.getString(4));
                n.put("link_url",   rs.getString(5));
                n.put("entity_id",  rs.getString(6));
                n.put("created_at", rs.getTimestamp(7).toInstant().toString());
                boolean isRead = rs.getBoolean(8);
                n.put("read", isRead);
                if (!isRead) unreadCount++;
                notifications.add(n);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("unread_count", unreadCount);
        result.put("notifications", notifications);
        return result;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    private void markRead(HttpServletRequest req, HttpServletResponse res, JSONObject input, UUID userId) throws Exception {
        String idStr = (String) input.get("id");
        UUID notificationId;
        try {
            notificationId = UUID.fromString(idStr);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "A valid 'id' is required", req.getRequestURI());
            return;
        }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO notification_reads (notification_id, user_id) VALUES (?, ?) " +
                "ON CONFLICT (notification_id, user_id) DO NOTHING"
            );
            pstmt.setObject(1, notificationId);
            pstmt.setObject(2, userId);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        OutputProcessor.send(res, 200, successResult("Notification marked read"));
    }

    private void markAllRead(String role, UUID userId) throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO notification_reads (notification_id, user_id) " +
                "SELECT n.id, ? FROM notifications n WHERE ? = ANY(n.target_roles) OR n.target_user_id = ? " +
                "ON CONFLICT (notification_id, user_id) DO NOTHING"
            );
            pstmt.setObject(1, userId);
            pstmt.setString(2, role);
            pstmt.setObject(3, userId);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Emit — called by other services when a notification-worthy event occurs.
    // -------------------------------------------------------------------------

    /**
     * Creates a notification for every role that has WRITE or ADMIN permission
     * on {@code module}. Never throws — a notification failure must not block
     * the primary action (user registration, ticket creation, ...) that
     * triggered it; failures are logged to stderr only.
     */
    public static void emit(String eventType, String module, String title, String message, String linkUrl, UUID entityId) {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement rolesStmt = null;
        PreparedStatement insertStmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            List<String> roles = new ArrayList<>();
            rolesStmt = conn.prepareStatement(
                "SELECT role FROM role_permissions WHERE module = ? AND permission_level IN ('WRITE','ADMIN')"
            );
            rolesStmt.setString(1, module);
            rs = rolesStmt.executeQuery();
            while (rs.next()) roles.add(rs.getString(1));
            if (roles.isEmpty()) return;

            Array rolesArray = conn.createArrayOf("text", roles.toArray());
            insertStmt = conn.prepareStatement(
                "INSERT INTO notifications (event_type, title, message, link_url, entity_id, target_roles) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
            );
            insertStmt.setString(1, eventType);
            insertStmt.setString(2, title);
            insertStmt.setString(3, message);
            insertStmt.setString(4, linkUrl);
            insertStmt.setObject(5, entityId);
            insertStmt.setArray(6, rolesArray);
            insertStmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Notification] emit failed for event_type=" + eventType + ": " + e.getMessage());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (rolesStmt != null) rolesStmt.close(); } catch (Exception ignored) {}
            try { if (insertStmt != null) insertStmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Creates a notification for every user holding {@code role} literally —
     * independent of the role_permissions module matrix. Used for self-service-wide
     * broadcasts (e.g. a newly published policy) where the recipient set is "every
     * employee", not "every role with WRITE/ADMIN on some console module". Never
     * throws, matching {@link #emit}.
     */
    public static void emitToRole(String eventType, String role, String title, String message, String linkUrl, UUID entityId) {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement insertStmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            Array rolesArray = conn.createArrayOf("text", new String[]{ role });
            insertStmt = conn.prepareStatement(
                "INSERT INTO notifications (event_type, title, message, link_url, entity_id, target_roles) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
            );
            insertStmt.setString(1, eventType);
            insertStmt.setString(2, title);
            insertStmt.setString(3, message);
            insertStmt.setString(4, linkUrl);
            insertStmt.setObject(5, entityId);
            insertStmt.setArray(6, rolesArray);
            insertStmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Notification] emitToRole failed for event_type=" + eventType + ": " + e.getMessage());
        } finally {
            try { if (insertStmt != null) insertStmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Creates a notification for exactly one user — used when an event concerns
     * only that employee's own record (e.g. their change request's status changed),
     * so it must not be broadcast to every USER-role employee. Never throws,
     * matching {@link #emit}.
     */
    public static void emitToUser(String eventType, String title, String message, String linkUrl, UUID entityId, UUID targetUserId) {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement insertStmt = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            insertStmt = conn.prepareStatement(
                "INSERT INTO notifications (event_type, title, message, link_url, entity_id, target_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
            );
            insertStmt.setString(1, eventType);
            insertStmt.setString(2, title);
            insertStmt.setString(3, message);
            insertStmt.setString(4, linkUrl);
            insertStmt.setObject(5, entityId);
            insertStmt.setObject(6, targetUserId);
            insertStmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Notification] emitToUser failed for event_type=" + eventType + ": " + e.getMessage());
        } finally {
            try { if (insertStmt != null) insertStmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }
}
