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

public class Audit implements Action {

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
                ev.put("id",            rs.getString("id"));
                ev.put("timestamp",     rs.getString("ts"));
                ev.put("audit_action",  rs.getString("audit_action"));
                ev.put("details",       rs.getString("details"));
                ev.put("log_hash",      rs.getString("log_hash"));
                ev.put("username",      rs.getString("username"));
                ev.put("email",         rs.getString("email"));
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

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
