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

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
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
            String role = InputProcessor.getRole(req);
            switch (func.toLowerCase()) {
                case "get_ops_metrics":    OutputProcessor.send(res, 200, getOpsMetrics());       break;
                case "list_changes":       OutputProcessor.send(res, 200, listChanges(input, role)); break;
                case "add_change":         addChange(req, res, input);                            break;
                case "update_change":      updateChange(req, res, input);                         break;
                case "list_assets":        OutputProcessor.send(res, 200, listAssets(input));     break;
                case "add_asset":          addAsset(req, res, input);                             break;
                case "update_asset":       updateAsset(req, res, input);                          break;
                case "list_vendors":       OutputProcessor.send(res, 200, listVendors(input));    break;
                case "add_vendor":         addVendor(req, res, input);                            break;
                case "update_vendor":      updateVendor(req, res, input);                         break;
                case "list_tickets":       OutputProcessor.send(res, 200, listTickets(input, role)); break;
                case "add_ticket":         addTicket(req, res, input);                            break;
                case "update_ticket":      updateTicket(req, res, input);                         break;
                case "escalate_ticket":    escalateTicket(req, res, input);                       break;
                case "list_staff":         OutputProcessor.send(res, 200, listStaff());           break;
                case "list_ticket_categories": OutputProcessor.send(res, 200, listTicketCategories()); break;
                case "list_ticket_subcategories": OutputProcessor.send(res, 200, listTicketSubcategories(input)); break;
                case "list_asset_categories": OutputProcessor.send(res, 200, listAssetCategories()); break;
                case "delete_change":      deleteChange(req, res, input);                         break;
                case "delete_asset":       deleteAsset(req, res, input);                          break;
                case "delete_vendor":      deleteVendor(req, res, input);                         break;
                case "delete_ticket":      deleteTicket(req, res, input);                         break;
                case "import_changes":     importChanges(req, res, input);                        break;
                case "import_assets":      importAssets(req, res, input);                         break;
                case "import_vendors":     importVendors(req, res, input);                        break;
                case "import_tickets":     importTickets(req, res, input);                        break;
                case "list_ticket_attachments": listTicketAttachments(req, res, input);            break;
                case "get_ticket_attachment":   getTicketAttachment(req, res, input);              break;
                case "upload_change_attachment": uploadChangeAttachment(req, res, input);           break;
                case "list_change_attachments": listChangeAttachments(req, res, input);            break;
                case "get_change_attachment":   getChangeAttachment(req, res, input);              break;
                case "list_change_comments":    listChangeComments(req, res, input);               break;
                case "add_change_comment":      addChangeComment(req, res, input);                 break;
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
    private JSONObject listChanges(JSONObject input, String role) throws Exception {
        String status = (String) input.get("status");
        String stage  = (String) input.get("stage");
        String search = (String) input.get("search");

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
        if (!isBlank(status)) where.append(" AND cr.status=?");
        if (!isBlank(stage))  where.append(" AND cr.stage=?");
        if (!isBlank(search)) where.append(" AND (cr.title ILIKE ? OR cr.description ILIKE ?)");
        // Change requests awaiting Supervisor approval are invisible to IT/GRC
        // queues entirely (not present-but-locked) until a Supervisor approves them.
        if ("IT_STAFF".equals(role) || "GRC_OFFICER".equals(role)) where.append(" AND cr.approval_status != 'PENDING'");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            String countSql = "SELECT COUNT(*) FROM change_requests cr" + where;
            p = conn.prepareStatement(countSql);
            int idx = 1;
            if (!isBlank(status)) p.setString(idx++, status);
            if (!isBlank(stage))  p.setString(idx++, stage);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            rs = p.executeQuery();
            if (rs.next()) total = rs.getLong(1);
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            String dataSql =
                "SELECT cr.id::text, cr.title, cr.description, cr.stage, cr.status, " +
                "TO_CHAR(cr.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at, " +
                "req.username AS requester_name, apr.username AS approver_name " +
                "FROM change_requests cr " +
                "LEFT JOIN users req ON req.id = cr.requester_id " +
                "LEFT JOIN users apr ON apr.id = cr.compliance_approver_id" + where +
                " ORDER BY cr.created_at DESC LIMIT ? OFFSET ?";
            p = conn.prepareStatement(dataSql); idx = 1;
            if (!isBlank(status)) p.setString(idx++, status);
            if (!isBlank(stage))  p.setString(idx++, stage);
            if (!isBlank(search)) { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            p.setLong(idx++, limit); p.setLong(idx++, offset);
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
        JSONObject result = new JSONObject(); result.put("success", true); result.put("changes", list);
        result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
        result.put("total_pages", (total + limit - 1) / limit);
        return result;
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
        String newStatus = (String) input.get("status");
        String newStage  = (String) input.get("stage");

        JSONObject before = getChangeSnapshot(id);

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            // Excludes PENDING rows so IT/GRC can't act on a change request out-of-band
            // (e.g. a stale tab) before a Supervisor has approved it.
            p = conn.prepareStatement("UPDATE change_requests SET status=COALESCE(?,status), stage=COALESCE(?,stage) WHERE id=? AND approval_status != 'PENDING'");
            p.setString(1, newStatus); p.setString(2, newStage);
            p.setObject(3, UUID.fromString(id));
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found or awaiting supervisor approval", req.getRequestURI()); return;
            }
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }

        if (before != null) {
            String requesterId = (String) before.get("requester_id");
            String title       = (String) before.get("title");
            String oldStatus   = (String) before.get("status");
            String oldStage    = (String) before.get("stage");
            String finalStatus = isBlank(newStatus) ? oldStatus : newStatus;
            String finalStage  = isBlank(newStage)  ? oldStage  : newStage;
            boolean changed = !java.util.Objects.equals(finalStatus, oldStatus) || !java.util.Objects.equals(finalStage, oldStage);
            if (changed && requesterId != null) {
                Notification.emitToUser("CHANGE_REQUEST_UPDATED", title,
                    "\"" + title + "\" is now " + finalStatus + (isBlank(finalStage) ? "" : " (" + finalStage + ")"),
                    "index.html", UUID.fromString(id), UUID.fromString(requesterId));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject getChangeSnapshot(String id) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title, status, stage, requester_id::text FROM change_requests WHERE id = ?");
            p.setObject(1, UUID.fromString(id));
            rs = p.executeQuery();
            if (!rs.next()) return null;
            JSONObject snap = new JSONObject();
            snap.put("title", rs.getString("title"));
            snap.put("status", rs.getString("status"));
            snap.put("stage", rs.getString("stage"));
            snap.put("requester_id", rs.getString("requester_id"));
            return snap;
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listAssets(JSONObject input) throws Exception {
        String criticality = (String) input.get("criticality");
        String categoryId  = (String) input.get("category_id");
        String patchStatus = (String) input.get("patch_status");
        String search       = (String) input.get("search");
        boolean export = Boolean.TRUE.equals(input.get("export"));

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
        if (!isBlank(criticality)) where.append(" AND a.criticality=?");
        if (!isBlank(categoryId))  where.append(" AND a.category_id=?::uuid");
        if (!isBlank(patchStatus)) where.append(" AND a.patch_status=?");
        if (!isBlank(search))      where.append(" AND (a.name ILIKE ? OR a.description ILIKE ? OR a.asset_tag ILIKE ? OR a.location ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            if (!export) {
                String countSql = "SELECT COUNT(*) FROM assets a" + where;
                p = conn.prepareStatement(countSql);
                int idx = 1;
                if (!isBlank(criticality)) p.setString(idx++, criticality);
                if (!isBlank(categoryId))  p.setString(idx++, categoryId);
                if (!isBlank(patchStatus)) p.setString(idx++, patchStatus);
                if (!isBlank(search))      { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
                rs = p.executeQuery();
                if (rs.next()) total = rs.getLong(1);
                try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
                rs = null; p = null;
            }

            String dataSql =
                "SELECT a.id::text, a.name, a.asset_tag, a.location, ac.id::text AS category_id, ac.name AS category_name, a.criticality, a.description, " +
                "a.patch_status, a.lifecycle_status, u.username AS owner_name, a.owner_id::text " +
                "FROM assets a LEFT JOIN users u ON u.id = a.owner_id LEFT JOIN asset_categories ac ON ac.id = a.category_id" + where +
                " ORDER BY CASE a.criticality WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, a.name" +
                (export ? "" : " LIMIT ? OFFSET ?");
            p = conn.prepareStatement(dataSql); int idx = 1;
            if (!isBlank(criticality)) p.setString(idx++, criticality);
            if (!isBlank(categoryId))  p.setString(idx++, categoryId);
            if (!isBlank(patchStatus)) p.setString(idx++, patchStatus);
            if (!isBlank(search))      { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
            if (!export) { p.setLong(idx++, limit); p.setLong(idx++, offset); }
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",               rs.getString("id"));
                a.put("name",             rs.getString("name"));
                a.put("asset_tag",        rs.getString("asset_tag"));
                a.put("location",         rs.getString("location"));
                a.put("category_id",      rs.getString("category_id"));
                a.put("category_name",    rs.getString("category_name"));
                a.put("criticality",      rs.getString("criticality"));
                a.put("description",      rs.getString("description"));
                a.put("patch_status",     rs.getString("patch_status"));
                a.put("lifecycle_status", rs.getString("lifecycle_status"));
                a.put("owner_name",       rs.getString("owner_name"));
                a.put("owner_id",         rs.getString("owner_id"));
                list.add(a);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("assets", list);
        if (!export) {
            result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
            result.put("total_pages", (total + limit - 1) / limit);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String name       = (String) input.get("name");
        String assetTag   = (String) input.get("asset_tag");
        String categoryId = (String) input.get("category_id");
        String crit       = (String) input.get("criticality");
        if (isBlank(name) || isBlank(assetTag) || isBlank(categoryId) || isBlank(crit)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "name, asset_tag, category_id, criticality required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO assets (name,asset_tag,location,category_id,criticality,description,owner_id,patch_status,lifecycle_status) VALUES (?,?,?,?::uuid,?,?,?::uuid,?,?) RETURNING id::text");
            p.setString(1,name); p.setString(2,assetTag.trim());
            p.setString(3,(String)input.get("location"));
            p.setString(4,categoryId); p.setString(5,crit);
            p.setString(6,(String)input.get("description"));
            String ownerId=(String)input.get("owner_id"); p.setString(7, isBlank(ownerId)?null:ownerId);
            String ps=(String)input.get("patch_status"); p.setString(8, isBlank(ps)?"CURRENT":ps);
            String ls=(String)input.get("lifecycle_status"); p.setString(9, isBlank(ls)?"ACTIVE":ls);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "An asset with that asset tag already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void updateAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("UPDATE assets SET name=COALESCE(?,name), asset_tag=COALESCE(?,asset_tag), location=COALESCE(?,location), category_id=COALESCE(?::uuid,category_id), criticality=COALESCE(?,criticality), description=COALESCE(?,description), patch_status=COALESCE(?,patch_status), lifecycle_status=COALESCE(?,lifecycle_status), owner_id=COALESCE(?::uuid,owner_id) WHERE id=?");
            String assetTag=(String)input.get("asset_tag"); p.setString(1,(String)input.get("name")); p.setString(2, isBlank(assetTag)?null:assetTag.trim());
            p.setString(3,(String)input.get("location")); p.setString(4,(String)input.get("category_id"));
            p.setString(5,(String)input.get("criticality"));
            p.setString(6,(String)input.get("description")); p.setString(7,(String)input.get("patch_status"));
            p.setString(8,(String)input.get("lifecycle_status")); p.setString(9,(String)input.get("owner_id"));
            p.setObject(10, UUID.fromString(id)); p.executeUpdate();
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("unique") || msg.contains("duplicate")) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "An asset with that asset tag already exists", req.getRequestURI());
            } else {
                throw e;
            }
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listVendors(JSONObject input) throws Exception {
        String search = (String) input.get("search");
        String qStatus = (String) input.get("questionnaire_status");
        boolean dueOnly = "1".equals(String.valueOf(input.get("due")));
        boolean export = Boolean.TRUE.equals(input.get("export"));

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
        if (!isBlank(qStatus)) where.append(" AND security_questionnaire_status=?");
        if (dueOnly)           where.append(" AND license_expiry < CURRENT_DATE + 90");
        if (!isBlank(search))  where.append(" AND (name ILIKE ? OR agreement_details ILIKE ?)");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            if (!export) {
                String countSql = "SELECT COUNT(*) FROM vendors" + where;
                p = conn.prepareStatement(countSql);
                int idx = 1;
                if (!isBlank(qStatus)) p.setString(idx++, qStatus);
                if (!isBlank(search))  { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
                rs = p.executeQuery();
                if (rs.next()) total = rs.getLong(1);
                try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
                rs = null; p = null;
            }

            String dataSql =
                "SELECT id::text, name, agreement_details, license_expiry::text, baseline_risk_score, " +
                "security_questionnaire_status, created_at::text, " +
                "(license_expiry < CURRENT_DATE + 90) AS review_due " +
                "FROM vendors" + where +
                " ORDER BY CASE WHEN license_expiry < CURRENT_DATE THEN 0 WHEN license_expiry < CURRENT_DATE+90 THEN 1 ELSE 2 END, name" +
                (export ? "" : " LIMIT ? OFFSET ?");
            p = conn.prepareStatement(dataSql); int idx = 1;
            if (!isBlank(qStatus)) p.setString(idx++, qStatus);
            if (!isBlank(search))  { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); }
            if (!export) { p.setLong(idx++, limit); p.setLong(idx++, offset); }
            rs = p.executeQuery();
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
        JSONObject result = new JSONObject(); result.put("success", true); result.put("vendors", list);
        if (!export) {
            result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
            result.put("total_pages", (total + limit - 1) / limit);
        }
        return result;
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
    private JSONObject listTickets(JSONObject input, String role) throws Exception {
        String status     = (String) input.get("status");
        String priority   = (String) input.get("priority");
        String categoryId = (String) input.get("category_id");
        String search     = (String) input.get("search");
        boolean export = Boolean.TRUE.equals(input.get("export"));

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
        if (!isBlank(status))     where.append(" AND ht.status=?");
        if (!isBlank(priority))   where.append(" AND ht.priority=?");
        if (!isBlank(categoryId)) where.append(" AND ht.category_id=?::uuid");
        if (!isBlank(search))     where.append(" AND (ht.title ILIKE ? OR ht.description ILIKE ? OR cb.username ILIKE ?)");
        // Tickets awaiting Supervisor approval are invisible to IT/GRC queues
        // entirely (not present-but-locked) until a Supervisor approves them.
        if ("IT_STAFF".equals(role) || "GRC_OFFICER".equals(role)) where.append(" AND ht.approval_status != 'PENDING'");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        long total = 0;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            if (!export) {
                String countSql = "SELECT COUNT(*) FROM helpdesk_tickets ht LEFT JOIN users cb ON cb.id = ht.created_by" + where;
                p = conn.prepareStatement(countSql);
                int idx = 1;
                if (!isBlank(status))     p.setString(idx++, status);
                if (!isBlank(priority))   p.setString(idx++, priority);
                if (!isBlank(categoryId)) p.setString(idx++, categoryId);
                if (!isBlank(search))     { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
                rs = p.executeQuery();
                if (rs.next()) total = rs.getLong(1);
                try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
                rs = null; p = null;
            }

            String dataSql =
                "SELECT ht.id::text, ht.title, ht.description, ht.status, ht.priority, " +
                "TO_CHAR(ht.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at, " +
                "a.name AS asset_name, cb.username AS created_by_name, at.username AS assigned_to_name, ht.assigned_to::text, " +
                "tc.id::text AS category_id, tc.name AS category_name, " +
                "tsc.id::text AS subcategory_id, tsc.name AS subcategory_name, " +
                "ht.resolution_notes, " +
                "inc.id::text AS incident_id " +
                "FROM helpdesk_tickets ht " +
                "LEFT JOIN assets a ON a.id = ht.asset_id " +
                "LEFT JOIN users cb ON cb.id = ht.created_by " +
                "LEFT JOIN users at ON at.id = ht.assigned_to " +
                "LEFT JOIN ticket_categories tc ON tc.id = ht.category_id " +
                "LEFT JOIN ticket_subcategories tsc ON tsc.id = ht.subcategory_id " +
                "LEFT JOIN incidents inc ON inc.ticket_escalation_id = ht.id" + where +
                " ORDER BY CASE ht.priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, ht.created_at DESC" +
                (export ? "" : " LIMIT ? OFFSET ?");
            p = conn.prepareStatement(dataSql); int idx = 1;
            if (!isBlank(status))     p.setString(idx++, status);
            if (!isBlank(priority))   p.setString(idx++, priority);
            if (!isBlank(categoryId)) p.setString(idx++, categoryId);
            if (!isBlank(search))     { String like = "%" + search + "%"; p.setString(idx++, like); p.setString(idx++, like); p.setString(idx++, like); }
            if (!export) { p.setLong(idx++, limit); p.setLong(idx++, offset); }
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
                t.put("category_id",      rs.getString("category_id"));
                t.put("category_name",    rs.getString("category_name"));
                t.put("subcategory_id",   rs.getString("subcategory_id"));
                t.put("subcategory_name", rs.getString("subcategory_name"));
                t.put("resolution_notes", rs.getString("resolution_notes"));
                t.put("incident_id",      rs.getString("incident_id"));
                list.add(t);
            }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("tickets", list);
        if (!export) {
            result.put("total_count", total); result.put("page", page); result.put("page_size", limit);
            result.put("total_pages", (total + limit - 1) / limit);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String title      = (String) input.get("title");
        String desc       = (String) input.get("description");
        String priority   = (String) input.get("priority");
        String categoryId = (String) input.get("category_id");
        if (isBlank(title) || isBlank(desc) || isBlank(categoryId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "title, description and category_id required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO helpdesk_tickets (title,description,priority,category_id,subcategory_id,created_by,assigned_to,asset_id) VALUES (?,?,?::varchar,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid) RETURNING id::text");
            p.setString(1,title); p.setString(2,desc); p.setString(3, isBlank(priority)?"MEDIUM":priority);
            p.setString(4, categoryId);
            String subcategoryId=(String)input.get("subcategory_id"); p.setString(5, isBlank(subcategoryId)?null:subcategoryId);
            String createdBy=(String)input.get("created_by"); p.setString(6, isBlank(createdBy)?null:createdBy);
            String assignedTo=(String)input.get("assigned_to"); p.setString(7, isBlank(assignedTo)?null:assignedTo);
            String assetId=(String)input.get("asset_id"); p.setString(8, isBlank(assetId)?null:assetId);
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
        String newStatus = (String) input.get("status");

        JSONObject before = getTicketSnapshot(id);

        String catId    = (String) input.get("category_id");
        String subcatId = (String) input.get("subcategory_id");
        // A subcategory belongs to exactly one category, so whenever the
        // caller sends category_id (the only current caller, the console's
        // full-form edit modal, always does) subcategory_id is set directly
        // — including to NULL — rather than COALESCE'd, so re-pointing the
        // category without re-picking a subcategory can't leave a
        // subcategory on the ticket that belongs to the old category.
        boolean categoryProvided = !isBlank(catId);

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            // Excludes PENDING rows so IT/GRC can't act on a ticket out-of-band
            // (e.g. a stale tab) before a Supervisor has approved it.
            p = conn.prepareStatement(
                "UPDATE helpdesk_tickets SET status=COALESCE(?,status), priority=COALESCE(?,priority), description=COALESCE(?,description), " +
                "assigned_to=COALESCE(?::uuid,assigned_to), category_id=COALESCE(?::uuid,category_id), " +
                (categoryProvided ? "subcategory_id=?::uuid, " : "subcategory_id=COALESCE(?::uuid,subcategory_id), ") +
                "resolution_notes=COALESCE(?,resolution_notes) WHERE id=? AND approval_status != 'PENDING'"
            );
            p.setString(1, newStatus); p.setString(2,(String)input.get("priority"));
            p.setString(3,(String)input.get("description"));
            String at=(String)input.get("assigned_to"); p.setString(4, isBlank(at)?null:at);
            p.setString(5, isBlank(catId)?null:catId);
            p.setString(6, isBlank(subcatId)?null:subcatId);
            p.setString(7, (String)input.get("resolution_notes"));
            p.setObject(8, UUID.fromString(id));
            int updated = p.executeUpdate();
            if (updated == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found or awaiting supervisor approval", req.getRequestURI()); return;
            }
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }

        if (before != null && !isBlank(newStatus)) {
            String createdBy = (String) before.get("created_by");
            String title     = (String) before.get("title");
            String oldStatus = (String) before.get("status");
            if (createdBy != null && !newStatus.equals(oldStatus)) {
                Notification.emitToUser("TICKET_UPDATED", title,
                    "\"" + title + "\" is now " + newStatus,
                    "index.html", UUID.fromString(id), UUID.fromString(createdBy));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void escalateTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String ticketId = (String) input.get("ticket_id");
        if (isBlank(ticketId)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "ticket_id required", req.getRequestURI()); return; }

        JSONObject ticket = getTicketDetail(ticketId);
        if (ticket == null) { OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found", req.getRequestURI()); return; }
        String title       = (String) ticket.get("title");
        String description = (String) ticket.get("description");
        String priority     = (String) ticket.get("priority");

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        String newIncidentId = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            try {
                p = conn.prepareStatement(
                    "INSERT INTO incidents (title, description, severity, ticket_escalation_id) VALUES (?,?,?,?::uuid) RETURNING id::text"
                );
                p.setString(1, title);
                p.setString(2, description + "\n\n(Escalated from Helpdesk Ticket)");
                p.setString(3, isBlank(priority) ? "MEDIUM" : priority);
                p.setString(4, ticketId);
                rs = p.executeQuery();
                if (rs.next()) newIncidentId = rs.getString(1);
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    OutputProcessor.errorResponse(res, 409, "Conflict", "This ticket has already been escalated to an incident", req.getRequestURI());
                    return;
                }
                throw e;
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("incident_id", newIncidentId);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }

        if (newIncidentId != null) {
            Notification.emit("TICKET_ESCALATED", "incidents", "Ticket escalated to incident",
                "\"" + title + "\" was escalated from a helpdesk ticket to a security incident",
                "incidents-register.html", UUID.fromString(newIncidentId));
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject getTicketDetail(String id) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title, description, priority FROM helpdesk_tickets WHERE id = ?::uuid");
            p.setString(1, id);
            rs = p.executeQuery();
            if (!rs.next()) return null;
            JSONObject t = new JSONObject();
            t.put("title", rs.getString("title"));
            t.put("description", rs.getString("description"));
            t.put("priority", rs.getString("priority"));
            return t;
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject getTicketSnapshot(String id) throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title, status, created_by::text FROM helpdesk_tickets WHERE id = ?");
            p.setObject(1, UUID.fromString(id));
            rs = p.executeQuery();
            if (!rs.next()) return null;
            JSONObject snap = new JSONObject();
            snap.put("title", rs.getString("title"));
            snap.put("status", rs.getString("status"));
            snap.put("created_by", rs.getString("created_by"));
            return snap;
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private JSONObject listTicketCategories() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray categories = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM ticket_categories WHERE is_active = TRUE ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject c=new JSONObject(); c.put("id",rs.getString(1)); c.put("name",rs.getString(2)); categories.add(c); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("categories", categories); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listTicketSubcategories(JSONObject input) throws Exception {
        String categoryId = (String) input.get("category_id");
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray subcategories = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String sql = "SELECT id::text, name FROM ticket_subcategories WHERE is_active = TRUE" +
                (isBlank(categoryId) ? "" : " AND category_id = ?::uuid") + " ORDER BY name";
            p = conn.prepareStatement(sql);
            if (!isBlank(categoryId)) p.setString(1, categoryId);
            rs = p.executeQuery();
            while (rs.next()) { JSONObject c=new JSONObject(); c.put("id",rs.getString(1)); c.put("name",rs.getString(2)); subcategories.add(c); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("subcategories", subcategories); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listAssetCategories() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray categories = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, name FROM asset_categories WHERE is_active = TRUE ORDER BY name");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject c=new JSONObject(); c.put("id",rs.getString(1)); c.put("name",rs.getString(2)); categories.add(c); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("categories", categories); return result;
    }

    @SuppressWarnings("unchecked")
    private JSONObject listStaff() throws Exception {
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray staff = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT id::text, username FROM users WHERE status='ACTIVE' AND role IN ('IT_STAFF','GRC_OFFICER','ADMIN') ORDER BY username");
            rs = p.executeQuery();
            while (rs.next()) { JSONObject s=new JSONObject(); s.put("id",rs.getString(1)); s.put("username",rs.getString(2)); staff.add(s); }
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
        JSONObject result = new JSONObject(); result.put("success", true); result.put("staff", staff); return result;
    }

    @SuppressWarnings("unchecked")
    private void deleteChange(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title FROM change_requests WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String title = rs.next() ? rs.getString("title") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM change_requests WHERE id=?::uuid AND approval_status != 'PENDING'");
            p.setString(1, id);
            int deleted = p.executeUpdate();
            if (deleted == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Change request not found or awaiting supervisor approval", req.getRequestURI()); return;
            }
            JSONObject ctx = new JSONObject(); ctx.put("change_id", id); ctx.put("change_title", title);
            EventLog.log(InputProcessor.getEmail(req), "CHANGE_REQUEST_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteAsset(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT name FROM assets WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString("name") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM assets WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("asset_id", id); ctx.put("asset_name", name);
            EventLog.log(InputProcessor.getEmail(req), "ASSET_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteVendor(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT name FROM vendors WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String name = rs.next() ? rs.getString("name") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM vendors WHERE id=?::uuid");
            p.setString(1, id); p.executeUpdate();
            JSONObject ctx = new JSONObject(); ctx.put("vendor_id", id); ctx.put("vendor_name", name);
            EventLog.log(InputProcessor.getEmail(req), "VENDOR_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void deleteTicket(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        if (!"ADMIN".equals(InputProcessor.getRole(req))) {
            OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI()); return;
        }
        String id = (String) input.get("id");
        if (isBlank(id)) { OutputProcessor.errorResponse(res, 400, "Bad Request", "id required", req.getRequestURI()); return; }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT title FROM helpdesk_tickets WHERE id=?::uuid");
            p.setString(1, id); rs = p.executeQuery();
            String title = rs.next() ? rs.getString("title") : id;
            pool.cleanup(rs, p, null); rs = null; p = null;
            p = conn.prepareStatement("DELETE FROM helpdesk_tickets WHERE id=?::uuid AND approval_status != 'PENDING'");
            p.setString(1, id);
            int deleted = p.executeUpdate();
            if (deleted == 0) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Ticket not found or awaiting supervisor approval", req.getRequestURI()); return;
            }
            JSONObject ctx = new JSONObject(); ctx.put("ticket_id", id); ctx.put("ticket_title", title);
            EventLog.log(InputProcessor.getEmail(req), "TICKET_DELETED", ctx.toJSONString());
            JSONObject result = new JSONObject(); result.put("success", true); OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // IT staff already has full visibility over every ticket via list_tickets
    // above, so these carry no extra ownership check beyond that module gate.

    @SuppressWarnings("unchecked")
    private void listTicketAttachments(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String ticketId = (String) input.get("ticket_id");
        if (isBlank(ticketId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "ticket_id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT id::text, file_name, sha256_checksum, uploaded_at::text FROM ticket_attachments " +
                "WHERE ticket_id = ?::uuid ORDER BY uploaded_at DESC"
            );
            p.setString(1, ticketId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",              rs.getString("id"));
                a.put("file_name",       rs.getString("file_name"));
                a.put("sha256_checksum", rs.getString("sha256_checksum"));
                a.put("uploaded_at",     rs.getString("uploaded_at"));
                list.add(a);
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("attachments", list);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void getTicketAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT file_name, file_path FROM ticket_attachments WHERE id = ?::uuid");
            p.setString(1, id);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Attachment not found", req.getRequestURI()); return;
            }
            String fileName = rs.getString("file_name");
            String filePath = rs.getString("file_path");
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "File not found on server", req.getRequestURI()); return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("file_name", fileName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // Change Management staff already has full visibility over every change
    // request via list_changes above, so these carry no extra ownership check.

    private static final Set<String> ATTACHMENT_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".png", ".jpg", ".jpeg", ".zip", ".txt", ".csv"
    ));
    private static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024; // 10 MB

    @SuppressWarnings("unchecked")
    private void uploadChangeAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        String fileName = (String) input.get("file_name");
        String fileData = (String) input.get("file_data");
        if (isBlank(changeRequestId) || isBlank(fileName) || isBlank(fileData)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id, file_name and file_data are required", req.getRequestURI()); return;
        }
        String safeName = new File(fileName).getName();
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 ? safeName.substring(dot).toLowerCase() : "";
        if (!ATTACHMENT_EXTENSIONS.contains(ext)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "File type not allowed: " + ext, req.getRequestURI()); return;
        }
        String raw = fileData.contains(",") ? fileData.substring(fileData.indexOf(',') + 1) : fileData;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw.trim()); }
        catch (Exception e) { OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid base64 content", req.getRequestURI()); return; }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            OutputProcessor.errorResponse(res, 400, "Bad Request",
                "File exceeds the " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + "MB attachment size limit", req.getRequestURI());
            return;
        }

        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
            String checksum = sb.toString();

            String uploadDir = System.getenv("TSI_EXPORT_PATH");
            if (isBlank(uploadDir)) uploadDir = System.getProperty("user.home") + "/.tsi-compass/exports";
            File dir = new File(uploadDir + "/change_request_attachments");
            if (!dir.exists() && !dir.mkdirs()) {
                OutputProcessor.errorResponse(res, 500, "Internal Error", "Cannot create upload directory", req.getRequestURI()); return;
            }
            File dest = new File(dir, UUID.randomUUID().toString() + ext);
            try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }

            UUID uploadedBy = InputProcessor.getAuthenticatedUserId(req);
            p = conn.prepareStatement(
                "INSERT INTO change_request_attachments (change_request_id, file_name, file_path, sha256_checksum, uploaded_by) " +
                "VALUES (?::uuid, ?, ?, ?, ?) RETURNING id::text"
            );
            p.setString(1, changeRequestId); p.setString(2, safeName);
            p.setString(3, dest.getAbsolutePath()); p.setString(4, checksum); p.setObject(5, uploadedBy);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void listChangeAttachments(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        if (isBlank(changeRequestId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT id::text, file_name, sha256_checksum, uploaded_at::text FROM change_request_attachments " +
                "WHERE change_request_id = ?::uuid ORDER BY uploaded_at DESC"
            );
            p.setString(1, changeRequestId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject a = new JSONObject();
                a.put("id",              rs.getString("id"));
                a.put("file_name",       rs.getString("file_name"));
                a.put("sha256_checksum", rs.getString("sha256_checksum"));
                a.put("uploaded_at",     rs.getString("uploaded_at"));
                list.add(a);
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("attachments", list);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void getChangeAttachment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String id = (String) input.get("id");
        if (isBlank(id)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("SELECT file_name, file_path FROM change_request_attachments WHERE id = ?::uuid");
            p.setString(1, id);
            rs = p.executeQuery();
            if (!rs.next()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "Attachment not found", req.getRequestURI()); return;
            }
            String fileName = rs.getString("file_name");
            String filePath = rs.getString("file_path");
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                OutputProcessor.errorResponse(res, 404, "Not Found", "File not found on server", req.getRequestURI()); return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("file_name", fileName);
            result.put("file_data", Base64.getEncoder().encodeToString(bytes));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    // Comment thread on a change request - append-only, any Change Management
    // staff can read or add to it (no extra ownership check, same as the
    // attachment funcs above).

    @SuppressWarnings("unchecked")
    private void listChangeComments(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        if (isBlank(changeRequestId)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id is required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        JSONArray list = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "SELECT cc.id::text, cc.comment, " +
                "TO_CHAR(cc.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at, " +
                "u.username AS author_name " +
                "FROM change_request_comments cc LEFT JOIN users u ON u.id = cc.author_id " +
                "WHERE cc.change_request_id = ?::uuid ORDER BY cc.created_at ASC"
            );
            p.setString(1, changeRequestId);
            rs = p.executeQuery();
            while (rs.next()) {
                JSONObject c = new JSONObject();
                c.put("id",          rs.getString("id"));
                c.put("comment",     rs.getString("comment"));
                c.put("created_at",  rs.getString("created_at"));
                c.put("author_name", rs.getString("author_name"));
                list.add(c);
            }
            JSONObject result = new JSONObject(); result.put("success", true); result.put("comments", list);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void addChangeComment(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String changeRequestId = (String) input.get("change_request_id");
        String comment = (String) input.get("comment");
        if (isBlank(changeRequestId) || isBlank(comment)) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "change_request_id and comment are required", req.getRequestURI()); return;
        }
        UUID authorId = InputProcessor.getAuthenticatedUserId(req);
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO change_request_comments (change_request_id, author_id, comment) " +
                "VALUES (?::uuid, ?, ?) RETURNING id::text"
            );
            p.setString(1, changeRequestId); p.setObject(2, authorId); p.setString(3, comment);
            rs = p.executeQuery();
            JSONObject result = new JSONObject(); result.put("success", true);
            if (rs.next()) result.put("id", rs.getString(1));
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(rs, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importChanges(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement("INSERT INTO change_requests (title, description, stage, status) VALUES (?, ?, ?, ?)");
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String title = strVal(row, "title"); String desc = strVal(row, "description");
                if (isBlank(title) || isBlank(desc)) { errors.add("Row skipped: title and description required"); continue; }
                String stage = strVal(row, "stage"); String status = strVal(row, "status");
                try {
                    p.setString(1, title); p.setString(2, desc);
                    p.setString(3, isBlank(stage)  ? "BRD"   : stage.toUpperCase());
                    p.setString(4, isBlank(status) ? "DRAFT" : status.toUpperCase());
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+title+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "CHANGES_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importAssets(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            java.util.Map<String,String> categoryByName = new java.util.HashMap<>();
            p = conn.prepareStatement("SELECT id::text, name FROM asset_categories WHERE is_active = TRUE");
            rs = p.executeQuery();
            while (rs.next()) categoryByName.put(rs.getString("name").toUpperCase(), rs.getString("id"));
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement(
                "INSERT INTO assets (name, asset_tag, location, category_id, criticality, description, patch_status, lifecycle_status) VALUES (?, ?, ?, ?::uuid, ?, ?, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String name = strVal(row, "name"); String assetTag = strVal(row, "asset_tag");
                String cat = strVal(row, "category"); String crit = strVal(row, "criticality");
                if (isBlank(name) || isBlank(assetTag) || isBlank(cat) || isBlank(crit)) { errors.add("Row skipped: name, asset tag, category and criticality required"); continue; }
                String categoryId = categoryByName.get(cat.trim().toUpperCase());
                if (categoryId == null) { errors.add("Row '"+name+"': unknown category '"+cat+"'"); continue; }
                try {
                    p.setString(1, name); p.setString(2, assetTag.trim());
                    p.setString(3, strVal(row, "location"));
                    p.setString(4, categoryId); p.setString(5, crit.toUpperCase());
                    p.setString(6, strVal(row, "description"));
                    String ps = strVal(row, "patch_status");
                    p.setString(7, isBlank(ps) ? "CURRENT" : ps.toUpperCase());
                    String ls = strVal(row, "lifecycle_status");
                    p.setString(8, isBlank(ls) ? "ACTIVE" : ls.toUpperCase());
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+name+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "ASSETS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importVendors(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            p = conn.prepareStatement(
                "INSERT INTO vendors (name, agreement_details, license_expiry, baseline_risk_score, security_questionnaire_status) VALUES (?, ?, ?::date, ?, ?)"
            );
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String name = strVal(row, "name");
                if (isBlank(name)) { errors.add("Row skipped: name required"); continue; }
                try {
                    p.setString(1, name); p.setString(2, strVal(row, "agreement_details"));
                    String exp = strVal(row, "license_expiry"); p.setString(3, isBlank(exp) ? null : exp);
                    Object scoreObj = row.get("baseline_risk_score");
                    String scoreStr = scoreObj != null ? scoreObj.toString().trim() : "";
                    if (!scoreStr.isEmpty()) { p.setInt(4, (int) Double.parseDouble(scoreStr)); }
                    else { p.setNull(4, Types.INTEGER); }
                    String qs = strVal(row, "security_questionnaire_status");
                    p.setString(5, isBlank(qs) ? null : qs.toUpperCase());
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+name+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "VENDORS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    @SuppressWarnings("unchecked")
    private void importTickets(HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        JSONArray rows = (JSONArray) input.get("rows");
        if (rows == null || rows.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad Request", "rows array required", req.getRequestURI()); return;
        }
        PoolDB pool = null; Connection conn = null; PreparedStatement p = null; ResultSet rs = null;
        int imported = 0; JSONArray errors = new JSONArray();
        try {
            pool = new PoolDB(); conn = pool.getConnection();

            java.util.Map<String,String> categoryByName = new java.util.HashMap<>();
            p = conn.prepareStatement("SELECT id::text, name FROM ticket_categories WHERE is_active = TRUE");
            rs = p.executeQuery();
            while (rs.next()) categoryByName.put(rs.getString("name").toUpperCase(), rs.getString("id"));
            try { pool.cleanup(rs, p, null); } catch (Exception ignored) {}
            rs = null; p = null;

            p = conn.prepareStatement("INSERT INTO helpdesk_tickets (title, description, priority, category_id) VALUES (?, ?, ?, ?::uuid)");
            for (Object obj : rows) {
                JSONObject row = (JSONObject) obj;
                String title = strVal(row, "title"); String desc = strVal(row, "description");
                String cat = strVal(row, "category");
                if (isBlank(title) || isBlank(desc) || isBlank(cat)) { errors.add("Row skipped: title, description and category required"); continue; }
                String categoryId = categoryByName.get(cat.trim().toUpperCase());
                if (categoryId == null) { errors.add("Row '"+title+"': unknown category '"+cat+"'"); continue; }
                try {
                    p.setString(1, title); p.setString(2, desc);
                    String prio = strVal(row, "priority");
                    p.setString(3, isBlank(prio) ? "MEDIUM" : prio.toUpperCase());
                    p.setString(4, categoryId);
                    p.executeUpdate(); imported++;
                } catch (Exception ex) { errors.add("Row '"+title+"': "+ex.getMessage()); }
            }
            EventLog.log(InputProcessor.getEmail(req), "TICKETS_IMPORTED", "{\"count\":"+imported+"}");
            JSONObject result = new JSONObject(); result.put("success", true);
            result.put("imported", (long) imported); result.put("errors", errors);
            OutputProcessor.send(res, 200, result);
        } finally { if (pool != null) try { pool.cleanup(null, p, conn); } catch(Exception i){} }
    }

    private String strVal(JSONObject obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString().trim();
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
