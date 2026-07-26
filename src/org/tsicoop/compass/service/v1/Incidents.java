package org.tsicoop.compass.service.v1;

import org.tsicoop.compass.framework.Action;
import org.tsicoop.compass.framework.EventLog;
import org.tsicoop.compass.framework.InputProcessor;
import org.tsicoop.compass.framework.OutputProcessor;
import org.tsicoop.compass.framework.PoolDB;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

public class Incidents implements Action {

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".pdf",".doc",".docx",".xls",".xlsx",".ppt",".pptx",
        ".png",".jpg",".jpeg",".zip",".txt",".csv"
    ));

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
                case "list_staff":              OutputProcessor.send(res, 200, listStaff());                                      break;
                case "add_incident_document":   addDocument(req, res, input, "incident");                                         break;
                case "list_incident_documents": OutputProcessor.send(res, 200, listDocuments(input, "incident"));                 break;
                case "get_incident_document":   OutputProcessor.send(res, 200, getDocument(input, "incident"));                   break;
                case "add_kb_document":         addDocument(req, res, input, "kb");                                               break;
                case "list_kb_documents":       OutputProcessor.send(res, 200, listDocuments(input, "kb"));                       break;
                case "get_kb_document":         OutputProcessor.send(res, 200, getDocument(input, "kb"));                         break;
                case "add_campaign_document":   addDocument(req, res, input, "campaign");                                         break;
                case "list_campaign_documents": OutputProcessor.send(res, 200, listDocuments(input, "campaign"));                 break;
                case "get_campaign_document":   OutputProcessor.send(res, 200, getDocument(input, "campaign"));                   break;
                case "generate_rca_report":     generateRcaReport(req, res, input);                                               break;
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
        String search   = (String) input.get("search");
        boolean export  = Boolean.TRUE.equals(input.get("export"));

        long page  = 1L;
        long limit = 20L;
        Object pageObj  = input.get("page");
        Object limitObj = input.get("limit");
        if (pageObj  instanceof Long) page  = (Long) pageObj;
        if (limitObj instanceof Long) limit = (Long) limitObj;
        if (limit > 100) limit = 100;
        if (page < 1) page = 1;
        long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(status))   where.append(" AND status=?");
        if (!isBlank(severity)) where.append(" AND severity=?");
        if (!isBlank(search))   where.append(" AND (title ILIKE ? OR description ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            if (!export) {
                String countSql = "SELECT COUNT(*) FROM incidents" + where;
                p = conn.prepareStatement(countSql);
                int idx = 1;
                if (!isBlank(status))   p.setString(idx++, status);
                if (!isBlank(severity)) p.setString(idx++, severity);
                if (!isBlank(search))   { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
                rs = p.executeQuery();
                if (rs.next()) total = rs.getLong(1);
                try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
                rs = null; p = null;
            }

            StringBuilder dataSql = new StringBuilder(
                "SELECT id::text, title, description, severity, status, " +
                "rca_timeline, rca_business_impact, rca_root_cause, rca_preventative_actions, " +
                "created_at::text, resolved_at::text FROM incidents"
            );
            dataSql.append(where);
            dataSql.append(" ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, created_at DESC");
            if (!export) dataSql.append(" LIMIT ? OFFSET ?");

            p = conn.prepareStatement(dataSql.toString()); int idx=1;
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(severity)) p.setString(idx++, severity);
            if (!isBlank(search))   { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            if (!export) { p.setLong(idx++, limit); p.setLong(idx++, offset); }
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
        JSONObject result = new JSONObject(); result.put("success", true); result.put("incidents", list);
        if (!export) {
            result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
            result.put("total_pages", (total + limit - 1) / limit);
        }
        return result;
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
        String newId = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO awareness_campaigns (name,description,scheduled_start,scheduled_end) VALUES (?,?,?::date,?::date) RETURNING id::text");
            p.setString(1,name); p.setString(2,(String)input.get("description")); p.setString(3,start); p.setString(4,end);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) { newId = rs.getString(1); result.put("id", newId); }
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }

        if (newId != null) {
            enrollAllEmployees(newId);
            Notification.emitToRole("TRAINING_ASSIGNED", "USER", "New training assigned",
                "\"" + name + "\" awareness training has been assigned to you", "training.html", UUID.fromString(newId));
        }
    }

    // Awareness campaigns are org-wide: every active employee is auto-enrolled
    // when a campaign is created (there's no per-employee assignment step).
    private void enrollAllEmployees(String campaignId) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO campaign_enrollments (campaign_id, user_id) " +
                "SELECT ?::uuid, id FROM users WHERE role = 'USER' AND status = 'ACTIVE' " +
                "ON CONFLICT (campaign_id, user_id) DO NOTHING"
            );
            p.setString(1, campaignId);
            p.executeUpdate();
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
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

        long page  = 1L;
        long limit = 20L;
        Object pageObj  = input.get("page");
        Object limitObj = input.get("limit");
        if (pageObj  instanceof Long) page  = (Long) pageObj;
        if (limitObj instanceof Long) limit = (Long) limitObj;
        if (limit > 100) limit = 100;
        if (page < 1) page = 1;
        long offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!isBlank(category)) where.append(" AND bpv.category=?");
        if (!isBlank(status))   where.append(" AND bpv.status=?");
        else                    where.append(" AND bpv.status != 'ARCHIVED'");
        if (!isBlank(search))   where.append(" AND (bpv.title ILIKE ? OR bpv.content ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM best_practices_vault bpv" + where;
            p = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(category)) p.setString(idx++, category);
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(search))   { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT bpv.id::text, bpv.title, bpv.content, bpv.category, bpv.audience, bpv.status, " +
                "bpv.created_at::text, bpv.updated_at::text, u.username AS author_name " +
                "FROM best_practices_vault bpv LEFT JOIN users u ON u.id = bpv.author_id" + where +
                " ORDER BY bpv.updated_at DESC LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql); idx=1;
            if (!isBlank(category)) p.setString(idx++, category);
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(search))   { String like="%"+search+"%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
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
        JSONObject result = new JSONObject(); result.put("success", true); result.put("articles", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
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
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' AND role != 'USER' ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    @SuppressWarnings("unchecked")
    private void addDocument(HttpServletRequest req, HttpServletResponse res, JSONObject input, String type) throws Exception {
        String fileName  = strVal(input, "file_name");
        String fileData  = strVal(input, "file_data");
        String entityId  = strVal(input, "entity_id");
        if (isBlank(fileName) || isBlank(fileData) || isBlank(entityId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "file_name, file_data, entity_id required", req.getRequestURI()); return;
        }
        String safeName = new File(fileName).getName();
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 ? safeName.substring(dot).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "File type not allowed: " + ext, req.getRequestURI()); return;
        }
        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes = Base64.getDecoder().decode(raw.trim());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length()==1) sb.append('0'); sb.append(h); }
        String checksum = sb.toString();
        String uploadDir = System.getenv("TSI_EXPORT_PATH");
        if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
        File dir = new File(uploadDir + "/" + type + "_docs");
        if (!dir.exists() && !dir.mkdirs()) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", "Cannot create upload directory", req.getRequestURI()); return;
        }
        File dest = new File(dir, UUID.randomUUID().toString() + ext);
        try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }
        String table, col;
        switch (type) {
            case "incident": table = "incident_documents"; col = "incident_id"; break;
            case "kb":       table = "kb_documents";       col = "article_id";  break;
            case "campaign": table = "campaign_documents"; col = "campaign_id"; break;
            default: throw new IllegalArgumentException("Unknown document type");
        }
        String uploadedBy = strVal(input, "uploaded_by");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO " + table + " ("+col+", file_name, file_path, sha256_checksum, uploaded_by) " +
                "VALUES (?::uuid, ?, ?, ?, ?::uuid) RETURNING id::text"
            );
            p.setString(1, entityId); p.setString(2, safeName);
            p.setString(3, dest.getAbsolutePath()); p.setString(4, checksum);
            p.setString(5, isBlank(uploadedBy) ? null : uploadedBy);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            result.put("sha256_checksum", checksum);
            EventLog.log(InputProcessor.getEmail(req), type.toUpperCase()+"_DOC_UPLOADED",
                "{\"entity_id\":\""+entityId+"\",\"file_name\":\""+safeName+"\"}");
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) pool.cleanup(rs, p, conn); }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listDocuments(JSONObject input, String type) throws Exception {
        String entityId = strVal(input, "entity_id");
        if (isBlank(entityId)) throw new IllegalArgumentException("entity_id required");
        String table, col;
        switch (type) {
            case "incident": table = "incident_documents"; col = "incident_id"; break;
            case "kb":       table = "kb_documents";       col = "article_id";  break;
            case "campaign": table = "campaign_documents"; col = "campaign_id"; break;
            default: throw new IllegalArgumentException("Unknown document type");
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray docs = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT d.id::text, d.file_name, d.sha256_checksum, d.uploaded_at::text, u.username AS uploaded_by_name " +
                "FROM " + table + " d LEFT JOIN users u ON u.id = d.uploaded_by " +
                "WHERE d." + col + " = ?::uuid ORDER BY d.uploaded_at DESC"
            );
            p.setString(1, entityId); rs = p.executeQuery();
            while (rs.next()) {
                JSONObject doc = new JSONObject();
                doc.put("id",               rs.getString("id"));
                doc.put("file_name",        rs.getString("file_name"));
                doc.put("sha256_checksum",  rs.getString("sha256_checksum"));
                doc.put("uploaded_at",      rs.getString("uploaded_at"));
                doc.put("uploaded_by_name", rs.getString("uploaded_by_name"));
                docs.add(doc);
            }
        } finally { if (pool != null) pool.cleanup(rs, p, conn); }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("documents", docs); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject getDocument(JSONObject input, String type) throws Exception {
        String id = strVal(input, "id");
        if (isBlank(id)) throw new IllegalArgumentException("id required");
        String table;
        switch (type) {
            case "incident": table = "incident_documents"; break;
            case "kb":       table = "kb_documents";       break;
            case "campaign": table = "campaign_documents"; break;
            default: throw new IllegalArgumentException("Unknown document type");
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT file_name, file_path FROM " + table + " WHERE id = ?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Document not found");
            String fileName = rs.getString("file_name");
            String filePath = rs.getString("file_path");
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) throw new IllegalArgumentException("File not found on server");
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("file_name", fileName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            return result;
        } finally { if (pool != null) pool.cleanup(rs, p, conn); }
    }

    @SuppressWarnings("unchecked")
    private void generateRcaReport(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT title, description, severity, status, " +
                "rca_timeline, rca_business_impact, rca_root_cause, rca_preventative_actions, " +
                "TO_CHAR(created_at, 'DD Mon YYYY') AS reported_on, " +
                "TO_CHAR(resolved_at, 'DD Mon YYYY') AS resolved_on " +
                "FROM incidents WHERE id = ?::uuid"
            );
            p.setString(1, id); rs = p.executeQuery();
            if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not Found", "Incident not found", req.getRequestURI()); return; }
            String title      = coalesce(rs.getString("title"),                     "Untitled Incident");
            String desc       = coalesce(rs.getString("description"),               "Not recorded.");
            String severity   = coalesce(rs.getString("severity"),                  "—");
            String status     = coalesce(rs.getString("status"),                    "—");
            String timeline   = coalesce(rs.getString("rca_timeline"),              "Not recorded.");
            String impact     = coalesce(rs.getString("rca_business_impact"),       "Not recorded.");
            String rootCause  = coalesce(rs.getString("rca_root_cause"),            "Not recorded.");
            String prevention = coalesce(rs.getString("rca_preventative_actions"), "Not recorded.");
            String reportedOn = coalesce(rs.getString("reported_on"),               "—");
            String resolvedOn = coalesce(rs.getString("resolved_on"),               "Pending");
            byte[] pdf = buildRcaPdf(title, desc, severity, status, timeline, impact, rootCause, prevention, reportedOn, resolvedOn);
            String safeTitle = title.replaceAll("[^a-zA-Z0-9 ]", "").trim().replaceAll("\\s+", "-").toLowerCase();
            if (safeTitle.length() > 40) safeTitle = safeTitle.substring(0, 40);
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("file_name", "rca-" + safeTitle + ".pdf");
            result.put("file_data", Base64.getEncoder().encodeToString(pdf));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    private byte[] buildRcaPdf(String title, String desc, String severity, String status,
            String timeline, String impact, String rootCause, String prevention,
            String reportedOn, String resolvedOn) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 45, 45, 55, 55);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Color TEAL  = new Color(0, 106, 103);
        Color INK   = new Color(23, 32, 51);
        Color MUTED = new Color(96, 112, 134);
        Color LINE  = new Color(217, 226, 236);
        Color WASH  = new Color(243, 247, 248);
        Color WHITE = Color.WHITE;

        Font fTitle    = new Font(Font.HELVETICA, 20, Font.BOLD,   WHITE);
        Font fSub      = new Font(Font.HELVETICA,  9, Font.NORMAL, new Color(180, 220, 218));
        Font fGen      = new Font(Font.HELVETICA,  7, Font.NORMAL, new Color(150, 200, 198));
        Font fSec      = new Font(Font.HELVETICA, 10, Font.BOLD,   TEAL);
        Font fLbl      = new Font(Font.HELVETICA,  7, Font.BOLD,   MUTED);
        Font fVal      = new Font(Font.HELVETICA,  9, Font.NORMAL, INK);
        Font fBody     = new Font(Font.HELVETICA,  9, Font.NORMAL, INK);
        Font fIncTitle = new Font(Font.HELVETICA, 12, Font.BOLD,   INK);
        Font fFoot     = new Font(Font.HELVETICA,  7, Font.ITALIC, MUTED);

        // ── Header banner ──
        PdfPTable banner = new PdfPTable(2);
        banner.setWidthPercentage(100); banner.setWidths(new float[]{1.6f, 1f}); banner.setSpacingAfter(20);
        PdfPCell bLeft = new PdfPCell(); bLeft.setBackgroundColor(TEAL); bLeft.setBorder(Rectangle.NO_BORDER); bLeft.setPadding(16);
        bLeft.addElement(new Paragraph("TSI Compass", fTitle));
        bLeft.addElement(new Paragraph("Incident Root Cause Analysis", fSub));
        banner.addCell(bLeft);
        PdfPCell bRight = new PdfPCell(); bRight.setBackgroundColor(TEAL); bRight.setBorder(Rectangle.NO_BORDER); bRight.setPadding(16);
        bRight.setHorizontalAlignment(Element.ALIGN_RIGHT); bRight.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph pType = new Paragraph("RCA REPORT", new Font(Font.HELVETICA, 13, Font.BOLD, WHITE)); pType.setAlignment(Element.ALIGN_RIGHT); bRight.addElement(pType);
        Paragraph pGen = new Paragraph("Generated: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")), fGen); pGen.setAlignment(Element.ALIGN_RIGHT); bRight.addElement(pGen);
        banner.addCell(bRight);
        doc.add(banner);

        // ── Incident title block ──
        PdfPTable titleBox = new PdfPTable(1); titleBox.setWidthPercentage(100); titleBox.setSpacingAfter(14);
        PdfPCell titleCell = new PdfPCell(); titleCell.setPadding(12); titleCell.setBackgroundColor(WASH); titleCell.setBorderColor(LINE);
        Paragraph incLbl = new Paragraph("INCIDENT", fLbl); incLbl.setSpacingAfter(3); titleCell.addElement(incLbl);
        titleCell.addElement(new Paragraph(title, fIncTitle));
        titleBox.addCell(titleCell);
        doc.add(titleBox);

        // ── Details row ──
        rcaSectionTitle(doc, "DETAILS", fSec);
        PdfPTable detRow = new PdfPTable(4); detRow.setWidthPercentage(100); detRow.setWidths(new float[]{1f,1f,1f,1f}); detRow.setSpacingAfter(14);
        rcaMetric(detRow, "SEVERITY", severity,   fLbl, fVal, WASH, LINE);
        rcaMetric(detRow, "STATUS",   status,     fLbl, fVal, WASH, LINE);
        rcaMetric(detRow, "REPORTED", reportedOn, fLbl, fVal, WASH, LINE);
        rcaMetric(detRow, "RESOLVED", resolvedOn, fLbl, fVal, WASH, LINE);
        doc.add(detRow);

        // ── Description ──
        rcaSectionTitle(doc, "DESCRIPTION", fSec);
        rcaTextBlock(doc, desc, fBody, WASH, LINE);

        // ── Root Cause Analysis ──
        rcaSectionTitle(doc, "ROOT CAUSE ANALYSIS", fSec);
        rcaSubSection(doc, "Timeline of Events",    timeline,   fLbl, fBody, WASH, LINE);
        rcaSubSection(doc, "Business Impact",       impact,     fLbl, fBody, WASH, LINE);
        rcaSubSection(doc, "Root Cause",            rootCause,  fLbl, fBody, WASH, LINE);
        rcaSubSection(doc, "Preventative Actions",  prevention, fLbl, fBody, WASH, LINE);

        // ── Footer ──
        Paragraph footer = new Paragraph(
            "Generated automatically by TSI Compass GRC Platform. Data reflects system state at time of generation.", fFoot);
        footer.setSpacingBefore(18); doc.add(footer);

        doc.close();
        return baos.toByteArray();
    }

    private void rcaSectionTitle(Document doc, String text, Font font) throws Exception {
        Paragraph p = new Paragraph(text, font); p.setSpacingBefore(12); p.setSpacingAfter(4); doc.add(p);
    }

    private void rcaMetric(PdfPTable table, String label, String value, Font fLbl, Font fVal, Color wash, Color line) {
        PdfPCell cell = new PdfPCell(); cell.setPadding(10); cell.setBackgroundColor(wash); cell.setBorderColor(line);
        Paragraph lp = new Paragraph(label, fLbl); lp.setSpacingAfter(3); cell.addElement(lp);
        cell.addElement(new Paragraph(value, fVal));
        table.addCell(cell);
    }

    private void rcaTextBlock(Document doc, String text, Font font, Color wash, Color line) throws Exception {
        PdfPTable box = new PdfPTable(1); box.setWidthPercentage(100); box.setSpacingAfter(10);
        PdfPCell cell = new PdfPCell(); cell.setPadding(10); cell.setBackgroundColor(wash); cell.setBorderColor(line);
        cell.addElement(new Paragraph(text, font));
        box.addCell(cell);
        doc.add(box);
    }

    private void rcaSubSection(Document doc, String heading, String text, Font fHead, Font fBody, Color wash, Color line) throws Exception {
        Paragraph h = new Paragraph(heading, fHead); h.setSpacingBefore(8); h.setSpacingAfter(3); doc.add(h);
        rcaTextBlock(doc, text, fBody, wash, line);
    }

    private String coalesce(String val, String fallback) { return isBlank(val) ? fallback : val; }

    private String strVal(JSONObject obj, String key) { Object v = obj.get(key); return v == null ? null : v.toString().trim(); }
    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
