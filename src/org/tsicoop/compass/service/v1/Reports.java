package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;

import java.sql.*;

public class Reports implements Action {

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
                case "get_executive_summary":
                    OutputProcessor.send(res, 200, getExecutiveSummary());
                    break;
                case "list_schedules":
                    OutputProcessor.send(res, 200, listSchedules());
                    break;
                case "add_schedule":
                    OutputProcessor.send(res, 200, addSchedule(input));
                    break;
                case "update_schedule":
                    OutputProcessor.send(res, 200, updateSchedule(input));
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
    private JSONObject getExecutiveSummary() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status != 'RETIRED') AS open_risks, " +
                "COUNT(*) FILTER (WHERE inherent_risk_score >= 15 AND status != 'RETIRED') AS high_risks, " +
                "COUNT(*) FILTER (WHERE status IN ('TREATED','MONITORED')) AS treated_risks, " +
                "COUNT(*) AS total_risks FROM risks"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_risks",    rs.getLong("open_risks"));
                result.put("high_risks",    rs.getLong("high_risks"));
                result.put("treated_risks", rs.getLong("treated_risks"));
                result.put("total_risks",   rs.getLong("total_risks"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS open_vulns, " +
                "COUNT(*) FILTER (WHERE severity = 'CRITICAL' AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS critical_vulns, " +
                "COUNT(*) FILTER (WHERE sla_deadline < NOW() AND status NOT IN ('RESOLVED','FALSE_POSITIVE')) AS overdue_vulns " +
                "FROM vulnerabilities"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_vulns",    rs.getLong("open_vulns"));
                result.put("critical_vulns",rs.getLong("critical_vulns"));
                result.put("overdue_vulns", rs.getLong("overdue_vulns"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(DISTINCT c.id) AS total_controls, " +
                "COUNT(DISTINCT CASE WHEN ca.status IN ('COMPLIANT','APPROVED') THEN c.id END) AS compliant_controls " +
                "FROM controls c LEFT JOIN LATERAL (" +
                "  SELECT status FROM control_attestations WHERE control_id = c.id ORDER BY attested_at DESC NULLS LAST LIMIT 1" +
                ") ca ON true"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                long total     = rs.getLong("total_controls");
                long compliant = rs.getLong("compliant_controls");
                result.put("total_controls",     total);
                result.put("compliant_controls", compliant);
                result.put("compliance_pct",     total > 0 ? (compliant * 100L / total) : 0L);
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('CLOSED')) AS active_audits, " +
                "COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed_audits, " +
                "COUNT(*) AS total_audits FROM audits"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("active_audits", rs.getLong("active_audits"));
                result.put("closed_audits", rs.getLong("closed_audits"));
                result.put("total_audits",  rs.getLong("total_audits"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open_observations, " +
                "COUNT(*) FILTER (WHERE priority = 'HIGH' AND status = 'OPEN') AS high_observations " +
                "FROM audit_observations"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_observations", rs.getLong("open_observations"));
                result.put("high_observations", rs.getLong("high_observations"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_incidents, " +
                "COUNT(*) FILTER (WHERE severity IN ('CRITICAL','HIGH') AND status NOT IN ('RESOLVED','CLOSED')) AS high_incidents " +
                "FROM incidents"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_incidents", rs.getLong("open_incidents"));
                result.put("high_incidents", rs.getLong("high_incidents"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status = 'SUBMITTED') AS pending_changes, " +
                "COUNT(*) FILTER (WHERE status NOT IN ('COMPLETED','REJECTED')) AS active_changes " +
                "FROM change_requests"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("pending_changes", rs.getLong("pending_changes"));
                result.put("active_changes",  rs.getLong("active_changes"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open_tickets, " +
                "COUNT(*) FILTER (WHERE priority IN ('CRITICAL','HIGH') AND status = 'OPEN') AS high_tickets " +
                "FROM helpdesk_tickets"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_tickets", rs.getLong("open_tickets"));
                result.put("high_tickets", rs.getLong("high_tickets"));
            }

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listSchedules() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT sr.id, sr.name, sr.report_type, sr.cadence, sr.recipients, sr.is_active, " +
                "TO_CHAR(sr.last_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS last_run_at, " +
                "TO_CHAR(sr.next_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS next_run_at, " +
                "TO_CHAR(sr.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS created_at, " +
                "u.username AS created_by_name " +
                "FROM scheduled_reports sr LEFT JOIN users u ON u.id = sr.created_by " +
                "ORDER BY sr.is_active DESC, sr.next_run_at ASC NULLS LAST"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",              rs.getString("id"));
                row.put("name",            rs.getString("name"));
                row.put("report_type",     rs.getString("report_type"));
                row.put("cadence",         rs.getString("cadence"));
                row.put("recipients",      rs.getString("recipients"));
                row.put("is_active",       rs.getBoolean("is_active"));
                row.put("last_run_at",     rs.getString("last_run_at"));
                row.put("next_run_at",     rs.getString("next_run_at"));
                row.put("created_at",      rs.getString("created_at"));
                row.put("created_by_name", rs.getString("created_by_name"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success",   true);
        result.put("schedules", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject addSchedule(JSONObject input) throws Exception {
        String name       = (String) input.get("name");
        String reportType = (String) input.get("report_type");
        String cadence    = (String) input.get("cadence");
        String recipients = (String) input.get("recipients");
        String createdBy  = (String) input.get("created_by");
        if (isBlank(name) || isBlank(reportType) || isBlank(cadence))
            throw new IllegalArgumentException("name, report_type, and cadence are required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO scheduled_reports (name, report_type, cadence, recipients, created_by) " +
                "VALUES (?, ?, ?, ?, ?::uuid) RETURNING id"
            );
            pstmt.setString(1, name);
            pstmt.setString(2, reportType);
            pstmt.setString(3, cadence);
            pstmt.setString(4, recipients);
            pstmt.setString(5, isBlank(createdBy) ? null : createdBy);
            rs = pstmt.executeQuery();
            rs.next();
            result.put("id", rs.getString("id"));
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject updateSchedule(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");
        Object isActiveObj = input.get("is_active");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE scheduled_reports SET " +
                "name        = COALESCE(?, name), " +
                "report_type = COALESCE(?, report_type), " +
                "cadence     = COALESCE(?, cadence), " +
                "recipients  = COALESCE(?, recipients), " +
                "is_active   = COALESCE(?::boolean, is_active), " +
                "next_run_at = COALESCE(?::timestamptz, next_run_at) " +
                "WHERE id = ?::uuid"
            );
            pstmt.setString(1, (String) input.get("name"));
            pstmt.setString(2, (String) input.get("report_type"));
            pstmt.setString(3, (String) input.get("cadence"));
            pstmt.setString(4, (String) input.get("recipients"));
            pstmt.setString(5, isActiveObj != null ? isActiveObj.toString() : null);
            pstmt.setString(6, (String) input.get("next_run_at"));
            pstmt.setString(7, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
