package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.*;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AdminDash handles compliance metrics with time-filtering capabilities.
 * Updated to use standard pool.cleanup pattern in finally blocks.
 */
public class AdminDash implements Action {

    private static final String SERVICE_TYPE_ADMIN_CONSOLE = "ADMIN_CONSOLE";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "get_admin_metrics":
                    OutputProcessor.send(res, 200, getAdminMetrics());
                    break;
                case "get_dpo_metrics":
                    OutputProcessor.send(res, 200, getDpoMetrics(input));
                    break;
                case "list_pending_grievances":
                    OutputProcessor.send(res, 200, listPendingGrievances(input));
                    break;
                case "list_access_logs":
                    OutputProcessor.send(res, 200, listAuditLogsFromDb());
                    break;
                case "get_dashboard_metrics":
                    OutputProcessor.send(res, 200, getDashboardMetrics());
                    break;
                case "list_recent_activity":
                    OutputProcessor.send(res, 200, listRecentActivity());
                    break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown function", req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    protected JSONArray listAuditLogsFromDb() throws SQLException {
        JSONArray logs = new JSONArray();
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_logs where service_type='" + SERVICE_TYPE_ADMIN_CONSOLE + "' ORDER BY timestamp DESC LIMIT 5");
        PoolDB pool = new PoolDB();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql.toString());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject log = new JSONObject();
                log.put("id", rs.getObject("id").toString());
                log.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                log.put("user_id", rs.getString("user_id"));
                log.put("service_type", rs.getString("service_type"));
                log.put("service_id", rs.getString("service_id"));
                log.put("audit_action", rs.getString("audit_action"));

                // Parse details if they look like JSON, otherwise return as string
                String details = rs.getString("context_details");
                log.put("context_details", details);
                logs.add(log);
            }
        }catch (Exception e){}
        finally{
            pool.cleanup(rs,pstmt,conn);
        }
        return logs;
    }

    /**
     * Retrieves global system-wide metrics for high-level administration.
     */
    private JSONObject getAdminMetrics() throws SQLException {
        JSONObject metrics = new JSONObject();
        PoolDB pool = new PoolDB();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            metrics.put("active_fiduciaries", getSimpleCount(conn, pool, "SELECT COUNT(*) FROM fiduciaries WHERE status IN ('ACTIVE', 'PENDING')"));
            metrics.put("active_processors", getSimpleCount(conn, pool, "SELECT COUNT(*) FROM apps WHERE status = 'ACTIVE'"));
            metrics.put("failed_purges", getSimpleCount(conn, pool, "SELECT COUNT(*) FROM purge_requests WHERE status = 'FAILED'"));
        } finally {
            if (pool != null && conn != null) pool.cleanup(null, null, conn);
        }
        return new JSONObject() {{ put("success", true); put("metrics", metrics); }};
    }

    private int getSimpleCount(Connection conn, PoolDB pool, String sql) throws SQLException {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, null);
        }
    }

    /**
     * Retrieves counts for Active Policies, Consents, Principals, Purge Requests,
     * and Grievances, filtered by a specific date range.
     */
    private JSONObject getDpoMetrics(JSONObject input) throws SQLException {
        JSONObject metrics = new JSONObject();
        String start = (String) input.get("start_date");
        String end = (String) input.get("end_date");

        PoolDB pool = new PoolDB();
        Connection conn = null;

        try {
            conn = pool.getConnection();

            // 1. # Active Policy (Count policies created or existing in period that are active)
            metrics.put("active_policies", getCount(conn, pool, "SELECT COUNT(*) FROM consent_policies WHERE status = 'ACTIVE' AND created_at >= ?::timestamp AND created_at <= ?::timestamp", start, end));

            // 2. # Consents
            metrics.put("total_consents", getCount(conn, pool, "SELECT COUNT(*) FROM consent_records WHERE timestamp >= ?::timestamp AND timestamp <= ?::timestamp", start, end));

            // 3. # Data Principals
            metrics.put("data_principals", getCount(conn, pool, "SELECT COUNT(*) FROM data_principal WHERE created_at >= ?::timestamp AND created_at <= ?::timestamp", start, end));

            // 4. Purge Requests (Total and Pending)
            metrics.put("purge_total", getCount(conn, pool, "SELECT COUNT(*) FROM purge_requests WHERE initiated_at >= ?::timestamp AND initiated_at <= ?::timestamp", start, end));
            metrics.put("purge_pending", getCount(conn, pool, "SELECT COUNT(*) FROM purge_requests WHERE status NOT IN ('PURGE_COMPLETED','LEGAL_HOLD_APPLIED') AND initiated_at >= ?::timestamp AND initiated_at <= ?::timestamp", start, end));

            // 5. Grievances (Total and Pending)
            metrics.put("grievances_total", getCount(conn, pool, "SELECT COUNT(*) FROM grievances WHERE submission_timestamp >= ?::timestamp AND submission_timestamp <= ?::timestamp", start, end));
            metrics.put("grievances_pending", getCount(conn, pool, "SELECT COUNT(*) FROM grievances WHERE status NOT IN ('RESOLVED') AND submission_timestamp >= ?::timestamp AND submission_timestamp <= ?::timestamp", start, end));

            // 6. ROPA entries by status
            metrics.put("ropa_active", getCount(conn, pool, "SELECT COUNT(*) FROM ropa_entries WHERE status = 'active' AND created_at >= ?::timestamp AND created_at <= ?::timestamp", start, end));
            metrics.put("ropa_draft",  getCount(conn, pool, "SELECT COUNT(*) FROM ropa_entries WHERE status = 'draft'  AND created_at >= ?::timestamp AND created_at <= ?::timestamp", start, end));

        } finally {
            // Standard cleanup if not handled by individual getCount calls (though they clean up their own stmt/rs)
            if (pool != null && conn != null) pool.cleanup(null, null, conn);
        }

        return new JSONObject() {{ put("success", true); put("metrics", metrics); }};
    }

    /**
     * Helper to execute a count query with date range and proper cleanup.
     */
    private int getCount(Connection conn, PoolDB pool, String sql, String start, String end) throws SQLException {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, start + " 00:00:00");
            pstmt.setString(2, end + " 23:59:59");
            rs = pstmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } finally {
            // Clean up resources but keep connection open for the batch of queries
            if (pool != null) pool.cleanup(rs, pstmt, null);
        }
    }

    /**
     * Retrieves list of pending grievances for the dashboard table.
     */
    private JSONArray listPendingGrievances(JSONObject input) throws SQLException {
        JSONArray arr = new JSONArray();
        int limit = (input.get("limit") instanceof Long) ? ((Long) input.get("limit")).intValue() : 10;

        String sql = "SELECT id, type, submission_timestamp, due_date, status FROM grievances " +
                "WHERE status NOT IN ('RESOLVED', 'CLOSED', 'COMPLETED') " +
                "ORDER BY due_date ASC LIMIT ?";

        PoolDB pool = new PoolDB();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, limit);
            rs = pstmt.executeQuery();

            ZonedDateTime now = ZonedDateTime.now();
            while (rs.next()) {
                JSONObject g = new JSONObject();
                Timestamp dueDate = rs.getTimestamp("due_date");
                long daysLeft = ChronoUnit.DAYS.between(now.toLocalDate(), dueDate.toInstant().atZone(now.getZone()).toLocalDate());

                g.put("grievance_id", rs.getString("id"));
                g.put("type", rs.getString("type"));
                g.put("submission_timestamp", rs.getTimestamp("submission_timestamp").toInstant().toString());
                g.put("status", rs.getString("status"));
                g.put("days_left", daysLeft);
                arr.add(g);
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        return arr;
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        return "POST".equalsIgnoreCase(method);
    }

    @SuppressWarnings("unchecked")
    private JSONObject getDashboardMetrics() throws SQLException {
        PoolDB pool = new PoolDB();
        Connection conn = null;
        JSONObject r = new JSONObject();
        try {
            conn = pool.getConnection();

            r.put("active_users",   getSimpleCount(conn, pool, "SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'"));
            r.put("pending_users",  getSimpleCount(conn, pool, "SELECT COUNT(*) FROM users WHERE status = 'PENDING'"));

            r.put("open_risks",     getSimpleCount(conn, pool, "SELECT COUNT(*) FROM risks WHERE status != 'RETIRED'"));
            r.put("high_risks",     getSimpleCount(conn, pool, "SELECT COUNT(*) FROM risks WHERE inherent_risk_score >= 15 AND status != 'RETIRED'"));

            r.put("policies_total",        getSimpleCount(conn, pool, "SELECT COUNT(*) FROM policies"));
            r.put("policies_draft_review", getSimpleCount(conn, pool, "SELECT COUNT(*) FROM policies WHERE status IN ('DRAFT','UNDER_REVIEW')"));

            r.put("controls_total",    getSimpleCount(conn, pool, "SELECT COUNT(*) FROM controls"));
            r.put("controls_attested", getSimpleCount(conn, pool,
                "SELECT COUNT(DISTINCT control_id) FROM control_attestations WHERE status IN ('COMPLIANT','APPROVED')"));

            int total    = r.get("controls_total")    instanceof Integer ? (Integer) r.get("controls_total")    : 0;
            int attested = r.get("controls_attested") instanceof Integer ? (Integer) r.get("controls_attested") : 0;
            r.put("readiness_pct", total > 0 ? (int) Math.round(100.0 * attested / total) : 0);

            r.put("evidence_count",      getSimpleCount(conn, pool, "SELECT COUNT(*) FROM evidence_locker"));
            r.put("open_incidents",      getSimpleCount(conn, pool, "SELECT COUNT(*) FROM incidents WHERE status NOT IN ('RESOLVED','CLOSED')"));
            r.put("critical_incidents",  getSimpleCount(conn, pool, "SELECT COUNT(*) FROM incidents WHERE status NOT IN ('RESOLVED','CLOSED') AND severity IN ('CRITICAL','HIGH')"));
            r.put("audit_events_today",  getSimpleCount(conn, pool, "SELECT COUNT(*) FROM system_audit_trail WHERE timestamp >= CURRENT_DATE"));

        } finally {
            if (pool != null && conn != null) pool.cleanup(null, null, conn);
        }
        r.put("success", true);
        return r;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listRecentActivity() throws SQLException {
        PoolDB pool = new PoolDB();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONArray events = new JSONArray();
        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT TO_CHAR(sat.timestamp AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS') AS ts, " +
                "sat.audit_action, sat.context_details::text AS details, u.username " +
                "FROM system_audit_trail sat LEFT JOIN users u ON u.id = sat.user_id " +
                "ORDER BY sat.timestamp DESC LIMIT 10"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject ev = new JSONObject();
                ev.put("timestamp",    rs.getString("ts"));
                ev.put("audit_action", rs.getString("audit_action"));
                ev.put("details",      rs.getString("details"));
                ev.put("username",     rs.getString("username"));
                events.add(ev);
            }
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("events", events);
        return result;
    }
}
