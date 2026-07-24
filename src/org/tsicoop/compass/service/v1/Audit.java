package org.tsicoop.compass.service.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Audit implements Action {

    private static final Set<String> EVIDENCE_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".png", ".jpg", ".jpeg", ".zip", ".txt", ".csv"
    ));

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
                case "get_audit_metrics":
                    OutputProcessor.send(res, 200, getAuditMetrics());
                    break;
                case "list_events":
                    OutputProcessor.send(res, 200, listEvents(input));
                    break;
                case "get_internal_audit_metrics":
                    OutputProcessor.send(res, 200, getInternalAuditMetrics());
                    break;
                case "list_audits":
                    OutputProcessor.send(res, 200, listAudits(input));
                    break;
                case "add_audit":
                    OutputProcessor.send(res, 200, addAudit(input));
                    break;
                case "update_audit":
                    OutputProcessor.send(res, 200, updateAudit(input));
                    break;
                case "list_observations":
                    OutputProcessor.send(res, 200, listObservations(input));
                    break;
                case "add_observation":
                    OutputProcessor.send(res, 200, addObservation(input));
                    break;
                case "update_observation":
                    OutputProcessor.send(res, 200, updateObservation(input));
                    break;
                case "list_evidence":
                    OutputProcessor.send(res, 200, listEvidence(input));
                    break;
                case "add_evidence":
                    OutputProcessor.send(res, 200, addEvidence(input));
                    break;
                case "get_evidence_file":
                    OutputProcessor.send(res, 200, getEvidenceFile(input));
                    break;
                case "delete_audit":
                    OutputProcessor.send(res, 200, deleteAudit(input, req));
                    break;
                case "delete_observation":
                    OutputProcessor.send(res, 200, deleteObservation(input, req));
                    break;
                case "delete_evidence":
                    OutputProcessor.send(res, 200, deleteEvidence(input, req));
                    break;
                case "import_observations":
                    OutputProcessor.send(res, 200, importObservations(input, req));
                    break;
                case "list_staff":
                    OutputProcessor.send(res, 200, listStaff());
                    break;
                case "list_frameworks_dd":
                    OutputProcessor.send(res, 200, listFrameworksDD());
                    break;
                case "list_departments_dd":
                    OutputProcessor.send(res, 200, new org.json.simple.JSONObject());
                    break;
                case "list_controls_dd":
                    OutputProcessor.send(res, 200, listControlsDD());
                    break;
                case "list_risks_dd":
                    OutputProcessor.send(res, 200, listRisksDD());
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

    // ── System audit trail ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject getAuditMetrics() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM system_audit_trail WHERE timestamp >= CURRENT_DATE"
            );
            rs = pstmt.executeQuery();
            result.put("events_today", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM system_audit_trail WHERE timestamp >= DATE_TRUNC('week', CURRENT_TIMESTAMP)"
            );
            rs = pstmt.executeQuery();
            result.put("events_week", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM system_audit_trail " +
                "WHERE timestamp >= DATE_TRUNC('week', CURRENT_TIMESTAMP) " +
                "AND (audit_action ILIKE '%DELETE%' OR audit_action ILIKE '%SUSPEND%' " +
                "OR audit_action ILIKE '%RESET%' OR audit_action ILIKE '%EXCEPTION%' " +
                "OR audit_action ILIKE '%ESCALAT%')"
            );
            rs = pstmt.executeQuery();
            result.put("critical_events", rs.next() ? rs.getLong(1) : 0L);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(DISTINCT user_id) FROM system_audit_trail " +
                "WHERE timestamp >= CURRENT_DATE AND user_id IS NOT NULL"
            );
            rs = pstmt.executeQuery();
            result.put("active_users_today", rs.next() ? rs.getLong(1) : 0L);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listEvents(JSONObject input) throws Exception {
        String search   = (String) input.get("search");
        String userId   = (String) input.get("user_id");
        String dateFrom = (String) input.get("date_from");
        String dateTo   = (String) input.get("date_to");

        long page     = 1L;
        long pageSize = 20L;
        Object pageObj     = input.get("page");
        Object pageSizeObj = input.get("page_size");
        if (pageObj instanceof Long)     page     = (Long) pageObj;
        if (pageSizeObj instanceof Long) pageSize = (Long) pageSizeObj;
        if (pageSize > 100) pageSize = 100;
        if (page < 1) page = 1;
        long offset = (page - 1) * pageSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(dateFrom)) where.append(" AND sat.timestamp >= ?::date");
        if (!isBlank(dateTo))   where.append(" AND sat.timestamp < (?::date + INTERVAL '1 day')");
        if (!isBlank(userId))   where.append(" AND sat.user_id = ?::uuid");
        if (!isBlank(search))   where.append(" AND sat.audit_action ILIKE ?");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray events = new JSONArray();
        long total = 0L;

        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM system_audit_trail sat " +
                              "LEFT JOIN users u ON u.id = sat.user_id" + where;
            pstmt = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(dateFrom)) pstmt.setString(idx++, dateFrom);
            if (!isBlank(dateTo))   pstmt.setString(idx++, dateTo);
            if (!isBlank(userId))   pstmt.setString(idx++, userId);
            if (!isBlank(search))   pstmt.setString(idx++, "%" + search + "%");
            rs = pstmt.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            String dataSql =
                "SELECT sat.id, TO_CHAR(sat.timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS ts, " +
                "sat.audit_action, sat.context_details::text AS details, sat.log_hash, " +
                "u.username, u.email " +
                "FROM system_audit_trail sat LEFT JOIN users u ON u.id = sat.user_id" +
                where + " ORDER BY sat.timestamp DESC LIMIT ? OFFSET ?";
            pstmt = conn.prepareStatement(dataSql);
            idx = 1;
            if (!isBlank(dateFrom)) pstmt.setString(idx++, dateFrom);
            if (!isBlank(dateTo))   pstmt.setString(idx++, dateTo);
            if (!isBlank(userId))   pstmt.setString(idx++, userId);
            if (!isBlank(search))   pstmt.setString(idx++, "%" + search + "%");
            pstmt.setLong(idx++, pageSize);
            pstmt.setLong(idx++, offset);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                JSONObject ev = new JSONObject();
                ev.put("id",           rs.getString("id"));
                ev.put("timestamp",    rs.getString("ts"));
                ev.put("audit_action", rs.getString("audit_action"));
                ev.put("details",      rs.getString("details"));
                ev.put("log_hash",     rs.getString("log_hash"));
                ev.put("username",     rs.getString("username"));
                ev.put("email",        rs.getString("email"));
                events.add(ev);
            }

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }

        result.put("success",     true);
        result.put("events",      events);
        result.put("total_count", total);
        result.put("page",        page);
        result.put("page_size",   pageSize);
        result.put("total_pages", (total + pageSize - 1) / pageSize);
        return result;
    }

    // ── Internal audit ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject getInternalAuditMetrics() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('CLOSED')) AS active_audits, " +
                "COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed_audits, " +
                "COUNT(*) AS total_audits, " +
                "COUNT(*) FILTER (WHERE scheduled_start <= CURRENT_DATE AND status = 'SCHEDULED') AS overdue_start " +
                "FROM audits"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("active_audits",  rs.getLong("active_audits"));
                result.put("closed_audits",  rs.getLong("closed_audits"));
                result.put("total_audits",   rs.getLong("total_audits"));
                result.put("overdue_start",  rs.getLong("overdue_start"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open_observations, " +
                "COUNT(*) FILTER (WHERE priority = 'HIGH' AND status = 'OPEN') AS high_observations, " +
                "COUNT(*) AS total_observations FROM audit_observations"
            );
            rs = pstmt.executeQuery();
            if (rs.next()) {
                result.put("open_observations",  rs.getLong("open_observations"));
                result.put("high_observations",  rs.getLong("high_observations"));
                result.put("total_observations", rs.getLong("total_observations"));
            }
            try { pool.cleanup(rs, pstmt, null); } catch (Exception ignored) {}
            rs = null; pstmt = null;

            pstmt = conn.prepareStatement("SELECT COUNT(*) FROM evidence_locker");
            rs = pstmt.executeQuery();
            result.put("evidence_count", rs.next() ? rs.getLong(1) : 0L);

        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listAudits(JSONObject input) throws Exception {
        String search = (String) input.get("search");
        String status = (String) input.get("status");

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(status)) where.append(" AND a.status = ?");
        if (!isBlank(search)) where.append(" AND a.title ILIKE ?");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String sql =
                "SELECT a.id, a.title, a.scope, a.status, " +
                "TO_CHAR(a.scheduled_start, 'YYYY-MM-DD') AS scheduled_start, " +
                "TO_CHAR(a.scheduled_end, 'YYYY-MM-DD') AS scheduled_end, " +
                "f.name AS framework_name, " +
                "u.username AS lead_auditor_name, " +
                "(SELECT COUNT(*) FROM audit_observations WHERE audit_id = a.id) AS observation_count, " +
                "(SELECT COUNT(*) FROM evidence_locker WHERE audit_id = a.id) AS evidence_count " +
                "FROM audits a " +
                "LEFT JOIN frameworks f ON f.id = a.framework_id " +
                "LEFT JOIN users u ON u.id = a.lead_auditor_id" +
                where + " ORDER BY a.scheduled_start DESC";
            pstmt = conn.prepareStatement(sql);
            int idx = 1;
            if (!isBlank(status)) pstmt.setString(idx++, status);
            if (!isBlank(search)) pstmt.setString(idx++, "%" + search + "%");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",                rs.getString("id"));
                row.put("title",             rs.getString("title"));
                row.put("scope",             rs.getString("scope"));
                row.put("status",            rs.getString("status"));
                row.put("scheduled_start",   rs.getString("scheduled_start"));
                row.put("scheduled_end",     rs.getString("scheduled_end"));
                row.put("framework_name",    rs.getString("framework_name"));
                row.put("lead_auditor_name", rs.getString("lead_auditor_name"));
                row.put("observation_count", rs.getLong("observation_count"));
                row.put("evidence_count",    rs.getLong("evidence_count"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        result.put("audits",  list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject addAudit(JSONObject input) throws Exception {
        String title        = (String) input.get("title");
        String scope        = (String) input.get("scope");
        String frameworkId  = (String) input.get("framework_id");
        String leadAuditor  = (String) input.get("lead_auditor_id");
        String startDate    = (String) input.get("scheduled_start");
        String endDate      = (String) input.get("scheduled_end");
        if (isBlank(title) || isBlank(scope) || isBlank(startDate) || isBlank(endDate))
            throw new IllegalArgumentException("title, scope, scheduled_start, scheduled_end are required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO audits (title, scope, framework_id, lead_auditor_id, scheduled_start, scheduled_end) " +
                "VALUES (?, ?, ?::uuid, ?::uuid, ?::date, ?::date) RETURNING id"
            );
            pstmt.setString(1, title);
            pstmt.setString(2, scope);
            pstmt.setString(3, isBlank(frameworkId) ? null : frameworkId);
            pstmt.setString(4, isBlank(leadAuditor) ? null : leadAuditor);
            pstmt.setString(5, startDate);
            pstmt.setString(6, endDate);
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
    private JSONObject updateAudit(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE audits SET " +
                "title           = COALESCE(?, title), " +
                "scope           = COALESCE(?, scope), " +
                "framework_id    = COALESCE(?::uuid, framework_id), " +
                "lead_auditor_id = COALESCE(?::uuid, lead_auditor_id), " +
                "scheduled_start = COALESCE(?::date, scheduled_start), " +
                "scheduled_end   = COALESCE(?::date, scheduled_end), " +
                "status          = COALESCE(?, status) " +
                "WHERE id = ?::uuid"
            );
            pstmt.setString(1, (String) input.get("title"));
            pstmt.setString(2, (String) input.get("scope"));
            pstmt.setString(3, (String) input.get("framework_id"));
            pstmt.setString(4, (String) input.get("lead_auditor_id"));
            pstmt.setString(5, (String) input.get("scheduled_start"));
            pstmt.setString(6, (String) input.get("scheduled_end"));
            pstmt.setString(7, (String) input.get("status"));
            pstmt.setString(8, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listObservations(JSONObject input) throws Exception {
        String auditId = (String) input.get("audit_id");
        String priority = (String) input.get("priority");
        String status   = (String) input.get("status");
        String search   = (String) input.get("search");

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(auditId))  where.append(" AND ao.audit_id = ?::uuid");
        if (!isBlank(priority)) where.append(" AND ao.priority = ?");
        if (!isBlank(status))   where.append(" AND ao.status = ?");
        if (!isBlank(search))   where.append(" AND ao.title ILIKE ?");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String sql =
                "SELECT ao.id, ao.audit_id, ao.title, ao.description, ao.priority, ao.status, " +
                "a.title AS audit_title, " +
                "r.title AS linked_risk_title, r.risk_code, " +
                "c.title AS failed_control_title, c.code AS failed_control_code, " +
                "ar.status AS remediation_status, " +
                "TO_CHAR(ar.target_date, 'YYYY-MM-DD') AS remediation_target, " +
                "ar.action_plan " +
                "FROM audit_observations ao " +
                "LEFT JOIN audits a ON a.id = ao.audit_id " +
                "LEFT JOIN risks r ON r.id = ao.linked_risk_id " +
                "LEFT JOIN controls c ON c.id = ao.failed_control_id " +
                "LEFT JOIN audit_remediations ar ON ar.observation_id = ao.id" +
                where +
                " ORDER BY CASE ao.priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, ao.status";
            pstmt = conn.prepareStatement(sql);
            int idx = 1;
            if (!isBlank(auditId))  pstmt.setString(idx++, auditId);
            if (!isBlank(priority)) pstmt.setString(idx++, priority);
            if (!isBlank(status))   pstmt.setString(idx++, status);
            if (!isBlank(search))   pstmt.setString(idx++, "%" + search + "%");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",                    rs.getString("id"));
                row.put("audit_id",              rs.getString("audit_id"));
                row.put("title",                 rs.getString("title"));
                row.put("description",           rs.getString("description"));
                row.put("priority",              rs.getString("priority"));
                row.put("status",                rs.getString("status"));
                row.put("audit_title",           rs.getString("audit_title"));
                row.put("linked_risk_title",     rs.getString("linked_risk_title"));
                row.put("risk_code",             rs.getString("risk_code"));
                row.put("failed_control_title",  rs.getString("failed_control_title"));
                row.put("failed_control_code",   rs.getString("failed_control_code"));
                row.put("remediation_status",    rs.getString("remediation_status"));
                row.put("remediation_target",    rs.getString("remediation_target"));
                row.put("action_plan",           rs.getString("action_plan"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success",      true);
        result.put("observations", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject addObservation(JSONObject input) throws Exception {
        String auditId         = (String) input.get("audit_id");
        String title           = (String) input.get("title");
        String description     = (String) input.get("description");
        String priority        = (String) input.get("priority");
        String linkedRiskId    = (String) input.get("linked_risk_id");
        String failedControlId = (String) input.get("failed_control_id");
        if (isBlank(auditId) || isBlank(title) || isBlank(priority))
            throw new IllegalArgumentException("audit_id, title, and priority are required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO audit_observations (audit_id, title, description, priority, linked_risk_id, failed_control_id) " +
                "VALUES (?::uuid, ?, ?, ?, ?::uuid, ?::uuid) RETURNING id"
            );
            pstmt.setString(1, auditId);
            pstmt.setString(2, title);
            pstmt.setString(3, description);
            pstmt.setString(4, priority);
            pstmt.setString(5, isBlank(linkedRiskId)    ? null : linkedRiskId);
            pstmt.setString(6, isBlank(failedControlId) ? null : failedControlId);
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
    private JSONObject updateObservation(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "UPDATE audit_observations SET " +
                "title       = COALESCE(?, title), " +
                "description = COALESCE(?, description), " +
                "priority    = COALESCE(?, priority), " +
                "status      = COALESCE(?, status) " +
                "WHERE id = ?::uuid"
            );
            pstmt.setString(1, (String) input.get("title"));
            pstmt.setString(2, (String) input.get("description"));
            pstmt.setString(3, (String) input.get("priority"));
            pstmt.setString(4, (String) input.get("status"));
            pstmt.setString(5, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) {
                try { pool.cleanup(null, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listEvidence(JSONObject input) throws Exception {
        String auditId = (String) input.get("audit_id");
        String search  = (String) input.get("search");

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(auditId)) where.append(" AND el.audit_id = ?::uuid");
        if (!isBlank(search))  where.append(" AND el.file_name ILIKE ?");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String sql =
                "SELECT el.id, el.file_name, el.file_path, el.sha256_checksum, el.is_locked, " +
                "TO_CHAR(el.uploaded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS uploaded_at, " +
                "a.title AS audit_title, a.id AS audit_id_val, " +
                "u.username AS uploaded_by_name " +
                "FROM evidence_locker el " +
                "LEFT JOIN audits a ON a.id = el.audit_id " +
                "LEFT JOIN users u ON u.id = el.uploaded_by" +
                where + " ORDER BY el.uploaded_at DESC";
            pstmt = conn.prepareStatement(sql);
            int idx = 1;
            if (!isBlank(auditId)) pstmt.setString(idx++, auditId);
            if (!isBlank(search))  pstmt.setString(idx++, "%" + search + "%");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",               rs.getString("id"));
                row.put("file_name",        rs.getString("file_name"));
                row.put("file_path",        rs.getString("file_path"));
                row.put("sha256_checksum",  rs.getString("sha256_checksum"));
                row.put("is_locked",        rs.getBoolean("is_locked"));
                row.put("uploaded_at",      rs.getString("uploaded_at"));
                row.put("audit_title",      rs.getString("audit_title"));
                row.put("audit_id",         rs.getString("audit_id_val"));
                row.put("uploaded_by_name", rs.getString("uploaded_by_name"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success",  true);
        result.put("evidence", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject addEvidence(JSONObject input) throws Exception {
        String fileName            = (String) input.get("file_name");
        String fileData            = (String) input.get("file_data");
        String uploadedBy          = (String) input.get("uploaded_by");
        String auditId             = (String) input.get("audit_id");
        String controlAttestationId = (String) input.get("control_attestation_id");
        if (isBlank(fileName) || isBlank(fileData))
            throw new IllegalArgumentException("file_name and file_data are required");

        String safeName = new File(fileName).getName();
        int dotIdx = safeName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? safeName.substring(dotIdx).toLowerCase() : "";
        if (!EVIDENCE_EXTENSIONS.contains(ext))
            throw new IllegalArgumentException("File type not allowed: " + ext);

        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw.trim()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid base64 content"); }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(bytes);
        StringBuilder hexSb = new StringBuilder();
        for (byte b : hashBytes) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) hexSb.append('0'); hexSb.append(h); }
        String checksum = hexSb.toString();

        String uploadDir = System.getenv("TSI_EXPORT_PATH");
        if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
        File dir = new File(uploadDir + "/evidence");
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Cannot create evidence upload directory");
        String storedName = UUID.randomUUID().toString() + ext;
        File dest = new File(dir, storedName);
        try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "INSERT INTO evidence_locker (file_name, file_path, sha256_checksum, timestamp_signature, uploaded_by, audit_id, control_attestation_id) " +
                "VALUES (?, ?, ?, 'file-upload', ?::uuid, ?::uuid, ?::uuid) RETURNING id"
            );
            pstmt.setString(1, safeName);
            pstmt.setString(2, dest.getAbsolutePath());
            pstmt.setString(3, checksum);
            pstmt.setString(4, isBlank(uploadedBy)             ? null : uploadedBy);
            pstmt.setString(5, isBlank(auditId)                ? null : auditId);
            pstmt.setString(6, isBlank(controlAttestationId)   ? null : controlAttestationId);
            rs = pstmt.executeQuery();
            rs.next();
            result.put("id", rs.getString("id"));
            result.put("sha256_checksum", checksum);
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getEvidenceFile(JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(
                "SELECT file_name, file_path FROM evidence_locker WHERE id = ?::uuid"
            );
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (!rs.next() || isBlank(rs.getString("file_path")))
                throw new IllegalArgumentException("Evidence record not found");
            String name = rs.getString("file_name");
            String path = rs.getString("file_path");

            File file = new File(path);
            if (!file.exists() || !file.isFile())
                throw new IllegalArgumentException("File not found on server");

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("file_name", name);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            return result;
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
    }

    // ── Delete (ADMIN only) ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject deleteAudit(JSONObject input, HttpServletRequest req) throws Exception {
        String role = InputProcessor.getRole(req);
        if (!"ADMIN".equals(role))
            throw new SecurityException("Only ADMIN may delete audit records");
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        String title = id;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT title FROM audits WHERE id = ?::uuid");
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) title = rs.getString("title");
            pool.cleanup(rs, pstmt, null);
            rs = null; pstmt = null;
            pstmt = conn.prepareStatement("DELETE FROM audits WHERE id = ?::uuid");
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        JSONObject ctx = new JSONObject();
        ctx.put("audit_id", id);
        ctx.put("audit_title", title);
        EventLog.log(InputProcessor.getEmail(req), "AUDIT_SCHEDULE_DELETED", ctx.toJSONString());
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject deleteObservation(JSONObject input, HttpServletRequest req) throws Exception {
        String role = InputProcessor.getRole(req);
        if (!"ADMIN".equals(role))
            throw new SecurityException("Only ADMIN may delete observation records");
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        String title = id;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT title FROM audit_observations WHERE id = ?::uuid");
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) title = rs.getString("title");
            pool.cleanup(rs, pstmt, null);
            rs = null; pstmt = null;
            pstmt = conn.prepareStatement("DELETE FROM audit_observations WHERE id = ?::uuid");
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        JSONObject ctx = new JSONObject();
        ctx.put("observation_id", id);
        ctx.put("observation_title", title);
        EventLog.log(InputProcessor.getEmail(req), "OBSERVATION_DELETED", ctx.toJSONString());
        result.put("success", true);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject deleteEvidence(JSONObject input, HttpServletRequest req) throws Exception {
        String role = InputProcessor.getRole(req);
        if (!"ADMIN".equals(role))
            throw new SecurityException("Only ADMIN may delete evidence records");
        String id = (String) input.get("id");
        if (isBlank(id)) throw new IllegalArgumentException("id is required");

        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        String fileName = id;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT file_name FROM evidence_locker WHERE id = ?::uuid");
            pstmt.setString(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) fileName = rs.getString("file_name");
            pool.cleanup(rs, pstmt, null);
            rs = null; pstmt = null;
            pstmt = conn.prepareStatement("DELETE FROM evidence_locker WHERE id = ?::uuid");
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } finally {
            if (pool != null) pool.cleanup(rs, pstmt, conn);
        }
        JSONObject ctx = new JSONObject();
        ctx.put("evidence_id", id);
        ctx.put("file_name", fileName);
        EventLog.log(InputProcessor.getEmail(req), "EVIDENCE_DELETED", ctx.toJSONString());
        result.put("success", true);
        return result;
    }

    // ── Dropdown helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
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
                "SELECT id, username, email FROM users WHERE status = 'ACTIVE' AND role != 'USER' ORDER BY username"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",       rs.getString("id"));
                row.put("username", rs.getString("username"));
                row.put("email",    rs.getString("email"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        result.put("staff",   list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listFrameworksDD() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT id, name FROM frameworks ORDER BY name");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",   rs.getString("id"));
                row.put("name", rs.getString("name"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success",    true);
        result.put("frameworks", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listControlsDD() throws Exception {
        PoolDB pool = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            pstmt = conn.prepareStatement("SELECT id, code, title FROM controls ORDER BY code");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",    rs.getString("id"));
                row.put("code",  rs.getString("code"));
                row.put("title", rs.getString("title"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success",  true);
        result.put("controls", list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listRisksDD() throws Exception {
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
                "SELECT id, risk_code, title FROM risks WHERE status != 'RETIRED' ORDER BY risk_code"
            );
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id",        rs.getString("id"));
                row.put("risk_code", rs.getString("risk_code"));
                row.put("title",     rs.getString("title"));
                list.add(row);
            }
        } finally {
            if (pool != null) {
                try { pool.cleanup(rs, pstmt, conn); } catch (Exception ignored) {}
            }
        }
        result.put("success", true);
        result.put("risks",   list);
        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject importObservations(JSONObject input, HttpServletRequest req) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty())
            throw new IllegalArgumentException("rows array required");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO audit_observations (audit_id, title, description, priority) " +
                "VALUES (?::uuid, ?, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String title    = strVal(row, "title");
                String priority = strVal(row, "priority");
                if (isBlank(title)) { errors.add("Row skipped: title required"); continue; }

                String auditTitle      = strVal(row, "audit_title");
                String resolvedAuditId = null;
                if (!isBlank(auditTitle)) {
                    PreparedStatement pa = null; ResultSet ra = null;
                    try {
                        pa = conn.prepareStatement("SELECT id::text FROM audits WHERE title ILIKE ? LIMIT 1");
                        pa.setString(1, auditTitle); ra = pa.executeQuery();
                        if (ra.next()) resolvedAuditId = ra.getString(1);
                    } finally {
                        if (ra != null) try { ra.close(); } catch (Exception ignored) {}
                        if (pa != null) try { pa.close(); } catch (Exception ignored) {}
                    }
                }

                try {
                    p.setString(1, resolvedAuditId);
                    p.setString(2, title);
                    p.setString(3, strVal(row, "description"));
                    p.setString(4, isBlank(priority) ? "MEDIUM" : priority.toUpperCase());
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '" + title + "': " + ex.getMessage()); }
            }
            JSONObject ctx = new JSONObject(); ctx.put("count", (long) imported);
            EventLog.log(InputProcessor.getEmail(req), "OBSERVATIONS_IMPORTED", ctx.toJSONString());
        } finally {
            if (pool != null) try { pool.cleanup(null, p, conn); } catch (Exception ignored) {}
        }
        JSONObject result = new JSONObject();
        result.put("success",  true);
        result.put("imported", (long) imported);
        result.put("errors",   errors);
        return result;
    }

    private String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
