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
import java.util.UUID;

public class Incidents implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_incident_metrics": OutputProcessor.send(res, 200, getIncidentMetrics());       break;
                case "list_incidents":       OutputProcessor.send(res, 200, listIncidents(input));       break;
                case "add_incident":         addIncident(req, res, input);                               break;
                case "update_incident":      updateIncident(req, res, input);                            break;
                case "list_campaigns":       OutputProcessor.send(res, 200, listCampaigns());            break;
                case "add_campaign":         addCampaign(req, res, input);                               break;
                case "update_campaign":      updateCampaign(req, res, input);                            break;
                case "list_knowledge":       OutputProcessor.send(res, 200, listKnowledge(input));       break;
                case "add_knowledge":        addKnowledge(req, res, input);                              break;
                case "update_knowledge":     updateKnowledge(req, res, input);                           break;
                case "list_staff":           OutputProcessor.send(res, 200, listStaff());               break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getIncidentMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_incidents, " +
                "COUNT(*) FILTER (WHERE severity IN ('CRITICAL','HIGH') AND status NOT IN ('RESOLVED','CLOSED')) AS high_sev, " +
                "COUNT(*) FILTER (WHERE rca_root_cause IS NULL AND status NOT IN ('RESOLVED','CLOSED')) AS pending_rca, " +
                "COUNT(*) FILTER (WHERE status IN ('RESOLVED','CLOSED') AND resolved_at >= DATE_TRUNC('year', CURRENT_DATE)) AS closed_ytd " +
                "FROM incidents"
            ); rs = p.executeQuery();
            if (rs.next()) {
                result.put("open_incidents",  rs.getLong("open_incidents"));
                result.put("high_severity",   rs.getLong("high_sev"));
                result.put("pending_rca",     rs.getLong("pending_rca"));
                result.put("closed_ytd",      rs.getLong("closed_ytd"));
            }
            pool.cleanup(rs, p, null); rs=null; p=null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) AS total_campaigns, " +
                "COALESCE(ROUND(100.0 * SUM(CASE WHEN ce.status='COMPLETED' THEN 1 ELSE 0 END) / NULLIF(COUNT(ce.user_id),0)),0) AS completion_pct " +
                "FROM awareness_campaigns ac " +
                "LEFT JOIN campaign_enrollments ce ON ce.campaign_id = ac.id " +
                "WHERE ac.status = 'ACTIVE'"
            ); rs = p.executeQuery();
            if (rs.next()) { result.put("active_campaigns", rs.getLong("total_campaigns")); result.put("training_completion_pct", rs.getLong("completion_pct")); }
            pool.cleanup(rs, p, null); rs=null; p=null;

            p = conn.prepareStatement("SELECT COUNT(*) FROM best_practices_vault WHERE status='PUBLISHED'");
            rs = p.executeQuery(); result.put("published_articles", rs.next() ? rs.getLong(1) : 0L);

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listIncidents(JSONObject input) throws Exception {
        String status   = (String) input.get("status");
        String severity = (String) input.get("severity");
        StringBuilder sql = new StringBuilder(
            "SELECT id::text, title, description, severity, status, " +
            "rca_timeline, rca_business_impact, rca_root_cause, rca_preventative_actions, " +
            "created_at::text, resolved_at::text FROM incidents WHERE 1=1"
        );
        if (!isBlank(status))   sql.append(" AND status=?");
        if (!isBlank(severity)) sql.append(" AND severity=?");
        sql.append(" ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, created_at DESC LIMIT 100");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString()); int idx=1;
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(severity)) p.setString(idx++, severity);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject inc = new JSONObject();
                inc.put("id",                     rs.getString("id"));
                inc.put("title",                  rs.getString("title"));
                inc.put("description",            rs.getString("description"));
                inc.put("severity",               rs.getString("severity"));
                inc.put("status",                 rs.getString("status"));
                inc.put("rca_timeline",           rs.getString("rca_timeline"));
                inc.put("rca_business_impact",    rs.getString("rca_business_impact"));
                inc.put("rca_root_cause",         rs.getString("rca_root_cause"));
                inc.put("rca_preventative_actions", rs.getString("rca_preventative_actions"));
                inc.put("created_at",             rs.getString("created_at"));
                inc.put("resolved_at",            rs.getString("resolved_at"));
                list.add(inc);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("incidents", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addIncident(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title    = (String) input.get("title");
        String desc     = (String) input.get("description");
        String severity = (String) input.get("severity");
        if (isBlank(title) || isBlank(desc) || isBlank(severity)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title, description, severity required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO incidents (title,description,severity) VALUES (?,?,?) RETURNING id::text");
            p.setString(1,title); p.setString(2,desc); p.setString(3,severity.toUpperCase());
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateIncident(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String status = (String) input.get("status");
            String resolvedClause = (status != null && (status.equals("RESOLVED") || status.equals("CLOSED")))
                ? ", resolved_at = CURRENT_TIMESTAMP" : "";
            p = conn.prepareStatement(
                "UPDATE incidents SET status=COALESCE(?,status), " +
                "rca_timeline=COALESCE(?,rca_timeline), rca_business_impact=COALESCE(?,rca_business_impact), " +
                "rca_root_cause=COALESCE(?,rca_root_cause), rca_preventative_actions=COALESCE(?,rca_preventative_actions)" +
                resolvedClause + " WHERE id=?"
            );
            p.setString(1,(String)input.get("status"));
            p.setString(2,(String)input.get("rca_timeline")); p.setString(3,(String)input.get("rca_business_impact"));
            p.setString(4,(String)input.get("rca_root_cause")); p.setString(5,(String)input.get("rca_preventative_actions"));
            p.setObject(6, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listCampaigns() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT ac.id::text, ac.name, ac.description, ac.scheduled_start::text, ac.scheduled_end::text, ac.status, " +
                "COUNT(ce.user_id) AS enrolled, " +
                "COUNT(ce.user_id) FILTER (WHERE ce.status='COMPLETED') AS completed " +
                "FROM awareness_campaigns ac " +
                "LEFT JOIN campaign_enrollments ce ON ce.campaign_id = ac.id " +
                "GROUP BY ac.id, ac.name, ac.description, ac.scheduled_start, ac.scheduled_end, ac.status " +
                "ORDER BY ac.scheduled_start DESC"
            ); rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",              rs.getString("id"));
                c.put("name",            rs.getString("name"));
                c.put("description",     rs.getString("description"));
                c.put("scheduled_start", rs.getString("scheduled_start"));
                c.put("scheduled_end",   rs.getString("scheduled_end"));
                c.put("status",          rs.getString("status"));
                long enrolled  = rs.getLong("enrolled");
                long completed = rs.getLong("completed");
                c.put("enrolled",     enrolled);
                c.put("completed",    completed);
                c.put("completion_pct", enrolled > 0 ? (completed * 100 / enrolled) : 0L);
                list.add(c);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("campaigns", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addCampaign(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name  = (String) input.get("name");
        String start = (String) input.get("scheduled_start");
        String end   = (String) input.get("scheduled_end");
        if (isBlank(name) || isBlank(start) || isBlank(end)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name, scheduled_start, scheduled_end required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO awareness_campaigns (name,description,scheduled_start,scheduled_end) VALUES (?,?,?::date,?::date) RETURNING id::text");
            p.setString(1,name); p.setString(2,(String)input.get("description")); p.setString(3,start); p.setString(4,end);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateCampaign(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id"); String status = (String) input.get("status");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE awareness_campaigns SET status=COALESCE(?,status) WHERE id=?");
            p.setString(1, isBlank(status)?null:status); p.setObject(2, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listKnowledge(JSONObject input) throws Exception {
        String category = (String) input.get("category");
        String status   = (String) input.get("status");
        String search   = (String) input.get("search");
        StringBuilder sql = new StringBuilder(
            "SELECT bpv.id::text, bpv.title, bpv.content, bpv.category, bpv.audience, bpv.status, " +
            "bpv.created_at::text, bpv.updated_at::text, u.username AS author_name " +
            "FROM best_practices_vault bpv LEFT JOIN users u ON u.id = bpv.author_id WHERE 1=1"
        );
        if (!isBlank(category)) sql.append(" AND bpv.category=?");
        if (!isBlank(status))   sql.append(" AND bpv.status=?");
        else                    sql.append(" AND bpv.status != 'ARCHIVED'");
        if (!isBlank(search))   sql.append(" AND (bpv.title ILIKE ? OR bpv.content ILIKE ?)");
        sql.append(" ORDER BY bpv.updated_at DESC LIMIT 100");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString()); int idx=1;
            if (!isBlank(category)) p.setString(idx++, category);
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(search))   { String like="%"+search+"%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",          rs.getString("id"));
                a.put("title",       rs.getString("title"));
                a.put("content",     rs.getString("content"));
                a.put("category",    rs.getString("category"));
                a.put("audience",    rs.getString("audience"));
                a.put("status",      rs.getString("status"));
                a.put("created_at",  rs.getString("created_at"));
                a.put("updated_at",  rs.getString("updated_at"));
                a.put("author_name", rs.getString("author_name"));
                list.add(a);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("articles", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addKnowledge(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title   = (String) input.get("title");
        String content = (String) input.get("content");
        if (isBlank(title) || isBlank(content)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and content required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO best_practices_vault (title,content,category,audience,status,author_id) VALUES (?,?,?,?,?,?::uuid) RETURNING id::text");
            p.setString(1,title); p.setString(2,content);
            String cat=(String)input.get("category"); p.setString(3, isBlank(cat)?"General":cat);
            p.setString(4,(String)input.get("audience"));
            String st=(String)input.get("status"); p.setString(5, isBlank(st)?"DRAFT":st);
            String authorId=(String)input.get("author_id"); p.setString(6, isBlank(authorId)?null:authorId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateKnowledge(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE best_practices_vault SET title=COALESCE(?,title), content=COALESCE(?,content), category=COALESCE(?,category), audience=COALESCE(?,audience), status=COALESCE(?,status), updated_at=CURRENT_TIMESTAMP WHERE id=?");
            p.setString(1,(String)input.get("title")); p.setString(2,(String)input.get("content"));
            p.setString(3,(String)input.get("category")); p.setString(4,(String)input.get("audience"));
            p.setString(5,(String)input.get("status")); p.setObject(6, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
