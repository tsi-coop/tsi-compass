package org.tsicoop.compass.framework;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;

public class EventLog {

    /**
     * Writes an immutable entry to system_audit_trail.
     * The hash chain is computed by a DB trigger, so log_hash is a placeholder here.
     *
     * @param actorEmail email of the user performing the action (looked up to resolve user_id)
     * @param action     short uppercase action string, e.g. "USER_PROVISIONED"
     * @param contextJson valid JSON string with relevant context; pass null or "" for {}
     */
    public static void log(String actorEmail, String action, String contextJson) {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            UUID actorId = null;
            if (actorEmail != null && !actorEmail.trim().isEmpty()) {
                pstmt = conn.prepareStatement("SELECT id FROM users WHERE email = ?");
                pstmt.setString(1, actorEmail.toLowerCase().trim());
                rs = pstmt.executeQuery();
                if (rs.next()) actorId = UUID.fromString(rs.getString("id"));
                pool.cleanup(rs, pstmt, null);
                rs = null;
                pstmt = null;
            }

            pstmt = conn.prepareStatement(
                "INSERT INTO system_audit_trail (user_id, audit_action, context_details, log_hash) " +
                "VALUES (?, ?, ?::jsonb, 'pending')"
            );
            if (actorId != null) pstmt.setObject(1, actorId);
            else pstmt.setNull(1, Types.OTHER);
            pstmt.setString(2, action);
            pstmt.setString(3, contextJson != null && !contextJson.isEmpty() ? contextJson : "{}");
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[EventLog] Audit write failed for action=" + action + ": " + e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
    }
}
