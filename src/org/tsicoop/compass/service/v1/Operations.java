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

public class Operations implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing _func", req.getRequestURI()); return;
            }
            switch (func.toLowerCase()) {
                case "get_ops_metrics":    OutputProcessor.send(res, 200, getOpsMetrics());       break;
                case "list_changes":       OutputProcessor.send(res, 200, listChanges(input));    break;
                case "add_change":         addChange(req, res, input);                            break;
                case "update_change":      updateChange(req, res, input);                         break;
                case "list_assets":        OutputProcessor.send(res, 200, listAssets(input));     break;
                case "add_asset":          addAsset(req, res, input);                             break;
                case "update_asset":       updateAsset(req, res, input);                          break;
                case "list_vendors":       OutputProcessor.send(res, 200, listVendors());         break;
                case "add_vendor":         addVendor(req, res, input);                            break;
                case "update_vendor":      updateVendor(req, res, input);                         break;
                case "list_tickets":       OutputProcessor.send(res, 200, listTickets(input));    break;
                case "add_ticket":         addTicket(req, res, input);                            break;
                case "update_ticket":      updateTicket(req, res, input);                         break;
                case "list_staff":         OutputProcessor.send(res, 200, listStaff());           break;
                default: OutputProcessor.errorResponse(res, 400, "Bad Request", "Unknown: "+func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse r) { return "POST".equalsIgnoreCase(m); }

    @SuppressWarnings("unchecked")
    private JSONObject getOpsMetrics() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONObject result = new JSONObject();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status NOT IN ('COMPLETED','REJECTED')) AS open_crs, " +
                "COUNT(*) FILTER (WHERE status='APPROVED') AS pending_approval, " +
                "COUNT(*) FILTER (WHERE status='COMPLETED') AS completed " +
                "FROM change_requests"
            ); rs = p.executeQuery();
            if (rs.next()) { result.put("open_changes",rs.getLong("open_crs")); result.put("pending_approval",rs.getLong("pending_approval")); result.put("completed_changes",rs.getLong("completed")); }
            pool.cleanup(rs, p, null); rs=null; p=null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE criticality='CRITICAL') AS critical, COUNT(*) AS total FROM assets"
            ); rs = p.executeQuery();
            if (rs.next()) { result.put("critical_assets",rs.getLong("critical")); result.put("total_assets",rs.getLong("total")); }
            pool.cleanup(rs, p, null); rs=null; p=null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE license_expiry < CURRENT_DATE + 90) AS due_review, COUNT(*) AS total FROM vendors"
            ); rs = p.executeQuery();
            if (rs.next()) { result.put("vendor_reviews_due",rs.getLong("due_review")); result.put("total_vendors",rs.getLong("total")); }
            pool.cleanup(rs, p, null); rs=null; p=null;

            p = conn.prepareStatement(
                "SELECT COUNT(*) FILTER (WHERE status IN ('OPEN','IN_PROGRESS')) AS open_tickets, " +
                "COUNT(*) FILTER (WHERE status IN ('OPEN','IN_PROGRESS') AND priority IN ('CRITICAL','HIGH')) AS high_priority " +
                "FROM helpdesk_tickets"
            ); rs = p.executeQuery();
            if (rs.next()) { result.put("open_tickets",rs.getLong("open_tickets")); result.put("high_priority_tickets",rs.getLong("high_priority")); }

        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        result.put("success", true); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listChanges(JSONObject input) throws Exception {
        String status = (String) input.get("status");
        StringBuilder sql = new StringBuilder(
            "SELECT cr.id::text, cr.title, cr.description, cr.stage, cr.status, cr.created_at::text, " +
            "req.username AS requester_name, apr.username AS approver_name " +
            "FROM change_requests cr " +
            "LEFT JOIN users req ON req.id = cr.requester_id " +
            "LEFT JOIN users apr ON apr.id = cr.compliance_approver_id WHERE 1=1"
        );
        if (!isBlank(status)) sql.append(" AND cr.status=?");
        sql.append(" ORDER BY cr.created_at DESC LIMIT 80");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString());
            if (!isBlank(status)) p.setString(1, status);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject cr = new JSONObject();
                cr.put("id",             rs.getString("id"));
                cr.put("title",          rs.getString("title"));
                cr.put("description",    rs.getString("description"));
                cr.put("stage",          rs.getString("stage"));
                cr.put("status",         rs.getString("status"));
                cr.put("created_at",     rs.getString("created_at"));
                cr.put("requester_name", rs.getString("requester_name"));
                cr.put("approver_name",  rs.getString("approver_name"));
                list.add(cr);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("changes", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addChange(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title = (String) input.get("title");
        String desc  = (String) input.get("description");
        if (isBlank(title) || isBlank(desc)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and description required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO change_requests (title,description,requester_id) VALUES (?,?,?::uuid) RETURNING id::text");
            p.setString(1, title); p.setString(2, desc);
            String reqId = (String) input.get("requester_id");
            p.setString(3, isBlank(reqId) ? null : reqId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateChange(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE change_requests SET status=COALESCE(?,status), stage=COALESCE(?,stage) WHERE id=?");
            p.setString(1, (String) input.get("status")); p.setString(2, (String) input.get("stage"));
            p.setObject(3, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listAssets(JSONObject input) throws Exception {
        String criticality = (String) input.get("criticality");
        String category    = (String) input.get("category");
        StringBuilder sql = new StringBuilder(
            "SELECT a.id::text, a.name, a.category, a.criticality, a.description, " +
            "a.patch_status, a.lifecycle_status, u.username AS owner_name, a.owner_id::text " +
            "FROM assets a LEFT JOIN users u ON u.id = a.owner_id WHERE 1=1"
        );
        if (!isBlank(criticality)) sql.append(" AND a.criticality=?");
        if (!isBlank(category))    sql.append(" AND a.category=?");
        sql.append(" ORDER BY CASE a.criticality WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, a.name");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString()); int idx=1;
            if (!isBlank(criticality)) p.setString(idx++, criticality);
            if (!isBlank(category))    p.setString(idx++, category);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",               rs.getString("id"));
                a.put("name",             rs.getString("name"));
                a.put("category",         rs.getString("category"));
                a.put("criticality",      rs.getString("criticality"));
                a.put("description",      rs.getString("description"));
                a.put("patch_status",     rs.getString("patch_status"));
                a.put("lifecycle_status", rs.getString("lifecycle_status"));
                a.put("owner_name",       rs.getString("owner_name"));
                a.put("owner_id",         rs.getString("owner_id"));
                list.add(a);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("assets", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name  = (String) input.get("name");
        String cat   = (String) input.get("category");
        String crit  = (String) input.get("criticality");
        if (isBlank(name) || isBlank(cat) || isBlank(crit)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name, category, criticality required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO assets (name,category,criticality,description,owner_id,patch_status,lifecycle_status) VALUES (?,?,?,?,?::uuid,?,?) RETURNING id::text");
            p.setString(1,name); p.setString(2,cat); p.setString(3,crit);
            p.setString(4,(String)input.get("description"));
            String ownerId=(String)input.get("owner_id"); p.setString(5, isBlank(ownerId)?null:ownerId);
            String ps=(String)input.get("patch_status"); p.setString(6, isBlank(ps)?"CURRENT":ps);
            String ls=(String)input.get("lifecycle_status"); p.setString(7, isBlank(ls)?"ACTIVE":ls);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE assets SET name=COALESCE(?,name), criticality=COALESCE(?,criticality), description=COALESCE(?,description), patch_status=COALESCE(?,patch_status), lifecycle_status=COALESCE(?,lifecycle_status), owner_id=COALESCE(?::uuid,owner_id) WHERE id=?");
            p.setString(1,(String)input.get("name")); p.setString(2,(String)input.get("criticality"));
            p.setString(3,(String)input.get("description")); p.setString(4,(String)input.get("patch_status"));
            p.setString(5,(String)input.get("lifecycle_status")); p.setString(6,(String)input.get("owner_id"));
            p.setObject(7, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listVendors() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT id::text, name, agreement_details, license_expiry::text, baseline_risk_score, " +
                "security_questionnaire_status, created_at::text, " +
                "(license_expiry < CURRENT_DATE + 90) AS review_due " +
                "FROM vendors ORDER BY CASE WHEN license_expiry < CURRENT_DATE THEN 0 WHEN license_expiry < CURRENT_DATE+90 THEN 1 ELSE 2 END, name"
            ); rs = p.executeQuery();
            while (rs.next()) {
                JSONObject v = new JSONObject();
                v.put("id",                           rs.getString("id"));
                v.put("name",                         rs.getString("name"));
                v.put("agreement_details",            rs.getString("agreement_details"));
                v.put("license_expiry",               rs.getString("license_expiry"));
                v.put("baseline_risk_score",          rs.getObject("baseline_risk_score") != null ? (long)rs.getInt("baseline_risk_score") : null);
                v.put("security_questionnaire_status",rs.getString("security_questionnaire_status"));
                v.put("created_at",                   rs.getString("created_at"));
                v.put("review_due",                   rs.getBoolean("review_due"));
                list.add(v);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("vendors", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addVendor(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name = (String) input.get("name");
        if (isBlank(name)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "name required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO vendors (name,agreement_details,license_expiry,baseline_risk_score,security_questionnaire_status) VALUES (?,?,?::date,?,?) RETURNING id::text");
            p.setString(1,name); p.setString(2,(String)input.get("agreement_details"));
            String exp=(String)input.get("license_expiry"); p.setString(3, isBlank(exp)?null:exp);
            Object score=input.get("baseline_risk_score");
            if(score!=null) p.setInt(4,((Number)score).intValue()); else p.setNull(4,Types.INTEGER);
            p.setString(5,(String)input.get("security_questionnaire_status"));
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateVendor(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE vendors SET name=COALESCE(?,name), agreement_details=COALESCE(?,agreement_details), license_expiry=COALESCE(?::date,license_expiry), security_questionnaire_status=COALESCE(?,security_questionnaire_status) WHERE id=?");
            p.setString(1,(String)input.get("name")); p.setString(2,(String)input.get("agreement_details"));
            String exp=(String)input.get("license_expiry"); p.setString(3, isBlank(exp)?null:exp);
            p.setString(4,(String)input.get("security_questionnaire_status")); p.setObject(5, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listTickets(JSONObject input) throws Exception {
        String status   = (String) input.get("status");
        String priority = (String) input.get("priority");
        StringBuilder sql = new StringBuilder(
            "SELECT ht.id::text, ht.title, ht.description, ht.status, ht.priority, ht.created_at::text, " +
            "a.name AS asset_name, cb.username AS created_by_name, at.username AS assigned_to_name, ht.assigned_to::text " +
            "FROM helpdesk_tickets ht " +
            "LEFT JOIN assets a ON a.id = ht.asset_id " +
            "LEFT JOIN users cb ON cb.id = ht.created_by " +
            "LEFT JOIN users at ON at.id = ht.assigned_to WHERE 1=1"
        );
        if (!isBlank(status))   sql.append(" AND ht.status=?");
        if (!isBlank(priority)) sql.append(" AND ht.priority=?");
        sql.append(" ORDER BY CASE ht.priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, ht.created_at DESC LIMIT 100");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(sql.toString()); int idx=1;
            if (!isBlank(status))   p.setString(idx++, status);
            if (!isBlank(priority)) p.setString(idx++, priority);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject t = new JSONObject();
                t.put("id",               rs.getString("id"));
                t.put("title",            rs.getString("title"));
                t.put("description",      rs.getString("description"));
                t.put("status",           rs.getString("status"));
                t.put("priority",         rs.getString("priority"));
                t.put("created_at",       rs.getString("created_at"));
                t.put("asset_name",       rs.getString("asset_name"));
                t.put("created_by_name",  rs.getString("created_by_name"));
                t.put("assigned_to_name", rs.getString("assigned_to_name"));
                t.put("assigned_to",      rs.getString("assigned_to"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("tickets", list); return result;
    }

    @SuppressWarnings("unchecked")
    private void addTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title    = (String) input.get("title");
        String desc     = (String) input.get("description");
        String priority = (String) input.get("priority");
        if (isBlank(title) || isBlank(desc)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title and description required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO helpdesk_tickets (title,description,priority,created_by,assigned_to,asset_id) VALUES (?,?,?::varchar,?::uuid,?::uuid,?::uuid) RETURNING id::text");
            p.setString(1,title); p.setString(2,desc); p.setString(3, isBlank(priority)?"MEDIUM":priority);
            String createdBy=(String)input.get("created_by"); p.setString(4, isBlank(createdBy)?null:createdBy);
            String assignedTo=(String)input.get("assigned_to"); p.setString(5, isBlank(assignedTo)?null:assignedTo);
            String assetId=(String)input.get("asset_id"); p.setString(6, isBlank(assetId)?null:assetId);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE helpdesk_tickets SET status=COALESCE(?,status), priority=COALESCE(?,priority), assigned_to=COALESCE(?::uuid,assigned_to) WHERE id=?");
            p.setString(1,(String)input.get("status")); p.setString(2,(String)input.get("priority"));
            String at=(String)input.get("assigned_to"); p.setString(3, isBlank(at)?null:at);
            p.setObject(4, UUID.fromString(id)); p.executeUpdate();
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
